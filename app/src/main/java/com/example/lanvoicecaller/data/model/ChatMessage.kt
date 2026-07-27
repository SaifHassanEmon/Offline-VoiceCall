package com.example.lanvoicecaller.data.model

/**
 * A single text chat message exchanged between two peers.
 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isFromMe: Boolean = false
)
