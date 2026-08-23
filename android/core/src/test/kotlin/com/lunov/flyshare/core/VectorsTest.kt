package com.lunov.flyshare.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-compatibility with the desktop, checked against the vectors the Node
 * implementation emits (`node scripts/vectors.js`).
 *
 * This is what makes it possible to be confident in the derivations without
 * standing up two apps: an encoding mistake fails here in milliseconds instead
 * of surfacing as a pairing whose codes mysteriously never match.
 */
class VectorsTest {

    private val vectors: JsonObject = run {
        val candidates = listOf(
            File("../../spec/vectors.json"),
            File("../spec/vectors.json"),
            File("spec/vectors.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("spec/vectors.json not found; run `node scripts/vectors.js` first")
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun section(name: String) = vectors[name]!!.jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `X25519 agreement matches the desktop`() {
        val keys = section("keys")
        val expected = section("x25519")

        val identityShared = Crypto.x25519(
            keys["identityA"]!!.jsonObject.str("private").fromBase64Url(),
            keys["identityB"]!!.jsonObject.str("public").fromBase64Url(),
        )
        assertEquals(expected.str("identityAxB"), identityShared.toBase64Url())

        val ephemeralShared = Crypto.x25519(
            keys["ephemeralA"]!!.jsonObject.str("private").fromBase64Url(),
            keys["ephemeralB"]!!.jsonObject.str("public").fromBase64Url(),
        )
        assertEquals(expected.str("ephemeralAxB"), ephemeralShared.toBase64Url())
    }

    @Test
    fun `public keys derive from their private halves`() {
        val keys = section("keys")
        for (name in listOf("identityA", "identityB", "ephemeralA", "ephemeralB")) {
            val entry = keys[name]!!.jsonObject
            assertEquals(
                entry.str("public"),
                Crypto.publicKeyOf(entry.str("private").fromBase64Url()).toBase64Url(),
                "public key mismatch for $name",
            )
        }
    }

    @Test
    fun `pairing commitment matches the desktop`() {
        val expected = section("commitment")
        assertEquals(
            expected.str("expected"),
            Sas.commitment(expected.str("publicKey"), expected.str("nonce")),
        )
    }

    @Test
    fun `decoding the key as well would not interoperate`() {
        // Guards the asymmetry §7.2 warns about, so a future refactor that
        // "tidies" the encoding fails here rather than in the field.
        val expected = section("commitment")
        val wrong = Crypto.sha256(
            "flyshare-sas-commit-v2".toByteArray(Charsets.UTF_8),
            expected.str("publicKey").fromBase64Url(),
            expected.str("nonce").fromBase64Url(),
        ).toBase64Url()
        assertTrue(wrong != expected.str("expected"))
    }

    @Test
    fun `six-digit code matches the desktop`() {
        val keys = section("keys")
        val sas = section("sas")
        val shared = Crypto.x25519(
            keys["identityA"]!!.jsonObject.str("private").fromBase64Url(),
            keys["identityB"]!!.jsonObject.str("public").fromBase64Url(),
        )
        assertEquals(
            sas.str("expected"),
            Sas.code(
                initiatorPublic = sas.str("initiatorPublic"),
                responderPublic = sas.str("responderPublic"),
                initiatorNonce = sas.str("initiatorNonce"),
                responderNonce = sas.str("responderNonce"),
                shared = shared,
            ),
        )
    }

    @Test
    fun `session key matches the desktop, from either end`() {
        val keys = section("keys")
        val session = section("session")
        val idA = session.str("deviceIdA")
        val idB = session.str("deviceIdB")

        val onA = Sas.sessionKey(
            ownEphemeralPrivate = keys["ephemeralA"]!!.jsonObject.str("private").fromBase64Url(),
            peerEphemeralPublic = keys["ephemeralB"]!!.jsonObject.str("public").fromBase64Url(),
            ownIdentityPrivate = keys["identityA"]!!.jsonObject.str("private").fromBase64Url(),
            peerIdentityPublic = keys["identityB"]!!.jsonObject.str("public").fromBase64Url(),
            selfId = idA,
            peerId = idB,
        )
        assertEquals(session.str("expectedPsk"), onA.toBase64Url())

        // The responder lists itself first; sorting inside must make that moot.
        val onB = Sas.sessionKey(
            ownEphemeralPrivate = keys["ephemeralB"]!!.jsonObject.str("private").fromBase64Url(),
            peerEphemeralPublic = keys["ephemeralA"]!!.jsonObject.str("public").fromBase64Url(),
            ownIdentityPrivate = keys["identityB"]!!.jsonObject.str("private").fromBase64Url(),
            peerIdentityPublic = keys["identityA"]!!.jsonObject.str("public").fromBase64Url(),
            selfId = idB,
            peerId = idA,
        )
        assertEquals(session.str("expectedPsk"), onB.toBase64Url())
    }

    @Test
    fun `framing matches the desktop byte for byte`() {
        val framing = section("framing")
        val encoded = Frames.encode(framing["message"]!!.jsonObject)
        assertEquals(framing.str("encoded"), encoded.joinToString("") { "%02x".format(it) })
    }
}
