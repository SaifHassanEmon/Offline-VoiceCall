package com.example.lanvoicecaller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.lanvoicecaller.MainActivity
import com.example.lanvoicecaller.data.model.CallState
import com.example.lanvoicecaller.data.model.ChatMessage
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.data.prefs.AppPreferences
import com.example.lanvoicecaller.network.discovery.LanDiscoveryManager
import com.example.lanvoicecaller.network.signaling.SignalingClient
import com.example.lanvoicecaller.network.signaling.SignalingMessage
import com.example.lanvoicecaller.network.signaling.SignalingServer
import com.example.lanvoicecaller.network.webrtc.WebRtcManager
import com.example.lanvoicecaller.network.wifidirect.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val CHANNEL_ID = "lan_call_channel"
private const val NOTIF_ID = 1
private const val SIGNALING_PORT = 8888

/**
 * Foreground service keeping all network components alive.
 *
 * Supports BOTH Wi-Fi Direct (direct P2P with no router) AND Local LAN/Hotspot (NSD + UDP broadcast).
 */
class LanCallService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LanCallService = this@LanCallService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var prefs: AppPreferences
        private set

    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var lanDiscoveryManager: LanDiscoveryManager
    private lateinit var signalingServer: SignalingServer
    private lateinit var webRtcManager: WebRtcManager
    private var signalingClient: SignalingClient? = null

    // Map of MAC address -> WifiP2pDevice
    private val wifiDeviceMap = mutableMapOf<String, WifiP2pDevice>()
    private val resolvedPeers = mutableMapOf<String, PeerDevice>()

    private var isP2pConnected = false
    private var amGroupOwner = false
    private var pendingCallPeer: PeerDevice? = null

    // ── Public state ──────────────────────────────────────────────────────────

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var activePeer: PeerDevice? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        createNotificationChannel()
        startForegroundSafely("Searching for devices…")

        // Wi-Fi Direct setup
        wifiDirectManager = WifiDirectManager(this)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wifiDirectManager.broadcastReceiver, wifiDirectManager.intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(wifiDirectManager.broadcastReceiver, wifiDirectManager.intentFilter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Local LAN / Hotspot discovery setup
        lanDiscoveryManager = LanDiscoveryManager(
            context = this,
            deviceId = prefs.deviceId,
            deviceName = prefs.displayName,
            signalingPort = SIGNALING_PORT
        )

        // Combine Wi-Fi Direct peers & LAN/Hotspot discovered peers
        combine(wifiDirectManager.discoveredDevices, lanDiscoveryManager.peers) { p2pDevices, lanPeers ->
            wifiDeviceMap.clear()
            p2pDevices.forEach { wifiDeviceMap[it.deviceAddress] = it }

            val p2pList = p2pDevices.map { d ->
                resolvedPeers[d.deviceAddress] ?: PeerDevice(
                    id = d.deviceAddress,
                    name = d.deviceName,
                    ipAddress = "",
                    port = SIGNALING_PORT
                )
            }

            // Deduplicate by device ID or name
            val map = LinkedHashMap<String, PeerDevice>()
            lanPeers.forEach { map[it.id] = it }
            p2pList.forEach { if (!map.containsKey(it.id)) map[it.id] = it }
            map.values.sortedBy { it.name }
        }.onEach { _peers.value = it }.launchIn(scope)

        // Observe Wi-Fi Direct P2P connection state
        wifiDirectManager.connectionInfo.onEach { info ->
            if (info != null && !isP2pConnected) {
                isP2pConnected = true
                amGroupOwner = info.isGroupOwner
                updateNotification(if (amGroupOwner) "Ready — waiting for peer…" else "Connecting to peer…")

                if (!amGroupOwner) {
                    handleClientSideConnection(info.groupOwnerIp)
                }
            } else if (info == null && isP2pConnected) {
                isP2pConnected = false
                updateNotification("Searching for devices…")
            }
        }.launchIn(scope)

        // WebRTC
        webRtcManager = WebRtcManager(this)
        runCatching { webRtcManager.init() }
        setupWebRtcCallbacks()

        // Signaling server
        runCatching { startSignalingServer() }

        // Start discovery services
        runCatching { wifiDirectManager.startDiscovery() }
        runCatching { lanDiscoveryManager.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        wifiDirectManager.stopDiscovery()
        wifiDirectManager.disconnect()
        lanDiscoveryManager.stop()
        runCatching { unregisterReceiver(wifiDirectManager.broadcastReceiver) }
        signalingServer.stop()
        webRtcManager.close()
        super.onDestroy()
    }

    fun forceRescan() {
        wifiDirectManager.startDiscovery()
    }

    // ── Client connection logic ───────────────────────────────────────────────

    private fun handleClientSideConnection(groupOwnerIp: String) {
        scope.launch {
            val client = SignalingClient()
            signalingClient = client
            val connected = client.connect(groupOwnerIp, SIGNALING_PORT) { envelope, peerIp ->
                handleIncomingMessage(envelope, peerIp)
            }

            if (!connected) {
                _callState.value = CallState.Ended("Could not reach peer")
                isP2pConnected = false
                return@launch
            }

            sendHello(client)

            pendingCallPeer?.let { peer ->
                val connectedPeer = peer.copy(ipAddress = groupOwnerIp)
                activePeer = connectedPeer
                webRtcManager.createPeerConnection()
                webRtcManager.createOffer()
                _callState.value = CallState.Calling(connectedPeer)
                pendingCallPeer = null
                updateNotification("Calling ${connectedPeer.name}…")
            }
        }
    }

    private fun sendHello(client: SignalingClient) {
        client.send(
            SignalingMessage.Envelope(
                type = SignalingMessage.TYPE_HELLO,
                senderId = prefs.deviceId,
                senderName = prefs.displayName,
                payload = SignalingMessage.json.encodeToString(
                    SignalingMessage.Hello.serializer(),
                    SignalingMessage.Hello(prefs.deviceId, prefs.displayName)
                )
            )
        )
    }

    // ── Signaling server ──────────────────────────────────────────────────────

    private fun startSignalingServer() {
        signalingServer = SignalingServer(port = SIGNALING_PORT) { envelope, peerIp, _ ->
            handleIncomingMessage(envelope, peerIp)
        }
        signalingServer.start()
    }

    private fun handleIncomingMessage(envelope: SignalingMessage.Envelope, peerIp: String) {
        scope.launch(Dispatchers.Main) {
            when (envelope.type) {

                SignalingMessage.TYPE_HELLO -> {
                    val hello = SignalingMessage.json.decodeFromString(
                        SignalingMessage.Hello.serializer(), envelope.payload
                    )
                    val resolved = PeerDevice(hello.deviceId, hello.deviceName, peerIp, SIGNALING_PORT)
                    val macKey = wifiDeviceMap.entries.firstOrNull {
                        it.value.deviceName == envelope.senderName || resolvedPeers[it.key]?.id == hello.deviceId
                    }?.key
                    macKey?.let { resolvedPeers[it] = resolved }

                    _peers.value = _peers.value.map { p ->
                        if (p.name == envelope.senderName || p.id == hello.deviceId) resolved else p
                    }
                }

                SignalingMessage.TYPE_CALL_INITIATE -> {
                    val payload = SignalingMessage.json.decodeFromString(
                        SignalingMessage.CallInitiate.serializer(), envelope.payload
                    )
                    val peer = PeerDevice(envelope.senderId, envelope.senderName, peerIp, SIGNALING_PORT)
                    activePeer = peer
                    webRtcManager.createPeerConnection()
                    webRtcManager.setRemoteOffer(payload.sdpOffer)
                    _callState.value = CallState.Incoming(peer)
                }

                SignalingMessage.TYPE_CALL_ACCEPT -> {
                    val payload = SignalingMessage.json.decodeFromString(
                        SignalingMessage.CallAccept.serializer(), envelope.payload
                    )
                    webRtcManager.setRemoteAnswer(payload.sdpAnswer)
                    val peer = activePeer ?: return@launch
                    _callState.value = CallState.Active(peer)
                    updateNotification("In call with ${peer.name}")
                }

                SignalingMessage.TYPE_CALL_REJECT -> {
                    _callState.value = CallState.Ended("Call rejected")
                }

                SignalingMessage.TYPE_CALL_END -> {
                    resetWebRtc()
                    _callState.value = CallState.Idle
                    activePeer = null
                    updateNotification("Connected — ready to call")
                }

                SignalingMessage.TYPE_ICE_CANDIDATE -> {
                    val candidate = SignalingMessage.json.decodeFromString(
                        SignalingMessage.IceCandidate.serializer(), envelope.payload
                    )
                    webRtcManager.addIceCandidate(candidate)
                }

                SignalingMessage.TYPE_CHAT -> {
                    val chatMsg = SignalingMessage.json.decodeFromString(
                        SignalingMessage.ChatMsg.serializer(), envelope.payload
                    )
                    _messages.value = _messages.value + ChatMessage(
                        id = chatMsg.msgId,
                        senderId = envelope.senderId,
                        senderName = envelope.senderName,
                        text = chatMsg.text,
                        timestampMs = chatMsg.timestampMs,
                        isFromMe = false
                    )
                }
            }
        }
    }

    // ── WebRTC callbacks ──────────────────────────────────────────────────────

    private fun setupWebRtcCallbacks() {
        webRtcManager.onLocalSdp = localSdp@{ type, sdp ->
            val peer = activePeer ?: return@localSdp
            val envelope = when (type) {
                "offer" -> SignalingMessage.Envelope(
                    type = SignalingMessage.TYPE_CALL_INITIATE,
                    senderId = prefs.deviceId,
                    senderName = prefs.displayName,
                    payload = SignalingMessage.json.encodeToString(
                        SignalingMessage.CallInitiate.serializer(),
                        SignalingMessage.CallInitiate(sdpOffer = sdp)
                    )
                )
                "answer" -> SignalingMessage.Envelope(
                    type = SignalingMessage.TYPE_CALL_ACCEPT,
                    senderId = prefs.deviceId,
                    senderName = prefs.displayName,
                    payload = SignalingMessage.json.encodeToString(
                        SignalingMessage.CallAccept.serializer(),
                        SignalingMessage.CallAccept(sdpAnswer = sdp)
                    )
                )
                else -> return@localSdp
            }
            signalingClient?.send(envelope)
        }

        webRtcManager.onIceCandidate = { candidate ->
            signalingClient?.send(
                SignalingMessage.Envelope(
                    type = SignalingMessage.TYPE_ICE_CANDIDATE,
                    senderId = prefs.deviceId,
                    senderName = prefs.displayName,
                    payload = SignalingMessage.json.encodeToString(
                        SignalingMessage.IceCandidate.serializer(), candidate
                    )
                )
            )
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun callPeer(peer: PeerDevice) {
        if (peer.ipAddress.isNotBlank()) {
            // Direct LAN/Hotspot peer with known IP address
            scope.launch {
                activePeer = peer
                ensureSignalingClient(peer.ipAddress)
                webRtcManager.createPeerConnection()
                webRtcManager.createOffer()
                _callState.value = CallState.Calling(peer)
                updateNotification("Calling ${peer.name}…")
            }
        } else if (isP2pConnected) {
            val targetIp = if (amGroupOwner) peer.ipAddress else "192.168.49.1"
            if (targetIp.isBlank()) return
            scope.launch {
                activePeer = peer.copy(ipAddress = targetIp)
                ensureSignalingClient(targetIp)
                webRtcManager.createPeerConnection()
                webRtcManager.createOffer()
                _callState.value = CallState.Calling(activePeer!!)
                updateNotification("Calling ${peer.name}…")
            }
        } else {
            // Initiate Wi-Fi Direct connection
            pendingCallPeer = peer
            val device = wifiDeviceMap[peer.id]
            if (device != null) {
                wifiDirectManager.connectTo(device)
            }
        }
    }

    fun acceptCall() {
        scope.launch {
            val peer = activePeer ?: return@launch
            val targetIp = if (peer.ipAddress.isNotBlank()) peer.ipAddress else if (amGroupOwner) peer.ipAddress else "192.168.49.1"
            ensureSignalingClient(targetIp)
            webRtcManager.createAnswer()
            _callState.value = CallState.Active(peer)
            updateNotification("In call with ${peer.name}")
        }
    }

    fun rejectCall() {
        signalingClient?.send(
            SignalingMessage.Envelope(
                type = SignalingMessage.TYPE_CALL_REJECT,
                senderId = prefs.deviceId,
                senderName = prefs.displayName
            )
        )
        _callState.value = CallState.Idle
        activePeer = null
    }

    fun endCall() {
        signalingClient?.send(
            SignalingMessage.Envelope(
                type = SignalingMessage.TYPE_CALL_END,
                senderId = prefs.deviceId,
                senderName = prefs.displayName
            )
        )
        signalingClient?.disconnect()
        signalingClient = null
        resetWebRtc()
        _callState.value = CallState.Idle
        activePeer = null
        updateNotification("Connected — ready to call")
    }

    fun setMuted(muted: Boolean) = webRtcManager.setMuted(muted)

    fun sendChatMessage(peer: PeerDevice, text: String) {
        scope.launch {
            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                senderId = prefs.deviceId,
                senderName = prefs.displayName,
                text = text,
                isFromMe = true
            )
            _messages.value = _messages.value + msg

            val targetIp = if (peer.ipAddress.isNotBlank()) peer.ipAddress else if (amGroupOwner) peer.ipAddress else "192.168.49.1"
            if (targetIp.isBlank()) return@launch

            val client = signalingClient ?: run {
                ensureSignalingClient(targetIp)
                signalingClient
            } ?: return@launch

            client.send(
                SignalingMessage.Envelope(
                    type = SignalingMessage.TYPE_CHAT,
                    senderId = prefs.deviceId,
                    senderName = prefs.displayName,
                    payload = SignalingMessage.json.encodeToString(
                        SignalingMessage.ChatMsg.serializer(),
                        SignalingMessage.ChatMsg(msg.id, text, msg.timestampMs)
                    )
                )
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun ensureSignalingClient(ip: String) {
        if (signalingClient != null) return
        val client = SignalingClient()
        signalingClient = client
        client.connect(ip, SIGNALING_PORT) { envelope, peerIp ->
            handleIncomingMessage(envelope, peerIp)
        }
        sendHello(client)
    }

    private fun resetWebRtc() {
        runCatching { webRtcManager.close() }
        webRtcManager.init()
        setupWebRtcCallbacks()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "LAN Voice Caller", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Voice call activity" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Voice Caller")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundSafely(text: String) {
        try {
            val notification = buildNotification(text)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotification(text: String) {
        startForegroundSafely(text)
    }

    companion object {
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LanCallService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
