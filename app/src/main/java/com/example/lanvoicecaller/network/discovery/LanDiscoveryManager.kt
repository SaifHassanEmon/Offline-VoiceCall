package com.example.lanvoicecaller.network.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.lanvoicecaller.data.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val TAG = "LanDiscoveryManager"

/**
 * Orchestrates peer discovery using both NsdManager (mDNS) and UDP broadcast.
 * Merges results from both into a single [peers] StateFlow.
 */
class LanDiscoveryManager(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val signalingPort: Int = 8888
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val nsdDiscovery = NsdDiscovery(context, deviceId, deviceName, signalingPort)
    private val udpDiscovery = UdpBroadcastDiscovery(deviceId, deviceName, signalingPort)

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val wifiManager: WifiManager? =
        runCatching { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()

    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        wifiManager?.createMulticastLock("LanCallMulticastLock")?.apply {
            setReferenceCounted(true)
        }
    }.getOrNull()

    fun start() {
        runCatching {
            multicastLock?.let { if (!it.isHeld) it.acquire() }
        }.onFailure { Log.w(TAG, "Could not acquire MulticastLock", it) }

        nsdDiscovery.registerService()
        nsdDiscovery.startDiscovery()
        udpDiscovery.start()

        combine(nsdDiscovery.peers, udpDiscovery.peers) { nsdPeers, udpPeers ->
            val merged = LinkedHashMap<String, PeerDevice>()
            udpPeers.forEach { merged[it.key] = it.value }
            nsdPeers.forEach { merged[it.key] = it.value }
            merged.values.sortedBy { it.name }
        }.onEach { _peers.value = it }.launchIn(scope)
    }

    fun stop() {
        nsdDiscovery.stop()
        udpDiscovery.stop()
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
    }
}
