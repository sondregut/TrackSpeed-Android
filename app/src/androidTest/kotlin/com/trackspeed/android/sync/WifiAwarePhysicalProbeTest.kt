package com.trackspeed.android.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.DiscoverySessionCallback
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingMessageCodec
import com.trackspeed.android.protocol.TimingPayload
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Explicit physical-radio probes for TrackSpeed's Wi-Fi Aware service name.
 *
 * These tests are intentionally kept under androidTest and require a named
 * method invocation. They do not enable Wi-Fi Aware in the release app. Use
 * `publishSecureAndroidService` with another Android subscriber, or keep an
 * iOS host's Wi-Fi Aware pairing surface open while running
 * `discoverIosPublisher`.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class WifiAwarePhysicalProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val handler = Handler(Looper.getMainLooper())
    private val awareSession = AtomicReference<WifiAwareSession?>()
    private val publishSession = AtomicReference<PublishDiscoverySession?>()
    private val subscribeSession = AtomicReference<SubscribeDiscoverySession?>()
    private val networkCallbacks = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val dataSocket = AtomicReference<Socket?>()
    private val ioExecutor = Executors.newCachedThreadPool()

    private lateinit var manager: WifiAwareManager
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setUp() {
        assumeTrue(
            "Device does not declare android.hardware.wifi.aware",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        )
        assertTrue(
            "NEARBY_WIFI_DEVICES was not granted to the debug app",
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        )
        manager = requireNotNull(context.getSystemService(WifiAwareManager::class.java)) {
            "WifiAwareManager is unavailable"
        }
        connectivityManager = requireNotNull(
            context.getSystemService(ConnectivityManager::class.java)
        ) { "ConnectivityManager is unavailable" }
        assertTrue("Wi-Fi Aware is not currently available", manager.isAvailable)

        val characteristics = requireNotNull(manager.characteristics) {
            "Wi-Fi Aware characteristics are unavailable"
        }
        Log.i(
            TAG,
            "[AWARE_CAPABILITY] sdk=${Build.VERSION.SDK_INT} " +
                "dataCipherSuites=${characteristics.supportedCipherSuites} " +
                "pairingSupported=${characteristics.isAwarePairingSupported} " +
                "pairingCipherSuites=${characteristics.supportedPairingCipherSuites}"
        )
    }

    @After
    fun tearDown() {
        dataSocket.getAndSet(null)?.close()
        serverSocket.getAndSet(null)?.close()
        networkCallbacks.forEach { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallbacks.clear()
        subscribeSession.getAndSet(null)?.close()
        publishSession.getAndSet(null)?.close()
        awareSession.getAndSet(null)?.close()
        ioExecutor.shutdownNow()
    }

    @Test
    fun publishSecureAndroidService() {
        val characteristics = requireNotNull(manager.characteristics) {
            "Wi-Fi Aware characteristics are unavailable"
        }
        val cipherSuite = selectSharedKeyCipherSuite(characteristics.supportedCipherSuites)
        assertTrue(
            "Device has Wi-Fi Aware discovery but no app-key secure data-path cipher",
            cipherSuite != Characteristics.WIFI_AWARE_CIPHER_SUITE_NONE
        )

        val attached = CountDownLatch(1)
        val published = CountDownLatch(1)
        val failure = AtomicReference<String?>()

        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession.set(session)
                attached.countDown()

                val security = WifiAwareDataPathSecurityConfig.Builder(cipherSuite)
                    .setPskPassphrase(PROBE_PASSPHRASE)
                    .build()
                val config = PublishConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setPublishType(PublishConfig.PUBLISH_TYPE_SOLICITED)
                    .setDataPathSecurityConfig(security)
                    .build()

                session.publish(config, object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        publishSession.set(session)
                        Log.i(
                            TAG,
                            "[AWARE_PUBLISH] state=started service=$SERVICE_NAME " +
                                "cipher=$cipherSuite secure=true"
                        )
                        published.countDown()
                    }

                    override fun onSessionConfigFailed() {
                        failure.set("publish_config_failed")
                        published.countDown()
                    }

                    override fun onSessionTerminated() {
                        failure.compareAndSet(null, "publish_session_terminated")
                        published.countDown()
                    }
                }, handler)
            }

            override fun onAttachFailed() {
                failure.set("attach_failed")
                attached.countDown()
                published.countDown()
            }
        }, handler)

        assertTrue("Wi-Fi Aware attach timed out: ${failure.get()}", attached.await(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("Secure publisher did not start: ${failure.get()}", published.await(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("Secure publisher failed: ${failure.get()}", failure.get() == null)

        val holdSeconds = probeHoldSeconds()
        Log.i(TAG, "[AWARE_PUBLISH] state=holding seconds=$holdSeconds")
        Thread.sleep(TimeUnit.SECONDS.toMillis(holdSeconds))
    }

    @Test
    fun discoverIosPublisher() {
        val attached = CountDownLatch(1)
        val subscribed = CountDownLatch(1)
        val discovered = CountDownLatch(1)
        val failure = AtomicReference<String?>()

        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession.set(session)
                attached.countDown()

                val config = SubscribeConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
                    .build()
                session.subscribe(config, object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        subscribeSession.set(session)
                        Log.i(TAG, "[AWARE_SUBSCRIBE] state=started service=$SERVICE_NAME")
                        subscribed.countDown()
                    }

                    override fun onServiceDiscovered(info: ServiceDiscoveryInfo) {
                        Log.i(
                            TAG,
                            "[AWARE_DISCOVERY] state=found service=$SERVICE_NAME " +
                                "ssiBytes=${info.serviceSpecificInfo?.size ?: 0} " +
                                "scidBytes=${info.scid?.size ?: 0}"
                        )
                        discovered.countDown()
                    }

                    override fun onSessionConfigFailed() {
                        failure.set("subscribe_config_failed")
                        subscribed.countDown()
                        discovered.countDown()
                    }

                    override fun onSessionTerminated() {
                        failure.compareAndSet(null, "subscribe_session_terminated")
                        subscribed.countDown()
                        discovered.countDown()
                    }
                }, handler)
            }

            override fun onAttachFailed() {
                failure.set("attach_failed")
                attached.countDown()
                subscribed.countDown()
                discovered.countDown()
            }
        }, handler)

        assertTrue("Wi-Fi Aware attach timed out: ${failure.get()}", attached.await(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("Subscriber did not start: ${failure.get()}", subscribed.await(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue("Subscriber failed: ${failure.get()}", failure.get() == null)

        val holdSeconds = probeHoldSeconds()
        Log.i(TAG, "[AWARE_SUBSCRIBE] state=searching seconds=$holdSeconds")
        assertTrue(
            "No iOS $SERVICE_NAME publisher was discovered in ${holdSeconds}s",
            discovered.await(holdSeconds, TimeUnit.SECONDS)
        )
    }

    /**
     * Publisher half of the two-device secured NDP/TCP probe. Start this test
     * first, then run [joinSecureAndroidDataPath] on the second Android phone.
     */
    @Test
    fun hostSecureAndroidDataPath() {
        val published = CountDownLatch(1)
        val exchangeComplete = CountDownLatch(1)
        val requestStarted = AtomicBoolean(false)
        val failure = AtomicReference<String?>()

        attachAndPublish(
            published = published,
            failure = failure,
            onMessage = { session, peer, message ->
                if (!message.contentEquals(CONNECT_REQUEST) ||
                    !requestStarted.compareAndSet(false, true)
                ) {
                    return@attachAndPublish
                }

                val listener = ServerSocket(0).apply {
                    reuseAddress = true
                    soTimeout = DATA_PATH_TIMEOUT_SECONDS.toInt() * 1_000
                }
                serverSocket.set(listener)
                val callback = requestPublisherDataPath(
                    session = session,
                    peer = peer,
                    port = listener.localPort,
                    failure = failure
                )
                networkCallbacks += callback
                session.sendMessage(peer, PATH_READY_MESSAGE_ID, PATH_READY)
                Log.i(
                    TAG,
                    "[AWARE_NDP_HOST] state=requested port=${listener.localPort} secure=true"
                )

                ioExecutor.execute {
                    runCatching {
                        val socket = listener.accept().apply {
                            tcpNoDelay = true
                            soTimeout = DATA_PATH_TIMEOUT_SECONDS.toInt() * 1_000
                        }
                        dataSocket.set(socket)
                        serveClockProtocol(socket)
                    }.onFailure { throwable ->
                        failure.compareAndSet(
                            null,
                            "host_socket_${throwable::class.java.simpleName}:${throwable.message}"
                        )
                    }
                    exchangeComplete.countDown()
                }
            }
        )

        assertTrue(
            "Secure publisher did not start: ${failure.get()}",
            published.await(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        assertTrue(
            "Secure NDP/TCP exchange timed out: ${failure.get()}",
            exchangeComplete.await(DATA_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        assertTrue("Secure NDP/TCP host failed: ${failure.get()}", failure.get() == null)
    }

    /** Subscriber half of [hostSecureAndroidDataPath]. */
    @Test
    fun joinSecureAndroidDataPath() {
        val subscribed = CountDownLatch(1)
        val exchangeComplete = CountDownLatch(1)
        val pathRequested = AtomicBoolean(false)
        val publisherPeer = AtomicReference<PeerHandle?>()
        val result = AtomicReference<ClockSyncCalculator.SyncResult?>()
        val failure = AtomicReference<String?>()

        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession.set(session)
                val config = SubscribeConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
                    .build()
                session.subscribe(config, object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        subscribeSession.set(session)
                        subscribed.countDown()
                        Log.i(TAG, "[AWARE_NDP_JOIN] state=subscribed service=$SERVICE_NAME")
                    }

                    override fun onServiceDiscovered(info: ServiceDiscoveryInfo) {
                        if (!publisherPeer.compareAndSet(null, info.peerHandle)) return
                        requireNotNull(subscribeSession.get()).sendMessage(
                            info.peerHandle,
                            CONNECT_MESSAGE_ID,
                            CONNECT_REQUEST
                        )
                        Log.i(TAG, "[AWARE_NDP_JOIN] state=discovered connectMessage=sent")
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        if (!message.contentEquals(PATH_READY) ||
                            !pathRequested.compareAndSet(false, true)
                        ) {
                            return
                        }
                        requestSubscriberDataPath(
                            session = requireNotNull(subscribeSession.get()),
                            peer = peerHandle,
                            result = result,
                            failure = failure,
                            exchangeComplete = exchangeComplete
                        ).also(networkCallbacks::add)
                    }

                    override fun onSessionConfigFailed() {
                        failure.set("subscribe_config_failed")
                        subscribed.countDown()
                        exchangeComplete.countDown()
                    }

                    override fun onSessionTerminated() {
                        failure.compareAndSet(null, "subscribe_session_terminated")
                        subscribed.countDown()
                        exchangeComplete.countDown()
                    }
                }, handler)
            }

            override fun onAttachFailed() {
                failure.set("attach_failed")
                subscribed.countDown()
                exchangeComplete.countDown()
            }
        }, handler)

        assertTrue(
            "Secure subscriber did not start: ${failure.get()}",
            subscribed.await(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        assertTrue(
            "Secure NDP/TCP exchange timed out: ${failure.get()}",
            exchangeComplete.await(DATA_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        assertTrue("Secure NDP/TCP join failed: ${failure.get()}", failure.get() == null)
        val sync = result.get()
        assertNotNull("Clock sync produced no result", sync)
        assertTrue(
            "Clock uncertainty ${sync?.uncertaintyMs}ms did not pass the setup gate",
            requireNotNull(sync).isSetupAcceptable()
        )
    }

    /** Host half using the production Android clock-lane implementation. */
    @Test
    fun hostProductionClockLane() {
        val service = WifiAwareClockSyncService(context)
        val completed = CountDownLatch(1)
        try {
            assertTrue(
                "Production Wi-Fi Aware reference was unavailable",
                service.startReference(
                    sessionId = PRODUCTION_SESSION_ID,
                    requesterId = PRODUCTION_REQUESTER_ID,
                    referenceId = PRODUCTION_REFERENCE_ID,
                    onProgress = {},
                    onComplete = { passed ->
                        if (passed) completed.countDown()
                    }
                )
            )
            assertTrue(
                "Production Wi-Fi Aware reference exchange timed out",
                completed.await(DATA_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )
        } finally {
            service.close()
        }
    }

    /** Joiner half of [hostProductionClockLane]. */
    @Test
    fun joinProductionClockLane() = runBlocking {
        val service = WifiAwareClockSyncService(context)
        try {
            val result = service.measureAsRequester(
                sessionId = PRODUCTION_SESSION_ID,
                requesterId = PRODUCTION_REQUESTER_ID,
                referenceId = PRODUCTION_REFERENCE_ID,
                onProgress = {}
            )
            assertNotNull("Production Wi-Fi Aware lane produced no result", result)
            assertTrue(
                "Production clock uncertainty ${result?.uncertaintyMs}ms missed the setup gate",
                requireNotNull(result).isSetupAcceptable()
            )
        } finally {
            service.close()
        }
    }

    /** Full app host: BLE bootstrap/capability exchange followed by Aware sync. */
    @Test
    fun hostProductionHybridConnection() = runBlocking {
        val syncManager = productionSyncManager()
        syncManager.stop()
        try {
            syncManager.startAsServer()
            val terminal = withTimeoutOrNull(HYBRID_CONNECTION_TIMEOUT_MS) {
                syncManager.syncState
                    .filter { state ->
                        state is ClockSyncManager.SyncState.Synced ||
                            state is ClockSyncManager.SyncState.Error
                    }
                    .first()
            }
            assertTrue(
                "Production host did not reach ready: $terminal",
                terminal is ClockSyncManager.SyncState.Synced
            )
            assertEquals(2, syncManager.connectedGateCount.value)
            assertEquals(2, syncManager.syncedGateCount.value)
        } finally {
            syncManager.stop()
        }
    }

    /** Full app joiner half of [hostProductionHybridConnection]. */
    @Test
    fun joinProductionHybridConnection() = runBlocking {
        val syncManager = productionSyncManager()
        syncManager.stop()
        try {
            syncManager.startAsClient()
            val terminal = withTimeoutOrNull(HYBRID_CONNECTION_TIMEOUT_MS) {
                syncManager.syncState
                    .filter { state ->
                        state is ClockSyncManager.SyncState.Synced ||
                            state is ClockSyncManager.SyncState.Error
                    }
                    .first()
            }
            assertTrue(
                "Production joiner did not reach ready: $terminal",
                terminal is ClockSyncManager.SyncState.Synced
            )
            val synced = terminal as ClockSyncManager.SyncState.Synced
            assertTrue(
                "Production hybrid uncertainty ${synced.uncertaintyMs}ms missed setup gate",
                synced.uncertaintyMs < ClockSyncConfig.SETUP_MAX_UNCERTAINTY_MS
            )
            // SyncComplete is sent by a separate StateFlow collector. Keep the
            // physical link alive long enough for the host to admit 2/2 ready.
            delay(HYBRID_SYNC_COMPLETE_DELIVERY_MS)
        } finally {
            syncManager.stop()
        }
    }

    private fun productionSyncManager(): ClockSyncManager {
        return EntryPointAccessors.fromApplication(
            context.applicationContext,
            SyncPhysicalTestEntryPoint::class.java
        ).clockSyncManager()
    }

    private fun attachAndPublish(
        published: CountDownLatch,
        failure: AtomicReference<String?>,
        onMessage: (PublishDiscoverySession, PeerHandle, ByteArray) -> Unit
    ) {
        val characteristics = requireNotNull(manager.characteristics)
        val cipherSuite = selectSharedKeyCipherSuite(characteristics.supportedCipherSuites)
        assertTrue(
            "Device has no app-key secure data-path cipher",
            cipherSuite != Characteristics.WIFI_AWARE_CIPHER_SUITE_NONE
        )
        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession.set(session)
                val security = WifiAwareDataPathSecurityConfig.Builder(cipherSuite)
                    .setPskPassphrase(PROBE_PASSPHRASE)
                    .build()
                val config = PublishConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setPublishType(PublishConfig.PUBLISH_TYPE_SOLICITED)
                    .setDataPathSecurityConfig(security)
                    .build()
                session.publish(config, object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        publishSession.set(session)
                        published.countDown()
                        Log.i(TAG, "[AWARE_NDP_HOST] state=published secure=true")
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        onMessage(requireNotNull(publishSession.get()), peerHandle, message)
                    }

                    override fun onSessionConfigFailed() {
                        failure.set("publish_config_failed")
                        published.countDown()
                    }

                    override fun onSessionTerminated() {
                        failure.compareAndSet(null, "publish_session_terminated")
                        published.countDown()
                    }
                }, handler)
            }

            override fun onAttachFailed() {
                failure.set("attach_failed")
                published.countDown()
            }
        }, handler)
    }

    private fun requestPublisherDataPath(
        session: PublishDiscoverySession,
        peer: PeerHandle,
        port: Int,
        failure: AtomicReference<String?>
    ): ConnectivityManager.NetworkCallback {
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peer)
            .setPskPassphrase(PROBE_PASSPHRASE)
            .setPort(port)
            .build()
        val request = awareNetworkRequest(specifier)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "[AWARE_NDP_HOST] state=available network=$network")
            }

            override fun onUnavailable() {
                failure.compareAndSet(null, "host_network_unavailable")
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "[AWARE_NDP_HOST] state=lost network=$network")
            }
        }
        connectivityManager.requestNetwork(request, callback)
        return callback
    }

    private fun requestSubscriberDataPath(
        session: SubscribeDiscoverySession,
        peer: PeerHandle,
        result: AtomicReference<ClockSyncCalculator.SyncResult?>,
        failure: AtomicReference<String?>,
        exchangeComplete: CountDownLatch
    ): ConnectivityManager.NetworkCallback {
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peer)
            .setPskPassphrase(PROBE_PASSPHRASE)
            .build()
        val request = awareNetworkRequest(specifier)
        val socketStarted = AtomicBoolean(false)
        fun maybeStartSocket(network: Network, capabilities: NetworkCapabilities?) {
            val info = capabilities?.transportInfo as? WifiAwareNetworkInfo
            val address = info?.peerIpv6Addr
            val port = info?.port ?: 0
            Log.i(
                TAG,
                "[AWARE_NDP_JOIN] state=capabilities " +
                    "network=$network address=$address port=$port"
            )
            if (address == null || port <= 0 ||
                !socketStarted.compareAndSet(false, true)
            ) {
                return
            }
            ioExecutor.execute {
                runCatching {
                    val socket = network.socketFactory.createSocket(address, port).apply {
                        tcpNoDelay = true
                        soTimeout = DATA_PATH_TIMEOUT_SECONDS.toInt() * 1_000
                    }
                    dataSocket.set(socket)
                    result.set(runClockProtocol(socket))
                }.onFailure { throwable ->
                    failure.compareAndSet(
                        null,
                        "join_socket_${throwable::class.java.simpleName}:${throwable.message}"
                    )
                }
                exchangeComplete.countDown()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "[AWARE_NDP_JOIN] state=available network=$network")
                maybeStartSocket(
                    network,
                    connectivityManager.getNetworkCapabilities(network)
                )
                handler.postDelayed(
                    {
                        maybeStartSocket(
                            network,
                            connectivityManager.getNetworkCapabilities(network)
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
                failure.compareAndSet(null, "join_network_unavailable")
                exchangeComplete.countDown()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "[AWARE_NDP_JOIN] state=lost network=$network")
            }
        }
        connectivityManager.requestNetwork(request, callback)
        Log.i(TAG, "[AWARE_NDP_JOIN] state=requested secure=true")
        return callback
    }

    private fun awareNetworkRequest(
        specifier: WifiAwareNetworkSpecifier
    ): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        .setNetworkSpecifier(specifier)
        .build()

    private fun serveClockProtocol(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        repeat(PROTOCOL_MESSAGE_COUNT) { expectedIndex ->
            val requestBytes = input.readSizedMessage()
            val t2Nanos = SystemClock.elapsedRealtimeNanos()
            val request = TimingMessageCodec.decodeFromBytes(requestBytes)
            val ping = request.payload as? TimingPayload.SyncPing
                ?: error("Expected syncPing at index $expectedIndex")
            assertEquals(expectedIndex.toLong(), request.seq)
            assertEquals("aware-$expectedIndex", ping.pingId)

            val pong = TimingMessage(
                seq = request.seq,
                senderId = "aware-reference",
                sessionId = AWARE_SESSION_ID,
                payload = TimingPayload.SyncPong(
                    pingId = ping.pingId,
                    t1Nanos = ping.t1Nanos,
                    t2Nanos = t2Nanos,
                    t3Nanos = 0L,
                    requesterId = ping.requesterId
                ),
                createdAtNanos = t2Nanos
            )
            val template = TimingMessageCodec.prepareSyncPongTimestampTemplate(pong)
            val t3Nanos = SystemClock.elapsedRealtimeNanos()
            output.writeSizedMessage(template.stamp(t3Nanos))
            output.flush()
        }
        Log.i(
            TAG,
            "[AWARE_PROTOCOL_HOST] messages=$PROTOCOL_MESSAGE_COUNT ordered=true duplicates=0"
        )
    }

    private fun runClockProtocol(socket: Socket): ClockSyncCalculator.SyncResult {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val calculator = ClockSyncCalculator(isFullSync = true)
        repeat(PROTOCOL_MESSAGE_COUNT) { index ->
            val ping = TimingMessage(
                seq = index.toLong(),
                senderId = "aware-requester",
                sessionId = AWARE_SESSION_ID,
                payload = TimingPayload.SyncPing(
                    pingId = "aware-$index",
                    t1Nanos = 0L,
                    requesterId = "aware-requester"
                ),
                createdAtNanos = SystemClock.elapsedRealtimeNanos()
            )
            val template = TimingMessageCodec.prepareSyncPingTimestampTemplate(ping)
            val t1Nanos = SystemClock.elapsedRealtimeNanos()
            output.writeSizedMessage(template.stamp(t1Nanos))
            output.flush()

            val responseBytes = input.readSizedMessage()
            val t4Nanos = SystemClock.elapsedRealtimeNanos()
            val response = TimingMessageCodec.decodeFromBytes(responseBytes)
            val pong = response.payload as? TimingPayload.SyncPong
                ?: error("Expected syncPong at index $index")
            assertEquals(index.toLong(), response.seq)
            assertEquals("aware-$index", pong.pingId)
            calculator.addSample(
                ClockSyncCalculator.SyncSample(
                    t1 = pong.t1Nanos,
                    t2 = pong.t2Nanos,
                    t3 = pong.t3Nanos,
                    t4 = t4Nanos
                )
            )
        }
        val result = requireNotNull(calculator.calculateOffset())
        Log.i(
            TAG,
            "[AWARE_PROTOCOL_JOIN] messages=$PROTOCOL_MESSAGE_COUNT ordered=true duplicates=0 " +
                "samples=${result.samplesUsed}/${result.totalSamples} " +
                "minRttMs=${"%.3f".format(result.minRttMs)} " +
                "uncertaintyMs=${"%.3f".format(result.uncertaintyMs)} " +
                "quality=${result.quality}"
        )
        return result
    }

    private fun DataInputStream.readSizedMessage(): ByteArray {
        val size = readInt()
        require(size in 1..MAXIMUM_PROTOCOL_MESSAGE_BYTES) { "Invalid message size $size" }
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeSizedMessage(message: ByteArray) {
        writeInt(message.size)
        write(message)
    }

    private fun probeHoldSeconds(): Long {
        return InstrumentationRegistry.getArguments()
            .getString(PROBE_HOLD_SECONDS_ARGUMENT)
            ?.toLongOrNull()
            ?.coerceIn(1L, 180L)
            ?: DEFAULT_HOLD_SECONDS
    }

    private fun selectSharedKeyCipherSuite(supported: Int): Int {
        return when {
            supported and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256 != 0 ->
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256
            supported and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128 != 0 ->
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128
            else -> Characteristics.WIFI_AWARE_CIPHER_SUITE_NONE
        }
    }

    private companion object {
        const val TAG = "TrackSpeedAwareProbe"
        const val SERVICE_NAME = "_sprinttimer._tcp"
        const val PROBE_PASSPHRASE = "trackspeed-aware-probe"
        const val PROBE_HOLD_SECONDS_ARGUMENT = "probeHoldSeconds"
        const val DEFAULT_HOLD_SECONDS = 45L
        const val ATTACH_TIMEOUT_SECONDS = 15L
        const val PUBLISH_TIMEOUT_SECONDS = 15L
        const val DATA_PATH_TIMEOUT_SECONDS = 90L
        const val NETWORK_CAPABILITIES_RETRY_DELAY_MS = 250L
        const val PROTOCOL_MESSAGE_COUNT = 256
        const val MAXIMUM_PROTOCOL_MESSAGE_BYTES = 4_096
        const val CONNECT_MESSAGE_ID = 101
        const val PATH_READY_MESSAGE_ID = 102
        const val AWARE_SESSION_ID = "aware-physical-probe"
        const val PRODUCTION_SESSION_ID = "aware-production-probe"
        const val PRODUCTION_REQUESTER_ID = "aware-production-requester"
        const val PRODUCTION_REFERENCE_ID = "aware-production-reference"
        const val HYBRID_CONNECTION_TIMEOUT_MS = 120_000L
        const val HYBRID_SYNC_COMPLETE_DELIVERY_MS = 2_000L
        val CONNECT_REQUEST = "TRACKSPEED_CONNECT_V1".toByteArray(Charsets.UTF_8)
        val PATH_READY = "TRACKSPEED_PATH_READY_V1".toByteArray(Charsets.UTF_8)
    }
}
