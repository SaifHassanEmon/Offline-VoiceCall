package com.example.lanvoicecaller.network.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "WifiDirect"

/**
 * Manages Wi-Fi Direct (WifiP2pManager) peer discovery and group formation.
 *
 * NO Wi-Fi router or internet required — two phones connect directly.
 */
class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null

    // ── Public state ─────────────────────────────────────────────────────────

    private val _discoveredDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WifiP2pDevice>> = _discoveredDevices.asStateFlow()

    data class P2pConnectionInfo(val isGroupOwner: Boolean, val groupOwnerIp: String)

    private val _connectionInfo = MutableStateFlow<P2pConnectionInfo?>(null)
    val connectionInfo: StateFlow<P2pConnectionInfo?> = _connectionInfo.asStateFlow()

    private val _isWifiP2pEnabled = MutableStateFlow(true)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _lastErrorCode = MutableStateFlow<Int?>(null)
    val lastErrorCode: StateFlow<Int?> = _lastErrorCode.asStateFlow()

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    _isWifiP2pEnabled.value = enabled
                    Log.d(TAG, "P2P State changed: enabled=$enabled")
                    if (enabled) startDiscovery()
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peerList ->
                        val list = peerList?.deviceList?.toList() ?: emptyList()
                        _discoveredDevices.value = list
                        Log.d(TAG, "Peers discovered: ${list.size}")
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel) { info ->
                            val ip = info?.groupOwnerAddress?.hostAddress
                            if (ip != null) {
                                val ci = P2pConnectionInfo(info.isGroupOwner, ip)
                                _connectionInfo.value = ci
                                Log.d(TAG, "P2P Connected: isGO=${ci.isGroupOwner} goIP=${ci.groupOwnerIp}")
                            }
                        }
                    } else {
                        _connectionInfo.value = null
                        Log.d(TAG, "P2P Disconnected")
                    }
                }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive) {
                triggerSingleDiscovery()
                delay(12000) // Re-trigger discovery every 12 seconds to keep scanning active
            }
        }
    }

    private fun triggerSingleDiscovery() {
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _lastErrorCode.value = null
                    Log.d(TAG, "discoverPeers success")
                }

                override fun onFailure(reasonCode: Int) {
                    _lastErrorCode.value = reasonCode
                    Log.w(TAG, "discoverPeers failure code: $reasonCode")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in discoverPeers (missing location permission or GPS off)", e)
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            manager?.stopPeerDiscovery(channel, null)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        }
    }

    fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        try {
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connecting to ${device.deviceName} (${device.deviceAddress})…")
                }
                override fun onFailure(reasonCode: Int) {
                    Log.w(TAG, "Connect failed: reason=$reasonCode")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during connectTo", e)
        }
    }

    fun disconnect() {
        try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _connectionInfo.value = null
                    _discoveredDevices.value = emptyList()
                }
                override fun onFailure(reasonCode: Int) {
                    Log.w(TAG, "Disconnect failed: $reasonCode")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error removing group", e)
        }
    }
}
