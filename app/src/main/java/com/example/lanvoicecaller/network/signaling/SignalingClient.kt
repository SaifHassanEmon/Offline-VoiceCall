package com.example.lanvoicecaller.network.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * TCP client used to send signaling messages to a remote peer.
 * A new connection is opened per call / chat session.
 */
class SignalingClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Connect to [ip]:[port] and start reading incoming messages.
     * [onMessage] receives (envelope, peerIp) — peerIp is the IP we connected to.
     */
    suspend fun connect(
        ip: String,
        port: Int = 8888,
        onMessage: (SignalingMessage.Envelope, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            socket = Socket(ip, port).apply {
                soTimeout = 0  // blocking reads
            }
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            // Start reading in background coroutine
            scope.launch(Dispatchers.IO) {
                readLoop(ip, onMessage)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readLoop(peerIp: String, onMessage: (SignalingMessage.Envelope, String) -> Unit) {
        try {
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val envelope = SignalingMessage.decode(line!!) ?: continue
                onMessage(envelope, peerIp)
            }
        } catch (e: Exception) {
            // Connection closed
        }
    }

    /** Send a signaling envelope to the connected peer. */
    fun send(envelope: SignalingMessage.Envelope) {
        writer?.print(SignalingMessage.encode(envelope))
        writer?.flush()
    }

    fun disconnect() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
    }
}
