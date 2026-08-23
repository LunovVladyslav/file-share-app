package com.lunov.flyshare.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The pairing exchange, both roles — docs/PROTOCOL.md §7.
 *
 * Written against streams rather than sockets so the whole handshake can be
 * driven over a pipe in a unit test, with no network involved.
 */
object Pairing {

    const val STEP_TIMEOUT_MS = 30_000
    const val DECISION_TIMEOUT_MS = 180_000

    /** What the far side told us about itself. Display data, not identity. */
    data class RemoteDevice(val id: String, val name: String, val os: String)

    data class Outcome(val peer: PairedPeer, val code: String)

    private fun deviceObject(self: SelfDescription): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(self.id))
        put("name", JsonPrimitive(self.name))
        put("os", JsonPrimitive(self.os))
    }

    private fun readDevice(frame: JsonObject): RemoteDevice? {
        val device = frame.obj("device") ?: return null
        val id = device.string("id") ?: return null
        return RemoteDevice(id, device.string("name") ?: id, device.string("os") ?: "unknown")
    }

    /**
     * Dial a peer and run the exchange. `onCode` fires the moment the number is
     * known, so it can go on screen while the far side is still waiting for a
     * person to press a button.
     */
    fun initiate(
        address: String,
        port: Int,
        self: SelfDescription,
        identity: Identity,
        trust: TrustStore,
        onCode: (String) -> Unit,
    ): Outcome {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), STEP_TIMEOUT_MS)
            socket.soTimeout = STEP_TIMEOUT_MS
            return initiateOver(socket.getInputStream(), socket.getOutputStream(), self, identity, trust, onCode) {
                socket.soTimeout = DECISION_TIMEOUT_MS
            }
        }
    }

    internal fun initiateOver(
        input: InputStream,
        output: OutputStream,
        self: SelfDescription,
        identity: Identity,
        trust: TrustStore,
        onCode: (String) -> Unit,
        beforeDecision: () -> Unit = {},
    ): Outcome {
        val myPublic = identity.publicKeyString
        val myNonce = Crypto.randomBytes(16).toBase64Url()

        Frames.write(output, frame("t" to "pair", "ver" to PROTOCOL_VERSION, "device" to deviceObject(self)))

        val commitFrame = expect(input, "pair-commit")
        val commit = commitFrame.string("commit") ?: throw PairingException("commitment was missing")

        Frames.write(output, frame("t" to "pair-reveal", "pub" to myPublic, "nonce" to myNonce))

        val open = expect(input, "pair-open")
        val peerPublic = open.string("pub") ?: throw PairingException("peer sent no public key")
        val peerNonce = open.string("nonce") ?: throw PairingException("peer sent no nonce")

        // The commitment is the whole point: without checking it here, a device
        // in the middle could pick its key after seeing ours and grind the two
        // displayed codes into agreement.
        if (Sas.commitment(peerPublic, peerNonce) != commit) {
            throw PairingException("the other device changed its key mid-pairing — pairing aborted")
        }

        val shared = Crypto.x25519(identity.privateKey, peerPublic.fromBase64Url())
        val code = Sas.code(myPublic, peerPublic, myNonce, peerNonce, shared)
        onCode(code)

        beforeDecision()
        val result = expect(input, "pair-result")
        if (result.string("accept") != "true") {
            throw PairingException(result.string("reason") ?: "the other device declined")
        }

        val device = readDevice(open) ?: throw PairingException("peer did not identify itself")
        val peer = PairedPeer(device.id, device.name, device.os, peerPublic, System.currentTimeMillis())
        trust.remember(peer)
        return Outcome(peer, code)
    }

    /**
     * The answering half, held open while a person decides.
     *
     * `confirm` is called with the code once both sides can compute it, and
     * blocks until someone answers — that wait is the security control, not an
     * inconvenience to be engineered away.
     */
    fun respond(
        input: InputStream,
        output: OutputStream,
        hello: JsonObject,
        self: SelfDescription,
        identity: Identity,
        trust: TrustStore,
        confirm: (peer: RemoteDevice, code: String) -> Boolean,
    ): Outcome {
        val device = readDevice(hello) ?: throw PairingException("peer did not identify itself")

        val myPublic = identity.publicKeyString
        val myNonce = Crypto.randomBytes(16).toBase64Url()

        // Committed before the initiator's key is visible — §7.1.
        Frames.write(output, frame("t" to "pair-commit", "commit" to Sas.commitment(myPublic, myNonce)))

        val reveal = expect(input, "pair-reveal")
        val peerPublic = reveal.string("pub") ?: throw PairingException("peer sent no public key")
        val peerNonce = reveal.string("nonce") ?: throw PairingException("peer sent no nonce")

        Frames.write(
            output,
            frame(
                "t" to "pair-open",
                "pub" to myPublic,
                "nonce" to myNonce,
                "device" to deviceObject(self),
            ),
        )

        val shared = Crypto.x25519(identity.privateKey, peerPublic.fromBase64Url())
        val code = Sas.code(peerPublic, myPublic, peerNonce, myNonce, shared)

        val accepted = confirm(device, code)
        Frames.write(
            output,
            frame(
                "t" to "pair-result",
                "accept" to accepted,
                "reason" to if (accepted) null else "declined on the other device",
            ),
        )
        if (!accepted) throw PairingException("declined on this device")

        val peer = PairedPeer(device.id, device.name, device.os, peerPublic, System.currentTimeMillis())
        trust.remember(peer)
        return Outcome(peer, code)
    }

    private fun expect(input: InputStream, type: String): JsonObject {
        val frame = Frames.read(input)
        if (frame.type() == "pair-error") {
            throw PairingException(frame.string("reason") ?: "pairing failed")
        }
        if (frame.type() != type) {
            throw PairingException("unexpected reply \"${frame.type()}\" during pairing")
        }
        return frame
    }
}

class PairingException(message: String) : Exception(message)
