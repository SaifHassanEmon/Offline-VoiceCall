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
import com.example.lanvoicecaller.network.signaling.SignalingClient
import com.example.lanvoicecaller.network.signaling.SignalingMessage
import com.example.lanvoicecaller.network.signaling.SignalingServer
import com.example.lanvoicecaller.network.webrtc.WebRtcManager
import com.example.lanvoicecaller.network.wifidirect.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

private const val CHANNEL_ID = "lan_call_channel"
private const val NOTIF_ID = 1
private const val SIGNALING_PORT = 8888

/**
 * Foreground service keeping all network components alive without any router or internet.
 *
 * Uses Wi-Fi Direct (WifiP2pManager) for device discovery and direct P2P connection.
 * Bind to this service from UI to access [peers], [callState], and [messages].
 *
 * Wi-Fi Direct connection flow:
 *  1. Both phones run startDiscovery() → each sees the other in [peers]
 *  2. Caller taps "Call" → callPeer() → wifiDirectManager.connectTo() fires
 *  3. Wi-Fi Direct group forms: one phone = Group Owner (GO, IP 192.168.49.1), other = Client
 *  4. Client connects TCP to 192.168.49.1:8888, sends HELLO + CALL_INITIATE
 *  5. GO's SignalingServer receives the call → shows IncomingCallScreen
 *  6. WebRTC audio P2P begins over the Wi-Fi Direct virtual LAN
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
    private lateinit var signalingServer: SignalingServer
    private lateinit var webRtcManager: WebRtcManager
    private var signalingClient: SignalingClient? = null

    // ── Wi-Fi Direct state ────────────────────────────────────────────────────

    // Map of MAC address → WifiP2pDevice (for triggering connections)
    private val wifiDeviceMap = mutableMapOf<String, WifiP2pDevice>()

    // Map of MAC address → resolved PeerDevice (after HELLO exchange)
    private val resolvedPeers = mutableMapOf<String, PeerDevice>()

    private var isP2pConnected = false
    private var amGroupOwner = false

    // Stores a pending call/chat target set before Wi-Fi Direct finishes connecting
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
        startForeground(NOTIF_ID, buildNotification("Searching for devices…"))

        // Wi-Fi Direct
        wifiDirectManager = WifiDirectManager(this)
        registerReceiver(wifiDirectManager.broadcastReceiver, wifiDirectManager.intentFilter)

        // Observe discovered devices → update peer list
        wifiDirectManager.discoveredDevices.onEach { devices ->
            wifiDeviceMap.clear()
            devices.forEach { wifiDeviceMap[it.deviceAddress] = it }
            // Merge discovered devices with any already-resolved peers
            val merged = devices.map { d ->
                resolvedPeers[d.deviceAddress] ?: PeerDevice(
                    id = d.deviceAddress,
                    name = d.deviceName,
                    ipAddress = "",
                    port = SIGNALING_PORT
                )
            }
            _peers.value = merged
        }.launchIn(scope)

        // Observe Wi-Fi Direct connection state
        wifiDirectManager.connectionInfo.onEach { info ->
            if (info != null && !isP2pConnected) {
                isP2pConnected = true
                amGroupOwner = info.isGroupOwner
                updateNotification(if (amGroupOwner) "Ready — waiting for peer…" else "Connecting to peer…")

                if (!amGroupOwner) {
                    // We're the client: connect to Group Owner at 192.168.49.1
                    handleClientSideConnection(info.groupOwnerIp)
                }
                // Group Owner: SignalingServer handles the incoming TCP connection
            } else if (info == null && isP2pConnected) {
                isP2pConnected = false
                updateNotification("Searching for devices…")
            }
        }.launchIn(scope)

        // WebRTC
        webRtcManager = WebRtcManager(this)
        webRtcManager.init()
        setupWebRtcCallbacks()

        // Signaling server
        startSignalingServer()

        // Start peer discovery
        wifiDirectManager.startDiscovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        wifiDirectManager.stopDiscovery()
        wifiDirectManager.disconnect()
        runCatching { unregisterReceiver(wifiDirectManager.broadcastReceiver) }
        signalingServer.stop()
        webRtcManager.close()
        super.onDestroy()
    }

    // ── Wi-Fi Direct client-side connection ───────────────────────────────────

    /**
     * Called when we are the Wi-Fi Direct client (non-Group-Owner).
     * Connects to the Group Owner's TCP server and sends HELLO + any pending action.
     */
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

            // Announce ourselves — GO now knows our app name + our IP (from socket)
            sendHello(client)

            // Fire any pending call
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
                    // Peer announced themselves → resolve their app name + IP
                    val hello = SignalingMessage.json.decodeFromString(
                        SignalingMessage.Hello.serializer(), envelope.payload
                    )
                    val resolved = PeerDevice(hello.deviceId, hello.deviceName, peerIp, SIGNALING_PORT)
                    // Update resolvedPeers map (match by any known MAC or by sender name)
                    val macKey = wifiDeviceMap.entries.firstOrNull {
                        it.value.deviceName == envelope.senderName || resolvedPeers[it.key]?.id == hello.deviceId
                    }?.key
                    macKey?.let { resolvedPeers[it] = resolved }

                    // Update live peer list
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

    // ── Public actions (called from ViewModels) ────────────────────────────────

    /**
     * Call [peer]. If Wi-Fi Direct is not connected yet, initiates the P2P connection
     * and stores [peer] as pending — the call fires automatically once connected.
     */
    fun callPeer(peer: PeerDevice) {
        if (isP2pConnected) {
            // Already connected — dial directly
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
            // Trigger Wi-Fi Direct connection first; call fires in handleClientSideConnection
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
            // If GO (server side), we need a client to reply to
            val targetIp = if (amGroupOwner) peer.ipAddress else "192.168.49.1"
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

            val client = signalingClient ?: run {
                val targetIp = if (amGroupOwner) peer.ipAddress else "192.168.49.1"
                if (targetIp.isBlank()) return@launch
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
        ).apply { description = "Wi-Fi Direct call activity" }
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

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, LanCallService::class.java))
        }
    }
}
