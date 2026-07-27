package com.example.lanvoicecaller.data.model

import com.example.lanvoicecaller.data.model.PeerDevice

/**
 * Sealed class representing every possible call state in the app.
 */
sealed class CallState {
    /** No active call. */
    object Idle : CallState()

    /** We are calling [peer] and waiting for them to answer. */
    data class Calling(val peer: PeerDevice) : CallState()

    /** [peer] is calling us — waiting for our accept/reject. */
    data class Incoming(val peer: PeerDevice) : CallState()

    /** Call is active with [peer]. [startedAtMs] is the epoch ms when the call was accepted. */
    data class Active(val peer: PeerDevice, val startedAtMs: Long = System.currentTimeMillis()) : CallState()

    /** Call ended (normally or by error). */
    data class Ended(val reason: String = "") : CallState()
}
