package com.lunov.flyshare.core

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.util.Base64

/**
 * The primitives docs/PROTOCOL.md is written in terms of.
 *
 * BouncyCastle rather than the platform: `KeyAgreement("XDH")` only arrived in
 * API 33 and the app supports 26. Using one implementation everywhere also
 * means the desktop spike's results carry over unchanged — those exact calls
 * were checked against Node's output before any of this was written.
 */
object Crypto {

    private val random = SecureRandom()

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    /** A fresh X25519 key pair as (private, public), both 32 raw bytes. */
    fun newKeyPair(): Pair<ByteArray, ByteArray> {
        val privateKey = X25519PrivateKeyParameters(random)
        return privateKey.encoded to privateKey.generatePublicKey().encoded
    }

    fun publicKeyOf(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        return ByteArray(agreement.agreementSize).also {
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), it, 0)
        }
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = SHA256Digest()
        for (part in parts) digest.update(part, 0, part.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    /** Constant-time comparison, for anything an attacker could probe. */
    fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

// base64url without padding — the only encoding on the wire, per §1.
private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val decoder: Base64.Decoder = Base64.getUrlDecoder()

fun ByteArray.toBase64Url(): String = encoder.encodeToString(this)

fun String.fromBase64Url(): ByteArray = decoder.decode(this)

/**
 * A device id: 8 random bytes as 16 lowercase hex characters, per §3.
 * It is a routing label, not a credential — the key is what authenticates.
 */
fun newDeviceId(): String =
    Crypto.randomBytes(8).joinToString("") { "%02x".format(it) }
