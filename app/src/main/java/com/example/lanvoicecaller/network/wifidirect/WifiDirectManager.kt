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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "WifiDirect"

/**
 * Manages Wi-Fi Direct (WifiP2pManager) peer discovery and group formation.
 *
 * NO Wi-Fi router or internet required — two phones connect directly.
 *
 * Flow:
 *   1. [startDiscovery] → [discoveredDevices] fills with nearby phones
 *   2. [connectTo] a device → Wi-Fi Direct P2P group forms automatically
 *   3. [connectionInfo] fires with group owner IP + role
 *   4. Non-owner (client) connects TCP to GO at 192.168.49.1:8888
 *   5. Owner (server) listens on SignalingServer for incoming TCP from client
 */
class WifiDirectManager(context: Context) {

    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    // ── Public state ─────────────────────────────────────────────────────────

    private val _discoveredDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WifiP2pDevice>> = _discoveredDevices.asStateFlow()

    data class P2pConnectionInfo(val isGroupOwner: Boolean, val groupOwnerIp: String)

    private val _connectionInfo = MutableStateFlow<P2pConnectionInfo?>(null)
    val connectionInfo: StateFlow<P2pConnectionInfo?> = _connectionInfo.asStateFlow()

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

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peerList ->
                        _discoveredDevices.value = peerList.deviceList.toList()
                        Log.d(TAG, "Peers found: ${peerList.deviceList.size}")
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (networkInfo?.isConnected == true) {
                        manager.requestConnectionInfo(channel) { info ->
                            val ip = info.groupOwnerAddress?.hostAddress
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

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d(TAG, "P2P state: $state")
                }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun startDiscovery() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Discovery started") }
            override fun onFailure(r: Int) { Log.w(TAG, "Discovery failed: $r") }
        })
    }

    fun stopDiscovery() {
        manager.stopPeerDiscovery(channel, null)
    }

    /**
     * Initiate a Wi-Fi Direct connection to [device].
     * Uses PBC (push-button configuration) so no PIN is needed.
     */
    fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Connecting to ${device.deviceName}…") }
            override fun onFailure(r: Int) { Log.w(TAG, "Connect failed: $r") }
        })
    }

    fun disconnect() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _connectionInfo.value = null
                _discoveredDevices.value = emptyList()
            }
            override fun onFailure(r: Int) { Log.w(TAG, "Disconnect failed: $r") }
        })
    }
}
