package com.lunov.flyshare.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * The listening half of a peer — docs/PROTOCOL.md §6.
 *
 * One port serves everything, and the first frame says what a connection is
 * for. Only pairing is implemented so far; a `session` gets a clear refusal
 * rather than a dropped connection, so the far side can say something useful.
 */
class PeerServer(
    private val self: SelfDescription,
    private val identity: Identity,
    private val trust: TrustStore,
    private val onPairingRequest: (Pairing.RemoteDevice, String) -> Boolean,
    private val onPaired: (PairedPeer) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val port: Int = TRANSFER_PORT,
) {

    private var server: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (server != null) return
        val listener = ServerSocket(port)
        server = listener
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: continue
                // One connection per coroutine: a person deciding on a pairing
                // must not stop anything else from being answered.
                launch(Dispatchers.IO) { handle(socket) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { open ->
            runCatching {
                open.tcpNoDelay = true
                open.soTimeout = Pairing.STEP_TIMEOUT_MS
                val input = open.getInputStream()
                val output = open.getOutputStream()
                dispatch(Frames.read(input), input, output, open)
            }.onFailure { error ->
                // A peer that hangs up mid-handshake is ordinary and not worth
                // surfacing; anything else is something the person should see.
                if (error !is FrameException) {
                    onFailure(error.message ?: error::class.simpleName ?: "connection failed")
                }
            }
        }
    }

    private fun dispatch(
        first: JsonObject,
        input: InputStream,
        output: OutputStream,
        socket: Socket,
    ) {
        when (first.type()) {
            "pair" -> {
                socket.soTimeout = Pairing.DECISION_TIMEOUT_MS
                val outcome = Pairing.respond(input, output, first, self, identity, trust) { device, code ->
                    onPairingRequest(device, code)
                }
                onPaired(outcome.peer)
            }

            "session" -> Frames.write(
                output,
                frame(
                    "t" to "session-err",
                    "reason" to "this device cannot transfer yet — pairing only",
                    "needsPairing" to !trust.isPaired(first.string("deviceId") ?: ""),
                ),
            )

            else -> Frames.write(
                output,
                frame(
                    "t" to "session-err",
                    "reason" to "this device speaks protocol v$PROTOCOL_VERSION",
                ),
            )
        }
    }
}
