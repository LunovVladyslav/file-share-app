package com.lunov.flyshare.core

import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing handshake driven over pipes, so both halves run in one test with
 * no network, no ports, and no timing luck.
 */
class PairingTest {

    private fun device(name: String) = SelfDescription(id = newDeviceId(), name = name, os = "test")

    /**
     * Runs a full exchange between two fresh devices.
     *
     * @param accept what the responder's person decides
     * @param tamper lets a test corrupt what the initiator receives
     */
    private fun exchange(
        accept: Boolean = true,
        initiatorSelf: SelfDescription = device("Phone"),
        responderSelf: SelfDescription = device("Desktop"),
        initiatorStorage: Storage = MemoryStorage(),
        responderStorage: Storage = MemoryStorage(),
    ): Pair<Result<Pairing.Outcome>, Result<Pairing.Outcome>> {
        // a -> b carries the initiator's frames, b -> a the responder's
        val toResponder = PipedOutputStream()
        val responderIn = PipedInputStream(toResponder, 64 * 1024)
        val toInitiator = PipedOutputStream()
        val initiatorIn = PipedInputStream(toInitiator, 64 * 1024)

        val initiatorIdentity = Identity(initiatorStorage)
        val initiatorTrust = TrustStore(initiatorStorage)
        val responderIdentity = Identity(responderStorage)
        val responderTrust = TrustStore(responderStorage)

        var initiatorCode: String? = null
        var responderCode: String? = null

        var responderResult: Result<Pairing.Outcome>? = null
        val responderThread = thread {
            responderResult = runCatching {
                val hello = Frames.read(responderIn)
                Pairing.respond(
                    responderIn, toInitiator, hello, responderSelf, responderIdentity, responderTrust,
                ) { _, code ->
                    responderCode = code
                    accept
                }
            }
        }

        val initiatorResult = runCatching {
            Pairing.initiateOver(
                initiatorIn, toResponder, initiatorSelf, initiatorIdentity, initiatorTrust,
                onCode = { initiatorCode = it },
            )
        }
        responderThread.join(10_000)

        // Whenever both sides got far enough, the numbers must agree.
        if (initiatorCode != null && responderCode != null) {
            assertEquals(initiatorCode, responderCode, "the two screens showed different codes")
        }
        return initiatorResult to responderResult!!
    }

    @Test
    fun `both ends derive the same code and pin each other`() {
        val phoneStorage = MemoryStorage()
        val desktopStorage = MemoryStorage()
        val phone = device("Phone")
        val desktop = device("Desktop")

        val (initiator, responder) = exchange(
            initiatorSelf = phone, responderSelf = desktop,
            initiatorStorage = phoneStorage, responderStorage = desktopStorage,
        )

        assertTrue(initiator.isSuccess, "initiator failed: ${initiator.exceptionOrNull()?.message}")
        assertTrue(responder.isSuccess, "responder failed: ${responder.exceptionOrNull()?.message}")
        assertEquals(initiator.getOrThrow().code, responder.getOrThrow().code)
        assertTrue(initiator.getOrThrow().code.matches(Regex("\\d{6}")))

        // Each side stored the other's real key, not its own.
        val phoneTrust = TrustStore(phoneStorage)
        val desktopTrust = TrustStore(desktopStorage)
        assertEquals(
            Identity(desktopStorage).publicKeyString,
            phoneTrust.all().single().publicKey,
        )
        assertEquals(
            Identity(phoneStorage).publicKeyString,
            desktopTrust.all().single().publicKey,
        )
        assertEquals(desktop.id, phoneTrust.all().single().id)
        assertEquals(phone.id, desktopTrust.all().single().id)
    }

    @Test
    fun `a refusal pins nothing on either side`() {
        val phoneStorage = MemoryStorage()
        val desktopStorage = MemoryStorage()

        val (initiator, responder) = exchange(
            accept = false,
            initiatorStorage = phoneStorage, responderStorage = desktopStorage,
        )

        assertTrue(initiator.isFailure)
        assertTrue(responder.isFailure)
        assertTrue(TrustStore(phoneStorage).all().isEmpty())
        assertTrue(TrustStore(desktopStorage).all().isEmpty())
    }

    @Test
    fun `a responder that swaps its key after committing is caught`() {
        // This is the attack the commitment exists to stop: reveal a different
        // key once the initiator's is known, and grind the codes into agreement.
        val toResponder = PipedOutputStream()
        val responderIn = PipedInputStream(toResponder, 64 * 1024)
        val toInitiator = PipedOutputStream()
        val initiatorIn = PipedInputStream(toInitiator, 64 * 1024)

        val storage = MemoryStorage()
        val identity = Identity(storage)
        val trust = TrustStore(storage)

        thread {
            runCatching {
                Frames.read(responderIn)
                val honestNonce = Crypto.randomBytes(16).toBase64Url()
                val (_, honestPublic) = Crypto.newKeyPair()

                // Commit to one key…
                Frames.write(
                    toInitiator,
                    frame("t" to "pair-commit", "commit" to Sas.commitment(honestPublic.toBase64Url(), honestNonce)),
                )
                Frames.read(responderIn)

                // …then open with another.
                val (_, swapped) = Crypto.newKeyPair()
                Frames.write(
                    toInitiator,
                    frame(
                        "t" to "pair-open",
                        "pub" to swapped.toBase64Url(),
                        "nonce" to honestNonce,
                        "device" to frame("id" to "evil", "name" to "Evil", "os" to "test"),
                    ),
                )
            }
        }

        val failure = assertFailsWith<PairingException> {
            Pairing.initiateOver(initiatorIn, toResponder, device("Phone"), identity, trust, onCode = {})
        }
        assertTrue("changed its key" in failure.message!!, failure.message!!)
        assertTrue(trust.all().isEmpty(), "nothing may be pinned after a failed commitment")
    }

    @Test
    fun `an interceptor cannot show the same code on both sides`() {
        // Two independent exchanges, as a machine in the middle would run them.
        val phone = Identity(MemoryStorage())
        val desktop = Identity(MemoryStorage())
        val attacker = Identity(MemoryStorage())

        val nonceA = Crypto.randomBytes(16).toBase64Url()
        val nonceB = Crypto.randomBytes(16).toBase64Url()

        val phoneSide = Sas.code(
            phone.publicKeyString, attacker.publicKeyString, nonceA, nonceB,
            Crypto.x25519(phone.privateKey, attacker.publicKey),
        )
        val desktopSide = Sas.code(
            attacker.publicKeyString, desktop.publicKeyString, nonceA, nonceB,
            Crypto.x25519(attacker.privateKey, desktop.publicKey),
        )
        assertTrue(phoneSide != desktopSide, "an interceptor produced matching codes")
    }
}

class TrustStoreTest {

    @Test
    fun `pins, finds and forgets`() {
        val storage = MemoryStorage()
        val trust = TrustStore(storage)
        val (_, publicKey) = Crypto.newKeyPair()
        val peer = PairedPeer("aabb", "Desktop", "windows", publicKey.toBase64Url(), 1_700_000_000_000)

        assertTrue(trust.all().isEmpty())
        trust.remember(peer)
        assertTrue(trust.isPaired("aabb"))
        assertNotNull(trust.publicKeyOf("aabb"))
        assertEquals(publicKey.toList(), trust.publicKeyOf("aabb")!!.toList())

        // A second instance must see it: the store lives in storage, not memory.
        assertEquals(1, TrustStore(storage).all().size)

        assertTrue(trust.forget("aabb"))
        assertNull(trust.publicKeyOf("aabb"))
        assertTrue(!trust.forget("aabb"))
    }

    @Test
    fun `identity survives a restart and is not regenerated`() {
        val storage = MemoryStorage()
        val first = Identity(storage)
        val id = first.deviceId
        val key = first.publicKeyString

        val second = Identity(storage)
        assertEquals(id, second.deviceId)
        assertEquals(key, second.publicKeyString)
        assertTrue(id.matches(Regex("[0-9a-f]{16}")), "device id should be 16 hex characters: $id")
    }
}
