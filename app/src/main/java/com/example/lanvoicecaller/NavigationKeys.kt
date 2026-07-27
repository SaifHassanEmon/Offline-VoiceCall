package com.example.lanvoicecaller

import androidx.navigation3.runtime.NavKey
import com.example.lanvoicecaller.data.model.PeerDevice
import kotlinx.serialization.Serializable

@Serializable data object Setup : NavKey
@Serializable data object Contacts : NavKey
@Serializable data class Chat(val peerId: String, val peerName: String, val peerIp: String, val peerPort: Int) : NavKey
@Serializable data object CallScreen : NavKey
