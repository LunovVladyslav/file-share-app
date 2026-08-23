package com.lunov.flyshare.core

/**
 * The six-digit code two people compare when devices first meet —
 * docs/PROTOCOL.md §7.
 */
object Sas {

    private const val COMMIT_LABEL = "flyshare-sas-commit-v2"
    private const val SAS_LABEL = "flyshare-sas-v2"
    private const val SESSION_LABEL = "flyshare-session-v2"

    /**
     * The responder's commitment, sent before it has seen the initiator's key.
     *
     * Note the mixed encoding, which §7.2 spells out and which is the easiest
     * thing here to get wrong: the public key is hashed as its base64url
     * *text*, the nonce as its *decoded* bytes. Decoding both, or neither,
     * produces a commitment the desktop will reject.
     */
    fun commitment(publicKey: String, nonce: String): String =
        Crypto.sha256(
            COMMIT_LABEL.toByteArray(Charsets.UTF_8),
            publicKey.toByteArray(Charsets.UTF_8),
            nonce.fromBase64Url(),
        ).toBase64Url()

    /**
     * Both ends derive this from the same exchange, so two screens showing the
     * same number means nothing is sitting in between.
     *
     * Roles are fixed: initiator first, never sorted.
     */
    fun code(
        initiatorPublic: String,
        responderPublic: String,
        initiatorNonce: String,
        responderNonce: String,
        shared: ByteArray,
    ): String {
        val salt = initiatorNonce.fromBase64Url() + responderNonce.fromBase64Url()
        val info = "$SAS_LABEL|$initiatorPublic|$responderPublic".toByteArray(Charsets.UTF_8)
        val material = Crypto.hkdfSha256(shared, salt, info, 4)

        val value = ((material[0].toLong() and 0xff) shl 24) or
            ((material[1].toLong() and 0xff) shl 16) or
            ((material[2].toLong() and 0xff) shl 8) or
            (material[3].toLong() and 0xff)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    /**
     * The pre-shared key for one connection — §8.2.
     *
     * The ephemeral half gives forward secrecy; the pinned half means an
     * unpaired device cannot produce this value at all, so its TLS handshake
     * fails and there is nothing to fall back to.
     */
    fun sessionKey(
        ownEphemeralPrivate: ByteArray,
        peerEphemeralPublic: ByteArray,
        ownIdentityPrivate: ByteArray,
        peerIdentityPublic: ByteArray,
        selfId: String,
        peerId: String,
    ): ByteArray {
        val ephemeralShared = Crypto.x25519(ownEphemeralPrivate, peerEphemeralPublic)
        val pairingSecret = Crypto.x25519(ownIdentityPrivate, peerIdentityPublic)
        // Sorted, so both ends derive the same key regardless of who dialled.
        val ordered = listOf(selfId, peerId).sorted().joinToString("|")
        val info = "$SESSION_LABEL|$ordered".toByteArray(Charsets.UTF_8)
        return Crypto.hkdfSha256(ephemeralShared, pairingSecret, info, 32)
    }
}
