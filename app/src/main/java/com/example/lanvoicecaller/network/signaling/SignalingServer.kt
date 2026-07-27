package com.example.lanvoicecaller.network.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP server that listens on [port] for incoming peer connections.
 * Each accepted connection spawns a coroutine that reads newline-delimited JSON envelopes.
 *
 * [onMessage] receives: (envelope, peerIp, replyFn)
 *   - peerIp is the remote address of the connected device — critical for Wi-Fi Direct
 *     where the Group Owner doesn't know the client's IP until they connect.
 */
class SignalingServer(
    private val port: Int = 8888,
    private val onMessage: (
        envelope: SignalingMessage.Envelope,
        peerIp: String,
        reply: (String) -> Unit
    ) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (true) {
                    val client: Socket = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                // Server stopped
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val peerIp = socket.inetAddress.hostAddress ?: "unknown"
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val envelope = SignalingMessage.decode(line!!) ?: continue
                    onMessage(envelope, peerIp) { response -> writer.println(response) }
                }
            } catch (e: Exception) {
                // Client disconnected
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
    }
}
