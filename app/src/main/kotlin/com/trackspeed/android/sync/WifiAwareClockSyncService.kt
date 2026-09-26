package com.trackspeed.android.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.ServiceDiscoveryInfo
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import com.trackspeed.android.protocol.TimingPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android-only Wi-Fi Aware clock lane.
 *
 * BLE remains the authenticated bootstrap and carries the session protocol.
 * When both Android apps advertise this capability, the requester and reference
 * derive a per-session Aware key from identities already exchanged over the
 * encrypted BLE link. Only clock samples move to the secured NDP-bound socket.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class WifiAwareClockSyncService(context: Context) {
    data class AttemptKey(
        val attemptId: String,
        val passphrase: String
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(WifiAwareManager::class.java)
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var referenceJob: Job? = null

    fun isAvailable(): Boolean {
        return appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            manager?.isAvailable == true &&
            connectivityManager != null &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED &&
            selectSharedKeyCipherSuite(manager.characteristics?.supportedCipherSuites ?: 0) !=
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NONE
    }

    fun startReference(
        sessionId: String,
        requesterId: String,
        referenceId: String,
        onProgress: (Float) -> Unit,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!isAvailable()) return false
        val key = deriveAttemptKey(sessionId, requesterId, referenceId)
        referenceJob?.cancel()
        referenceJob = scope.launch {
            val passed = runCatching {
                runInterruptible {
                    runReferenceBlocking(key, requesterId, referenceId, onProgress)
                }
            }.onFailure { throwable ->
                Log.w(TAG, "[AWARE_CLOCK_REFERENCE] attempt=${key.attemptId} failed", throwable)
            }.getOrDefault(false)
            Log.i(
                TAG,
                "[AWARE_CLOCK_REFERENCE] attempt=${key.attemptId} complete=$passed"
            )
            onComplete(passed)
        }
        return true
    }

    suspend fun measureAsRequester(
        sessionId: String,
        requesterId: String,
        referenceId: String,
        onProgress: (Float) -> Unit
    ): ClockSyncCalculator.SyncResult? {
        if (!isAvailable()) return null
        val key = deriveAttemptKey(sessionId, requesterId, referenceId)
        return runCatching {
            runInterruptible(Dispatchers.IO) {
                runRequesterBlocking(key, requesterId, referenceId, onProgress)
            }
        }.onFailure { throwable ->
            Log.w(TAG, "[AWARE_CLOCK_REQUESTER] attempt=${key.attemptId} failed", throwable)
        }.getOrNull()
    }

    fun close() {
        cancelReference()
        scope.cancel()
    }

    fun cancelReference() {
        referenceJob?.cancel()
        referenceJob = null
    }

    @SuppressLint("MissingPermission")
    private fun runReferenceBlocking(
        key: AttemptKey,
        requesterId: String,
        referenceId: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        val resources = ConnectionAttemptResources()
        val awareSession = AtomicReference<WifiAwareSession?>()
        val publishSession = AtomicReference<PublishDiscoverySession?>()
        val networkCallback = AtomicReference<ConnectivityManager.NetworkCallback?>()
        val serverSocket = AtomicReference<ServerSocket?>()
        val dataSocket = AtomicReference<Socket?>()
        val published = CountDownLatch(1)
        val exchangeComplete = CountDownLatch(1)
        val requestStarted = AtomicBoolean(false)
        val failure = AtomicReference<String?>()

        try {
            val awareManager = requireNotNull(manager)
            val characteristics = requireNotNull(awareManager.characteristics)
            val cipherSuite = selectSharedKeyCipherSuite(characteristics.supportedCipherSuites)
            val security = WifiAwareDataPathSecurityConfig.Builder(cipherSuite)
                .setPskPassphrase(key.passphrase)
                .build()
            awareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    runCatching {
                    if (!resources.track { session.close() }) return
                    awareSession.set(session)
                    val configBuilder = PublishConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setServiceSpecificInfo(key.attemptId.toByteArray(Charsets.UTF_8))
                        .setPublishType(PublishConfig.PUBLISH_TYPE_SOLICITED)
                        .setDataPathSecurityConfig(security)
                    val instantMode = characteristics.isInstantCommunicationModeSupported
                    if (instantMode) {
                        // Track timing values range over bulk throughput. Android's
                        // instant mode accelerates discovery/NDP setup for 30 seconds;
                        // 2.4 GHz keeps the widest practical gate-to-gate reach.
                        configBuilder.setInstantCommunicationModeEnabled(
                            true,
                            ScanResult.WIFI_BAND_24_GHZ
                        )
                    }
                    Log.i(
                        TAG,
                        "[AWARE_CLOCK_REFERENCE] attempt=${key.attemptId} " +
                            "instantMode=$instantMode band=2.4GHz"
                    )
                    val config = configBuilder.build()
                    session.publish(config, object : DiscoverySessionCallback() {
                        override fun onPublishStarted(session: PublishDiscoverySession) {
                    if (!resources.track { session.close() }) return
                            publishSession.set(session)
                            published.countDown()
                            Log.i(
                                TAG,
                                "[AWARE_CLOCK_REFERENCE] attempt=${key.attemptId} state=published"
                            )
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    runCatching {
                            if (!resources.isOpen) return
                            if (!message.contentEquals(connectMessage(key)) ||
                                !requestStarted.compareAndSet(false, true)
                            ) {
                                return
                            }
                            val listener = ServerSocket(0).apply {
                                reuseAddress = true
                                soTimeout = SOCKET_TIMEOUT_MS
                            }
                            if (!resources.track { listener.closeQuietly() }) return
                            serverSocket.set(listener)
                            val activePublishSession = requireNotNull(publishSession.get())
                            networkCallback.set(
                                requestPublisherDataPath(
                                    activePublishSession,
                                    peerHandle,
                                    listener.localPort,
                                    key,
                                    failure,
                                    resources
                                )
                            )
                            activePublishSession.sendMessage(
                                peerHandle,
                                PATH_READY_MESSAGE_ID,
                                readyMessage(key)
                            )
                            scope.launch {
                                runCatching {
                                    val socket = listener.accept().apply {
                                        tcpNoDelay = true
                                        soTimeout = SOCKET_TIMEOUT_MS
                                    }
                                    if (!resources.track { socket.closeQuietly() }) return@runCatching
                                    dataSocket.set(socket)
                                    serveClockProtocol(
                                        socket,
                                        requesterId,
                                        referenceId,
                                        onProgress
                                    )
                                }.onFailure { throwable ->
                                    failure.compareAndSet(
                                        null,
                                        "reference_socket_${throwable::class.java.simpleName}"
                                    )
                                }
                                exchangeComplete.countDown()
                            }
                                            }.onFailure { error ->
                        failure.compareAndSet(null, "callback_${error::class.java.simpleName}")
                        published.countDown()
                        exchangeComplete.countDown()
                    }
                }

                        override fun onSessionConfigFailed() {
                            failure.compareAndSet(null, "publish_config_failed")
                            published.countDown()
                            exchangeComplete.countDown()
                        }

                        override fun onSessionTerminated() {
                            failure.compareAndSet(null, "publish_session_terminated")
                            exchangeComplete.countDown()
                        }
                    }, handler)
                                    }.onFailure { error ->
                        failure.compareAndSet(null, "callback_${error::class.java.simpleName}")
                        published.countDown()
                        exchangeComplete.countDown()
                    }
                }

                override fun onAttachFailed() {
                    failure.compareAndSet(null, "attach_failed")
                    published.countDown()
                    exchangeComplete.countDown()
                }
            }, handler)

            if (!published.await(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, "publish_timeout")
            } else if (!exchangeComplete.await(EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, "exchange_timeout")
            }
            return failure.get() == null
        } finally {
            resources.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRequesterBlocking(
        key: AttemptKey,
        requesterId: String,
        referenceId: String,
        onProgress: (Float) -> Unit
    ): ClockSyncCalculator.SyncResult? {
        val resources = ConnectionAttemptResources()
        val awareSession = AtomicReference<WifiAwareSession?>()
        val subscribeSession = AtomicReference<SubscribeDiscoverySession?>()
        val networkCallback = AtomicReference<ConnectivityManager.NetworkCallback?>()
        val dataSocket = AtomicReference<Socket?>()
        val subscribed = CountDownLatch(1)
        val exchangeComplete = CountDownLatch(1)
        val discovered = AtomicBoolean(false)
        val pathRequested = AtomicBoolean(false)
        val result = AtomicReference<ClockSyncCalculator.SyncResult?>()
        val failure = AtomicReference<String?>()

        try {
            val awareManager = requireNotNull(manager)
            val characteristics = requireNotNull(awareManager.characteristics)
            awareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    runCatching {
                    if (!resources.track { session.close() }) return
                    awareSession.set(session)
                    val configBuilder = SubscribeConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
                    val instantMode = characteristics.isInstantCommunicationModeSupported
                    if (instantMode) {
                        configBuilder.setInstantCommunicationModeEnabled(
                            true,
                            ScanResult.WIFI_BAND_24_GHZ
                        )
                    }
                    Log.i(
                        TAG,
                        "[AWARE_CLOCK_REQUESTER] attempt=${key.attemptId} " +
                            "instantMode=$instantMode band=2.4GHz"
                    )
                    val config = configBuilder.build()
                    session.subscribe(config, object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    if (!resources.track { session.close() }) return
                            subscribeSession.set(session)
                            subscribed.countDown()
                            Log.i(
                                TAG,
                                "[AWARE_CLOCK_REQUESTER] attempt=${key.attemptId} state=subscribed"
                            )
                        }

                        override fun onServiceDiscovered(info: ServiceDiscoveryInfo) {
                    runCatching {
                            if (!resources.isOpen) return
                            if (info.serviceSpecificInfo?.contentEquals(
                                    key.attemptId.toByteArray(Charsets.UTF_8)
                                ) != true || !discovered.compareAndSet(false, true)
                            ) {
                                return
                            }
                            requireNotNull(subscribeSession.get()).sendMessage(
                                info.peerHandle,
                                CONNECT_MESSAGE_ID,
                                connectMessage(key)
                            )
                            Log.i(
                                TAG,
                                "[AWARE_CLOCK_REQUESTER] attempt=${key.attemptId} state=discovered"
                            )
                                            }.onFailure { error ->
                        failure.compareAndSet(null, "callback_${error::class.java.simpleName}")
                        subscribed.countDown()
                        exchangeComplete.countDown()
                    }
                }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    runCatching {
                            if (!resources.isOpen) return
                            if (!message.contentEquals(readyMessage(key)) ||
                                !pathRequested.compareAndSet(false, true)
                            ) {
                                return
                            }
                            networkCallback.set(
                                requestSubscriberDataPath(
                                    requireNotNull(subscribeSession.get()),
                                    peerHandle,
                                    key,
                                    requesterId,
                                    referenceId,
                                    result,
                                    failure,
                                    dataSocket,
                                    exchangeComplete,
                                    onProgress,
                                    resources
                                )
                            )
                                            }.onFailure { error ->
                        failure.compareAndSet(null, "callback_${error::class.java.simpleName}")
                        subscribed.countDown()
                        exchangeComplete.countDown()
                    }
                }

                        override fun onSessionConfigFailed() {
                            failure.compareAndSet(null, "subscribe_config_failed")
                            subscribed.countDown()
                            exchangeComplete.countDown()
                        }

                        override fun onSessionTerminated() {
                            failure.compareAndSet(null, "subscribe_session_terminated")
                            exchangeComplete.countDown()
                        }
                    }, handler)
                                    }.onFailure { error ->
                        failure.compareAndSet(null, "callback_${error::class.java.simpleName}")
                        subscribed.countDown()
                        exchangeComplete.countDown()
                    }
                }

                override fun onAttachFailed() {
                    failure.compareAndSet(null, "attach_failed")
                    subscribed.countDown()
                    exchangeComplete.countDown()
                }
            }, handler)

            if (!subscribed.await(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, "subscribe_timeout")
            } else if (!exchangeComplete.await(EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, "exchange_timeout")
            }
            if (failure.get() != null) {
                Log.w(
                    TAG,
                    "[AWARE_CLOCK_REQUESTER] attempt=${key.attemptId} error=${failure.get()}"
                )
                return null
            }
            return result.get()
        } finally {
            resources.close()
        }
    }

    private fun requestPublisherDataPath(
        session: PublishDiscoverySession,
        peer: PeerHandle,
        port: Int,
        key: AttemptKey,
        failure: AtomicReference<String?>,
        resources: ConnectionAttemptResources
    ): ConnectivityManager.NetworkCallback {
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peer)
            .setPskPassphrase(key.passphrase)
            .setPort(port)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(
                    TAG,
                    "[AWARE_CLOCK_REFERENCE] attempt=${key.attemptId} state=network_available"
                )
            }

            override fun onUnavailable() {
                failure.compareAndSet(null, "reference_network_unavailable")
            }
        }
        connectivityManager?.requestNetwork(awareNetworkRequest(specifier), callback)
        resources.track { connectivityManager?.unregisterNetworkCallback(callback) }
        return callback
    }

    private fun requestSubscriberDataPath(
        session: SubscribeDiscoverySession,
        peer: PeerHandle,
        key: AttemptKey,
        requesterId: String,
        referenceId: String,
        result: AtomicReference<ClockSyncCalculator.SyncResult?>,
        failure: AtomicReference<String?>,
        dataSocket: AtomicReference<Socket?>,
        exchangeComplete: CountDownLatch,
        onProgress: (Float) -> Unit,
        resources: ConnectionAttemptResources
    ): ConnectivityManager.NetworkCallback {
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peer)
            .setPskPassphrase(key.passphrase)
            .build()
        val socketStarted = AtomicBoolean(false)

        fun maybeStartSocket(network: Network, capabilities: NetworkCapabilities?) {
            if (!resources.isOpen) return
            val info = capabilities?.transportInfo as? WifiAwareNetworkInfo ?: return
            val address = info.peerIpv6Addr ?: return
            if (info.port <= 0 || !socketStarted.compareAndSet(false, true)) return
            scope.launch {
                runCatching {
                    val socket = network.socketFactory.createSocket(address, info.port).apply {
                        tcpNoDelay = true
                        soTimeout = SOCKET_TIMEOUT_MS
                    }
                    if (!resources.track { socket.closeQuietly() }) return@runCatching
                    dataSocket.set(socket)
                    result.set(
                        runClockProtocol(socket, requesterId, referenceId, onProgress)
                    )
                }.onFailure { throwable ->
                    failure.compareAndSet(
                        null,
                        "requester_socket_${throwable::class.java.simpleName}"
                    )
                }
                exchangeComplete.countDown()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onAvailable(network: Network) {
                maybeStartSocket(network, connectivityManager?.getNetworkCapabilities(network))
                handler.postDelayed(
                    {
                        maybeStartSocket(
                            network,
                            connectivityManager?.getNetworkCapabilities(network)
                        )
                    },
                    NETWORK_CAPABILITIES_RETRY_DELAY_MS
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                maybeStartSocket(network, networkCapabilities)
            }

            override fun onUnavailable() {
                failure.compareAndSet(null, "requester_network_unavailable")
                exchangeComplete.countDown()
            }
        }
        connectivityManager?.requestNetwork(awareNetworkRequest(specifier), callback)
        resources.track { connectivityManager?.unregisterNetworkCallback(callback) }
        return callback
    }

    private fun serveClockProtocol(
        socket: Socket,
        requesterId: String,
        referenceId: String,
        onProgress: (Float) -> Unit
    ) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        repeat(PROTOCOL_MESSAGE_COUNT) { index ->
            val request = TimingMessageCodec.decodeFromBytes(input.readSizedMessage())
            val t2Nanos = SystemClock.elapsedRealtimeNanos()
            val ping = request.payload as? TimingPayload.SyncPing
                ?: error("Expected Wi-Fi Aware syncPing")
            check(request.seq == index.toLong())
            check(request.senderId.equals(requesterId, ignoreCase = true))
            check(ping.requesterId.equals(requesterId, ignoreCase = true))
            check(ping.pingId == "aware-$index")

            val pong = TimingMessage(
                seq = request.seq,
                senderId = referenceId,
                sessionId = request.sessionId,
                payload = TimingPayload.SyncPong(
                    pingId = ping.pingId,
                    t1Nanos = ping.t1Nanos,
                    t2Nanos = t2Nanos,
                    t3Nanos = 0L,
                    requesterId = requesterId
                ),
                createdAtNanos = t2Nanos
            )
            val template = TimingMessageCodec.prepareSyncPongTimestampTemplate(pong)
            val t3Nanos = SystemClock.elapsedRealtimeNanos()
            output.writeSizedMessage(template.stamp(t3Nanos))
            output.flush()
            onProgress(
                ClockSyncSetupProgress.fromCompletedSamples(
                    completedSamples = index + 1,
                    samplesPerMeasurement = PROTOCOL_MESSAGE_COUNT,
                    maximumMeasurements = 1
                )
            )
        }
        Log.i(
            TAG,
            "[AWARE_CLOCK_REFERENCE] messages=$PROTOCOL_MESSAGE_COUNT ordered=true duplicates=0"
        )
    }

    private fun runClockProtocol(
        socket: Socket,
        requesterId: String,
        referenceId: String,
        onProgress: (Float) -> Unit
    ): ClockSyncCalculator.SyncResult {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val calculator = ClockSyncCalculator(isFullSync = true)
        val pingTemplates = List(PROTOCOL_MESSAGE_COUNT) { index ->
            TimingMessageCodec.prepareSyncPingTimestampTemplate(
                TimingMessage(
                    seq = index.toLong(),
                    senderId = requesterId,
                    sessionId = "aware-clock",
                    payload = TimingPayload.SyncPing(
                        pingId = "aware-$index",
                        t1Nanos = 0L,
                        requesterId = requesterId
                    ),
                    createdAtNanos = SystemClock.elapsedRealtimeNanos()
                )
            )
        }

        // A sequential TCP ping/pong wakes the Wi-Fi Aware data path once per
        // sample on current Pixels. Queue a small cohort before reading the
        // replies so one radio service period yields several independent NTP
        // tuples. Each tuple still carries its own t1/t2/t3/t4 and sequence;
        // the estimator keeps only the lowest-delay observations.
        for (batchStart in 0 until PROTOCOL_MESSAGE_COUNT step PROTOCOL_BATCH_SIZE) {
            val batchEnd = minOf(batchStart + PROTOCOL_BATCH_SIZE, PROTOCOL_MESSAGE_COUNT)
            for (index in batchStart until batchEnd) {
                val t1Nanos = SystemClock.elapsedRealtimeNanos()
                output.writeSizedMessage(pingTemplates[index].stamp(t1Nanos))
            }
            output.flush()

            for (index in batchStart until batchEnd) {
                val response = TimingMessageCodec.decodeFromBytes(input.readSizedMessage())
                val t4Nanos = SystemClock.elapsedRealtimeNanos()
                val pong = response.payload as? TimingPayload.SyncPong
                    ?: error("Expected Wi-Fi Aware syncPong")
                check(response.seq == index.toLong())
                check(response.senderId.equals(referenceId, ignoreCase = true))
                check(pong.pingId == "aware-$index")
                calculator.addSample(
                    ClockSyncCalculator.SyncSample(
                        t1 = pong.t1Nanos,
                        t2 = pong.t2Nanos,
                        t3 = pong.t3Nanos,
                        t4 = t4Nanos
                    )
                )
                onProgress(
                    ClockSyncSetupProgress.fromCompletedSamples(
                        completedSamples = index + 1,
                        samplesPerMeasurement = PROTOCOL_MESSAGE_COUNT,
                        maximumMeasurements = 1
                    )
                )
            }
        }
        val result = requireNotNull(calculator.calculateOffset())
        Log.i(
            TAG,
            "[AWARE_CLOCK_REQUESTER] messages=$PROTOCOL_MESSAGE_COUNT " +
                "batchSize=$PROTOCOL_BATCH_SIZE ordered=true duplicates=0 " +
                "samples=${result.samplesUsed}/${result.totalSamples} " +
                "minRttMs=${String.format(Locale.US, "%.3f", result.minRttMs)} " +
                "uncertaintyMs=${String.format(Locale.US, "%.3f", result.uncertaintyMs)} " +
                "quality=${result.quality}"
        )
        return result
    }

    private fun awareNetworkRequest(specifier: WifiAwareNetworkSpecifier): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

    private fun DataInputStream.readSizedMessage(): ByteArray {
        val size = readInt()
        require(size in 1..MAXIMUM_PROTOCOL_MESSAGE_BYTES)
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeSizedMessage(message: ByteArray) {
        require(message.size <= MAXIMUM_PROTOCOL_MESSAGE_BYTES)
        writeInt(message.size)
        write(message)
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private fun ServerSocket.closeQuietly() {
        runCatching { close() }
    }

    private fun connectMessage(key: AttemptKey): ByteArray =
        "TRACKSPEED_AWARE_CONNECT:${key.attemptId}".toByteArray(Charsets.UTF_8)

    private fun readyMessage(key: AttemptKey): ByteArray =
        "TRACKSPEED_AWARE_READY:${key.attemptId}".toByteArray(Charsets.UTF_8)

    private fun selectSharedKeyCipherSuite(supported: Int): Int {
        return when {
            supported and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256 != 0 ->
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256
            supported and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128 != 0 ->
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128
            else -> Characteristics.WIFI_AWARE_CIPHER_SUITE_NONE
        }
    }

    companion object {
        private const val TAG = "WifiAwareClockSync"
        private const val SERVICE_NAME = "_sprinttimer._tcp"
        private const val ATTACH_TIMEOUT_SECONDS = 10L
        private const val EXCHANGE_TIMEOUT_SECONDS = 60L
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val NETWORK_CAPABILITIES_RETRY_DELAY_MS = 250L
        // RFC 5905 filters eight recent tuples. Keep the physically qualified
        // 256-message cohort, but move several tuples per radio service period
        // so precision does not require a 30-second sequential exchange.
        private const val PROTOCOL_MESSAGE_COUNT = 256
        private const val PROTOCOL_BATCH_SIZE = 8
        private const val MAXIMUM_PROTOCOL_MESSAGE_BYTES = 4_096
        private const val CONNECT_MESSAGE_ID = 201
        private const val PATH_READY_MESSAGE_ID = 202

        internal fun deriveAttemptKey(
            sessionId: String,
            requesterId: String,
            referenceId: String
        ): AttemptKey {
            val material = listOf(
                "trackspeed-aware-clock-v1",
                sessionId.lowercase(Locale.US),
                requesterId.lowercase(Locale.US),
                referenceId.lowercase(Locale.US)
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
            return AttemptKey(
                attemptId = digest.take(24),
                passphrase = digest.takeLast(32)
            )
        }
    }
}
