package com.example.lanvoicecaller.network.discovery

import android.content.Context
import android.net.wifi.WifiManager
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

    // MulticastLock required to receive mDNS multicast packets on Android
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("LanCallMulticastLock").apply {
        setReferenceCounted(true)
    }

    fun start() {
        // Acquire multicast lock — required for NSD to work
        if (!multicastLock.isHeld) multicastLock.acquire()

        nsdDiscovery.registerService()
        nsdDiscovery.startDiscovery()
        udpDiscovery.start()

        // Merge both peer maps into a single deduplicated list
        combine(nsdDiscovery.peers, udpDiscovery.peers) { nsdPeers, udpPeers ->
            val merged = LinkedHashMap<String, PeerDevice>()
            udpPeers.forEach { merged[it.key] = it.value }
            nsdPeers.forEach { merged[it.key] = it.value }  // NSD data preferred (has resolved host)
            merged.values.sortedBy { it.name }
        }.onEach { _peers.value = it }.launchIn(scope)
    }

    fun stop() {
        nsdDiscovery.stop()
        udpDiscovery.stop()
        if (multicastLock.isHeld) multicastLock.release()
    }
}
