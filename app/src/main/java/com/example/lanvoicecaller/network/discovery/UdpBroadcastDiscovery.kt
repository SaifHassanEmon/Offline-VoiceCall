package com.example.lanvoicecaller.network.discovery

import android.util.Log
import com.example.lanvoicecaller.data.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "UdpBroadcast"
private const val BROADCAST_PORT = 45454
private const val PING_INTERVAL_MS = 5000L
private const val PEER_TIMEOUT_MS = 15000L

/**
 * UDP broadcast fallback discovery — fires pings to 255.255.255.255 and listens for pongs.
 * Used when the router blocks mDNS multicast (AP isolation, etc.).
 */
class UdpBroadcastDiscovery(
    private val deviceId: String,
    private val deviceName: String,
    private val signalingPort: Int = 8888
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _peers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    val peers: StateFlow<Map<String, PeerDevice>> = _peers.asStateFlow()

    private val lastSeen = mutableMapOf<String, Long>()

    @Serializable
    private data class Beacon(
        val type: String,          // "PING" or "PONG"
        val deviceId: String,
        val deviceName: String,
        val port: Int
    )

    private var listenerSocket: DatagramSocket? = null

    fun start() {
        startListener()
        startPinger()
        startExpiry()
    }

    private fun startListener() {
        scope.launch {
            try {
                listenerSocket = DatagramSocket(BROADCAST_PORT).apply {
                    broadcast = true
                    soTimeout = 500
                }
                val buf = ByteArray(1024)
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        listenerSocket?.receive(packet)
                        val text = String(packet.data, 0, packet.length).trim()
                        val beacon = runCatching { json.decodeFromString(Beacon.serializer(), text) }.getOrNull() ?: continue
                        if (beacon.deviceId == deviceId) continue  // ignore self
                        val ip = packet.address.hostAddress ?: continue

                        // Add/update peer
                        val peer = PeerDevice(beacon.deviceId, beacon.deviceName, ip, beacon.port)
                        lastSeen[beacon.deviceId] = System.currentTimeMillis()
                        _peers.value = _peers.value + (beacon.deviceId to peer)

                        // If we received a PING, respond with PONG directly to sender
                        if (beacon.type == "PING") {
                            sendUnicast(ip, beacon)
                        }
                    } catch (e: SocketTimeoutException) {
                        // No packet received in this window — continue
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listener stopped: ${e.message}")
            }
        }
    }

    private fun startPinger() {
        scope.launch {
            val socket = DatagramSocket().apply { broadcast = true }
            val beacon = Beacon("PING", deviceId, deviceName, signalingPort)
            val text = json.encodeToString(Beacon.serializer(), beacon)
            val data = text.toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            while (true) {
                try {
                    val packet = DatagramPacket(data, data.size, broadcastAddr, BROADCAST_PORT)
                    socket.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Ping failed: ${e.message}")
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun sendUnicast(ip: String, originalBeacon: Beacon) {
        scope.launch {
            try {
                val pong = Beacon("PONG", deviceId, deviceName, signalingPort)
                val text = json.encodeToString(Beacon.serializer(), pong)
                val data = text.toByteArray()
                val socket = DatagramSocket()
                val packet = DatagramPacket(data, data.size, InetAddress.getByName(ip), BROADCAST_PORT)
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Pong failed: ${e.message}")
            }
        }
    }

    private fun startExpiry() {
        scope.launch {
            while (true) {
                delay(5000)
                val now = System.currentTimeMillis()
                val expired = lastSeen.filterValues { now - it > PEER_TIMEOUT_MS }.keys
                if (expired.isNotEmpty()) {
                    lastSeen -= expired
                    _peers.value = _peers.value.filterKeys { it !in expired }
                }
            }
        }
    }

    fun stop() {
        runCatching { listenerSocket?.close() }
    }
}
