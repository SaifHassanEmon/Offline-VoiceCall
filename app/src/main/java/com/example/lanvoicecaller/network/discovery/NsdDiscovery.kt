package com.example.lanvoicecaller.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.lanvoicecaller.data.model.PeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NsdDiscovery"
private const val SERVICE_TYPE = "_lanvoicecall._tcp."

/**
 * Uses Android's NsdManager (mDNS / DNS-SD) to:
 *  1. Register this device's presence on the local network.
 *  2. Discover other devices running the same service type.
 */
class NsdDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val signalingPort: Int = 8888
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    val peers: StateFlow<Map<String, PeerDevice>> = _peers.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // ── Registration ────────────────────────────────────────────────────────

    fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$deviceId|$deviceName"
            serviceType = SERVICE_TYPE
            port = signalingPort
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered as: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    // ── Discovery ───────────────────────────────────────────────────────────

    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "Discovery started")
            }
            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "Discovery stopped")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains(SERVICE_TYPE.trimEnd('.'))) {
                    resolveService(info)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // Parse deviceId from service name "deviceId|deviceName"
                val parts = info.serviceName.split("|")
                if (parts.isNotEmpty()) {
                    val lostId = parts[0]
                    _peers.value = _peers.value.filterKeys { it != lostId }
                }
            }
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(info: NsdServiceInfo) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val parts = info.serviceName.split("|")
                if (parts.size < 2) return
                val peerId = parts[0]
                val peerName = parts[1]
                if (peerId == deviceId) return  // ignore self

                val ip = info.host?.hostAddress ?: return
                val peer = PeerDevice(
                    id = peerId,
                    name = peerName,
                    ipAddress = ip,
                    port = info.port
                )
                _peers.value = _peers.value + (peerId to peer)
                Log.d(TAG, "Discovered peer: $peerName @ $ip:${info.port}")
            }
        })
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    fun stop() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
    }
}
