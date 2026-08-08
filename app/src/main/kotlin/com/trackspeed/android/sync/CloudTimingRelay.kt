package com.trackspeed.android.sync

import android.os.Build
import android.util.Log
import com.trackspeed.android.cloud.AuthService
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase Realtime Broadcast backup for an active BLE timing session.
 *
 * The channel and event/payload shape match iOS
 * `SupabaseBroadcastTransport`: `timing_<session UUID>`, event
 * `timing_message`, payload `{ "payload": <TimingMessage> }`. BLE remains
 * the latency-critical primary path; receiver deduplication makes dual-path
 * delivery exactly-once at the semantic layer.
 */
@Singleton
class CloudTimingRelay @Inject constructor(
    private val supabase: SupabaseClient,
    private val authService: AuthService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incomingMessages = MutableSharedFlow<TimingMessage>(extraBufferCapacity = 128)
    val incomingMessages: SharedFlow<TimingMessage> = _incomingMessages.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val subscribed = AtomicBoolean(false)
    private val relayGeneration = AtomicLong(0L)
    @Volatile private var activeSessionId: String? = null
    @Volatile private var channel: RealtimeChannel? = null
    private var channelJob: Job? = null

    val isAvailable: Boolean get() = started.get()

    @Synchronized
    fun start(sessionId: String, deviceId: String, isHost: Boolean) {
        val normalized = sessionId.lowercase()
        if (activeSessionId == normalized && channelJob?.isActive == true) return
        val generation = relayGeneration.incrementAndGet()
        val previousChannel = channel
        activeSessionId = normalized
        started.set(true)
        subscribed.set(false)
        channel = null
        channelJob?.cancel()
        channelJob = scope.launch {
            previousChannel?.let { runCatching { supabase.realtime.removeChannel(it) } }
            var retryDelayMs = 1_000L
            while (started.get() && activeSessionId == normalized && relayGeneration.get() == generation) {
                var realtimeChannel: RealtimeChannel? = null
                try {
                    authService.ensureAnonymousSession()
                    realtimeChannel = supabase.channel("timing_$normalized") {
                        broadcast { receiveOwnBroadcasts = false }
                    }
                    channel = realtimeChannel
                    val timingFlow = realtimeChannel.broadcastFlow<JsonObject>(TIMING_EVENT)
                    realtimeChannel.subscribe(blockUntilSubscribed = true)
                    if (relayGeneration.get() != generation) return@launch
                    subscribed.set(true)
                    retryDelayMs = 1_000L
                    sendPresence(realtimeChannel, deviceId, isHost, joined = true)
                    Log.i(TAG, "Cloud timing fallback subscribed for ${normalized.take(8)}")
                    timingFlow.collect { payload ->
                        decodeBroadcastPayload(payload)?.let { message ->
                            if (relayGeneration.get() == generation &&
                                message.sessionId.equals(normalized, ignoreCase = true) &&
                                message.senderId != deviceId
                            ) {
                                _incomingMessages.emit(message)
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (relayGeneration.get() == generation) {
                        subscribed.set(false)
                        Log.w(TAG, "Cloud timing fallback disconnected; retrying", error)
                    }
                } finally {
                    if (relayGeneration.get() == generation) subscribed.set(false)
                    if (channel === realtimeChannel) channel = null
                    realtimeChannel?.let { runCatching { supabase.realtime.removeChannel(it) } }
                }
                if (started.get() && activeSessionId == normalized && relayGeneration.get() == generation) {
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    fun send(message: TimingMessage): Boolean {
        if (!started.get()) return false
        if (!message.sessionId.equals(activeSessionId, ignoreCase = true)) {
            Log.w(TAG, "Refusing cloud timing send for inactive session")
            return false
        }
        val relayChannel = channel ?: return false
        scope.launch {
            runCatching {
                relayChannel.broadcast(
                    event = TIMING_EVENT,
                    message = encodeBroadcastPayload(message)
                )
            }.onFailure { error ->
                Log.w(TAG, "Cloud timing message send failed", error)
            }
        }
        return subscribed.get()
    }

    @Synchronized
    fun stop(deviceId: String, isHost: Boolean) {
        val oldChannel = channel
        relayGeneration.incrementAndGet()
        started.set(false)
        subscribed.set(false)
        activeSessionId = null
        channel = null
        channelJob?.cancel()
        channelJob = null
        if (oldChannel != null) {
            scope.launch {
                runCatching { sendPresence(oldChannel, deviceId, isHost, joined = false) }
                runCatching { supabase.realtime.removeChannel(oldChannel) }
            }
        }
    }

    private suspend fun sendPresence(
        realtimeChannel: RealtimeChannel,
        deviceId: String,
        isHost: Boolean,
        joined: Boolean
    ) {
        realtimeChannel.broadcast(
            event = PRESENCE_EVENT,
            message = buildJsonObject {
                put("deviceId", deviceId)
                put("deviceName", "Android ${Build.MODEL}")
                put("joined", joined)
                put("isHost", isHost)
            }
        )
    }

    internal companion object {
        private const val TAG = "CloudTimingRelay"
        private const val TIMING_EVENT = "timing_message"
        private const val PRESENCE_EVENT = "presence"

        internal fun encodeBroadcastPayload(message: TimingMessage): JsonObject =
            buildJsonObject {
                put(
                    "payload",
                    TimingMessageCodec.json.encodeToJsonElement(
                        TimingMessage.serializer(),
                        message
                    )
                )
            }

        internal fun decodeBroadcastPayload(payload: JsonObject): TimingMessage? {
            val messageJson = payload["payload"] ?: return null
            return runCatching {
                TimingMessageCodec.json.decodeFromJsonElement(
                    TimingMessage.serializer(),
                    messageJson
                )
            }.getOrNull()
        }
    }
}
