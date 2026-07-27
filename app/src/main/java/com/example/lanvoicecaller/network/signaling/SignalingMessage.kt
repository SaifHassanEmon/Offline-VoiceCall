package com.example.lanvoicecaller.network.signaling

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * All messages exchanged over the TCP signaling channel between peers.
 * Each message is serialized as a single-line JSON string terminated by '\n'.
 */
object SignalingMessage {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Envelope ────────────────────────────────────────────────────────────

    @Serializable
    data class Envelope(
        val type: String,
        val senderId: String,
        val senderName: String,
        val payload: String = ""   // JSON-encoded inner payload
    )

    // ── Inner payloads ──────────────────────────────────────────────────────

    @Serializable
    data class CallInitiate(val sdpOffer: String)

    @Serializable
    data class CallAccept(val sdpAnswer: String)

    @Serializable
    data class IceCandidate(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int)

    @Serializable
    data class ChatMsg(val msgId: String, val text: String, val timestampMs: Long)

    /** Sent immediately after TCP connection to announce display name + device ID. */
    @Serializable
    data class Hello(val deviceId: String, val deviceName: String)

    // ── Type constants ──────────────────────────────────────────────────────

    const val TYPE_CALL_INITIATE  = "CALL_INITIATE"
    const val TYPE_CALL_ACCEPT    = "CALL_ACCEPT"
    const val TYPE_CALL_REJECT    = "CALL_REJECT"
    const val TYPE_CALL_END       = "CALL_END"
    const val TYPE_ICE_CANDIDATE  = "ICE_CANDIDATE"
    const val TYPE_CHAT           = "CHAT"
    const val TYPE_PING           = "PING"
    const val TYPE_HELLO          = "HELLO"

    // ── Helpers ─────────────────────────────────────────────────────────────

    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope) + "\n"

    fun decode(line: String): Envelope? = runCatching {
        json.decodeFromString(Envelope.serializer(), line.trim())
    }.getOrNull()
}
