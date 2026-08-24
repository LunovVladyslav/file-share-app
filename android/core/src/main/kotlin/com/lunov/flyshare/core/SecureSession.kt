package com.lunov.flyshare.core

import kotlinx.serialization.json.JsonObject
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsProtocol
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCrypto
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import java.security.SecureRandom
import java.security.Security
import java.util.Vector

/**
 * An open, authenticated connection to a peer.
 *
 * The receiver is written against this rather than against a socket, so a
 * hostile sender can be tested over a pair of in-memory pipes: no ports, no
 * timing, and no cooperating implementation that would never send the frames
 * being defended against in the first place.
 */
interface SecureChannel : Closeable {
    val input: InputStream
    val output: OutputStream

    /** The device id proved by the handshake, not the one the peer claims. */
    val peerDeviceId: String

    /** Zero means wait indefinitely — what an idle control connection wants. */
    fun readTimeout(milliseconds: Int)
}

/**
 * TLS 1.3 with an external PSK — docs/PROTOCOL.md §8.
 *
 * Android's own stack cannot do this: Conscrypt's PSKKeyManager is deprecated
 * precisely because it does not work with TLS 1.3. BouncyCastle can, in both
 * roles, which is what the spike in `spike/` established against the real Node
 * implementation before any of this was written.
 */
object SecureSession {

    private const val PSK_IDENTITY = "flyshare"

    /** BouncyCastle's JCE name for the RFC 7539 form of ChaCha20. */
    private const val CHACHA20 = "ChaCha7539"

    /**
     * SHA-256 suites only, and AES first.
     *
     * The restriction is not a preference, it is required: a TLS 1.3 external
     * PSK is bound to one hash, so offering a SHA-384 suite lets the peer pick
     * a different digest and the handshake dies with "ciphersuite digest has
     * changed" — an error that names the symptom and hides the cause.
     *
     * AES leads because it reaches hardware acceleration on every current phone
     * and desktop; the spike measured it about three times faster than ChaCha20
     * through this stack, with a handshake six times quicker.
     */
    private val CIPHER_SUITES = intArrayOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
    )

    init {
        // JcaTlsCrypto asks the JCE for the record cipher by name, and only
        // BouncyCastle knows ChaCha20 as "ChaCha7539".
        //
        // Android already registers a cut-down BouncyCastle under the name
        // "BC", and it does not include that cipher. Checking for the *name*
        // therefore finds a provider and installs nothing, and a negotiated
        // ChaCha20 suite then dies with "No provider found for ChaCha7539" —
        // after the handshake, which makes it read like a protocol fault.
        // So ask for the algorithm, which is the thing actually needed.
        val available = runCatching { Cipher.getInstance(CHACHA20) }.isSuccess
        if (!available) {
            Security.removeProvider("BC")
            // Appended rather than inserted first: AES-GCM must keep coming
            // from the platform provider, where it is hardware-accelerated.
            runCatching { Security.addProvider(BouncyCastleProvider()) }
        }
    }

    private fun crypto(): TlsCrypto = JcaTlsCryptoProvider().create(SecureRandom())

    /** An encrypted connection, plus who is on the other end. */
    class Connection(
        override val input: InputStream,
        override val output: OutputStream,
        override val peerDeviceId: String,
        val cipherSuite: Int,
        private val socket: Socket,
        private val protocol: TlsProtocol,
    ) : SecureChannel {
        val description: String get() = "TLSv1.3 / ${cipherSuiteName(cipherSuite)}"

        override fun readTimeout(milliseconds: Int) {
            runCatching { socket.soTimeout = milliseconds }
        }

        /**
         * A TLS close_notify first, then the socket. Without it the peer sees a
         * truncated stream, which it cannot tell apart from a connection cut.
         */
        override fun close() {
            runCatching { protocol.close() }
            runCatching { socket.close() }
        }
    }

    /**
     * Dial a paired peer and bring the connection up.
     *
     * The plaintext prologue carries nothing secret — two ephemeral public keys
     * — and everything after it is inside TLS.
     */
    fun connect(
        peer: Peer,
        self: SelfDescription,
        identity: Identity,
        trust: TrustStore,
        connectTimeoutMs: Int = 15_000,
    ): Connection {
        val peerIdentity = trust.publicKeyOf(peer.id)
            ?: throw SessionException("${peer.name} is not paired with this device yet", needsPairing = true)

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(peer.address, peer.port), connectTimeoutMs)
        socket.soTimeout = connectTimeoutMs

        try {
            val (ephemeralPrivate, ephemeralPublic) = Crypto.newKeyPair()
            Frames.write(
                socket.getOutputStream(),
                frame(
                    "t" to "session",
                    "ver" to PROTOCOL_VERSION,
                    "deviceId" to self.id,
                    "ephPub" to ephemeralPublic.toBase64Url(),
                ),
            )

            val reply = Frames.read(socket.getInputStream())
            if (reply.type() != "session-ok") {
                throw SessionException(
                    reply.string("reason") ?: "the other device refused the connection",
                    needsPairing = reply.bool("needsPairing") == true,
                )
            }
            if (reply.string("deviceId") != peer.id) {
                throw SessionException("the device at that address is not the one that was paired")
            }
            // Better evidence of presence than an announcement: this arrived.
            Presence.noteContact(peer.id)

            val psk = Sas.sessionKey(
                ownEphemeralPrivate = ephemeralPrivate,
                peerEphemeralPublic = (reply.string("ephPub") ?: throw SessionException("no ephemeral key"))
                    .fromBase64Url(),
                ownIdentityPrivate = identity.privateKey,
                peerIdentityPublic = peerIdentity,
                selfId = self.id,
                peerId = peer.id,
            )

            val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            val client = PskClient(crypto(), psk)
            protocol.connect(client)
            return Connection(
                protocol.inputStream, protocol.outputStream,
                peer.id, client.selected, socket, protocol,
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is SessionException) e else SessionException(handshakeMessage(e))
        }
    }

    /**
     * Bring up an accepted connection whose opening frame was `session`.
     *
     * An unpaired peer is told so and gets no further: it cannot derive the
     * key, so there is nothing to fall back to and nothing to leak.
     */
    fun accept(
        socket: Socket,
        hello: JsonObject,
        self: SelfDescription,
        identity: Identity,
        trust: TrustStore,
    ): Connection {
        val output = socket.getOutputStream()
        val peerId = hello.string("deviceId")

        if (hello.int("ver") != PROTOCOL_VERSION) {
            Frames.write(output, frame(
                "t" to "session-err",
                "reason" to "protocol mismatch (peer v${hello.int("ver")}, this device v$PROTOCOL_VERSION)",
            ))
            throw SessionException("protocol mismatch")
        }

        val peerIdentity = peerId?.let { trust.publicKeyOf(it) }
        if (peerId == null || peerIdentity == null) {
            Frames.write(output, frame(
                "t" to "session-err",
                "needsPairing" to true,
                "reason" to "this device has not been paired yet",
            ))
            throw SessionException("peer is not paired", needsPairing = true)
        }

        Presence.noteContact(peerId)

        val (ephemeralPrivate, ephemeralPublic) = Crypto.newKeyPair()
        Frames.write(output, frame(
            "t" to "session-ok",
            "deviceId" to self.id,
            "ephPub" to ephemeralPublic.toBase64Url(),
        ))

        val psk = Sas.sessionKey(
            ownEphemeralPrivate = ephemeralPrivate,
            peerEphemeralPublic = (hello.string("ephPub") ?: throw SessionException("no ephemeral key"))
                .fromBase64Url(),
            ownIdentityPrivate = identity.privateKey,
            peerIdentityPublic = peerIdentity,
            selfId = self.id,
            peerId = peerId,
        )

        return try {
            val protocol = TlsServerProtocol(socket.getInputStream(), output)
            val server = PskServer(crypto(), psk)
            protocol.accept(server)
            Connection(
                protocol.inputStream, protocol.outputStream,
                peerId, server.selected, socket, protocol,
            )
        } catch (e: Exception) {
            throw SessionException(handshakeMessage(e))
        }
    }

    /**
     * A failed handshake almost always means the pinned keys no longer agree,
     * and "handshake failure" tells nobody that.
     */
    private fun handshakeMessage(e: Exception): String = when {
        e is TlsFatalAlert && e.alertDescription == AlertDescription.decrypt_error ->
            "the other device did not recognise this one — pair again"
        else -> "could not establish an encrypted connection — ${e.message ?: e::class.simpleName}"
    }

    private class PskClient(crypto: TlsCrypto, private val psk: ByteArray) : DefaultTlsClient(crypto) {
        var selected: Int = 0
            private set

        override fun getProtocolVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
        override fun getSupportedCipherSuites(): IntArray = CIPHER_SUITES

        override fun getExternalPSKs(): Vector<*> = Vector<TlsPSKExternal>().apply {
            add(BasicTlsPSKExternal(
                PSK_IDENTITY.toByteArray(Charsets.US_ASCII),
                crypto.createSecret(psk),
                PRFAlgorithm.tls13_hkdf_sha256,
            ))
        }

        override fun notifySelectedCipherSuite(cipherSuite: Int) {
            selected = cipherSuite
            super.notifySelectedCipherSuite(cipherSuite)
        }

        // Never reached: a PSK-only TLS 1.3 handshake exchanges no certificate.
        override fun getAuthentication(): TlsAuthentication =
            throw UnsupportedOperationException("no certificates in a PSK handshake")
    }

    private class PskServer(crypto: TlsCrypto, private val psk: ByteArray) : DefaultTlsServer(crypto) {
        var selected: Int = 0
            private set

        override fun getProtocolVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
        override fun getSupportedCipherSuites(): IntArray = CIPHER_SUITES

        /**
         * Our order, not the client's. OpenSSL offers ChaCha20 ahead of
         * AES-128, so honouring the client's preference gives away the
         * threefold speed difference measured in the spike for nothing.
         */
        override fun preferLocalCipherSuites(): Boolean = true

        override fun getExternalPSK(identities: Vector<*>?): TlsPSKExternal = BasicTlsPSKExternal(
            PSK_IDENTITY.toByteArray(Charsets.US_ASCII),
            crypto.createSecret(psk),
            PRFAlgorithm.tls13_hkdf_sha256,
        )

        override fun getSelectedCipherSuite(): Int =
            super.getSelectedCipherSuite().also { selected = it }

        override fun getCredentials(): TlsCredentials? = null // the PSK is the authentication
    }

    fun cipherSuiteName(suite: Int): String = when (suite) {
        CipherSuite.TLS_AES_128_GCM_SHA256 -> "TLS_AES_128_GCM_SHA256"
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256 -> "TLS_CHACHA20_POLY1305_SHA256"
        else -> "0x%04x".format(suite)
    }
}

class SessionException(message: String, val needsPairing: Boolean = false) : Exception(message)
