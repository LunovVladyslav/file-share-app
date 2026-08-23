package com.lunov.flyshare.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receive a transfer from the real Node sender, from a plain JVM.
 *
 *     ./gradlew :core:receive -PoutDir=... [-PtimeoutSeconds=120]
 *
 * The counterpart is `spike/send-to.mjs`, which drives the actual desktop
 * client. Running the two against each other is how the receiver gets checked
 * against the implementation it has to interoperate with, rather than against
 * a second reading of the specification — and it does so on a desktop, where a
 * failure is far easier to look at than on a phone.
 *
 * Both sides derive their keys from a fixed seed so no pairing, and therefore
 * no person, is needed. Pairing is verified separately on real devices.
 */
object TransferProbe {

    private const val NODE_SEED = "flyshare-interop-node"
    private const val KOTLIN_SEED = "flyshare-interop-kotlin"
    private const val NODE_DEVICE_ID = "1111111111111111"
    private const val KOTLIN_DEVICE_ID = "2222222222222222"

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: "build/received").absoluteFile
        val timeoutSeconds = args.getOrNull(1)?.toLongOrNull() ?: 120L
        val port = args.getOrNull(2)?.toIntOrNull() ?: TRANSFER_PORT

        outDir.deleteRecursively()
        outDir.mkdirs()

        val storage = MemoryStorage()
        storage.write("identity.json", seededIdentity(KOTLIN_SEED, KOTLIN_DEVICE_ID))
        storage.write("peers.json", pinned(NODE_DEVICE_ID, NODE_SEED))

        val identity = Identity(storage)
        val trust = TrustStore(storage)
        val self = SelfDescription(id = identity.deviceId, name = "Kotlin receiver", os = "android")

        val done = CountDownLatch(1)
        var outcome: TransferProgress? = null
        var lastPercent = -1

        val receiver = TransferReceiver(
            store = { FileDownloadStore(outDir) },
            ask = { offer ->
                println("offer from ${offer.peerName}: ${offer.files.size} file(s), ${offer.totalSize} bytes")
                true // a probe has nobody to ask
            },
            onUpdate = { progress ->
                when (progress.status) {
                    TransferStatus.Receiving -> {
                        val percent = (progress.fraction * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            print("\r  $percent%   ")
                            System.out.flush()
                        }
                    }
                    TransferStatus.Complete, TransferStatus.Failed, TransferStatus.Declined -> {
                        outcome = progress
                        done.countDown()
                    }
                    else -> {}
                }
            },
        )

        val server = PeerServer(
            self = self,
            identity = identity,
            trust = trust,
            onPairingRequest = { _, _ -> false }, // this probe only receives
            onFailure = { println("\nconnection failed: $it") },
            transfers = receiver,
            port = port,
        )

        val scope = CoroutineScope(SupervisorJob())
        server.start(scope)

        println("listening on $port as ${self.name} [${self.id}]")
        println("send with: node spike/send-to.mjs <host> $port ${self.id} <path...>")

        val finished = done.await(timeoutSeconds, TimeUnit.SECONDS)
        server.stop()
        print("\r")

        if (!finished) {
            println("no transfer completed within ${timeoutSeconds}s")
            kotlin.system.exitProcess(1)
        }

        val result = outcome!!
        println("${result.status}: ${result.received} of ${result.totalSize} bytes")
        result.detail?.let { println("  $it") }

        // A digest per file, so the comparison with the sender is exact rather
        // than "the sizes look right".
        outDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            println("  ${sha256(file)}  ${file.length().toString().padStart(12)}  " +
                file.relativeTo(outDir).path.replace(File.separatorChar, '/'))
        }

        kotlin.system.exitProcess(if (result.status == TransferStatus.Complete) 0 else 1)
    }

    private fun seededIdentity(seed: String, deviceId: String): String = json(buildJsonObject {
        put("privateKey", JsonPrimitive(privateKeyFromSeed(seed).toBase64Url()))
        put("deviceId", JsonPrimitive(deviceId))
    })

    private fun pinned(deviceId: String, seed: String): String = json(buildJsonObject {
        put(deviceId, buildJsonObject {
            put("name", JsonPrimitive("Node interop sender"))
            put("os", JsonPrimitive("windows"))
            put("publicKey", JsonPrimitive(Crypto.publicKeyOf(privateKeyFromSeed(seed)).toBase64Url()))
            put("pairedAt", JsonPrimitive(0))
        })
    })

    /** The same derivation as spike/send-to.mjs: SHA-256 of the seed. */
    private fun privateKeyFromSeed(seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun json(value: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), value)
}
