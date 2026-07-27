package com.example.lanvoicecaller.ui.chat

import androidx.lifecycle.ViewModel
import com.example.lanvoicecaller.data.model.ChatMessage
import com.example.lanvoicecaller.data.model.PeerDevice
import com.example.lanvoicecaller.service.LanCallService
import kotlinx.coroutines.flow.StateFlow

class ChatViewModel(private val service: LanCallService) : ViewModel() {
    val messages: StateFlow<List<ChatMessage>> = service.messages

    fun sendMessage(peer: PeerDevice, text: String) = service.sendChatMessage(peer, text)

    fun startCall(peer: PeerDevice) = service.callPeer(peer)
}
