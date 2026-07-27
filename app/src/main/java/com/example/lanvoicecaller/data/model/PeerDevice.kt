package com.example.lanvoicecaller.data.model

/**
 * Represents a peer device discovered on the local network.
 */
data class PeerDevice(
    val id: String,          // Unique device UUID
    val name: String,        // Display name chosen by user
    val ipAddress: String,   // LAN IP address (e.g. 192.168.1.105)
    val port: Int,           // TCP signaling port (default 8888)
    val discoveredAtMs: Long = System.currentTimeMillis()
)
