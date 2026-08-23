package com.lunov.flyshare.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Send a folder to the real Node receiver, from a plain JVM.
 *
 *     ./gradlew :core:send -PsendDir=... [-PsendPort=45889] [-PsendHost=127.0.0.1]
 *
 * The counterpart is `spike/receive-at.mjs`, and the mirror of what
 * `:core:receive` does for the other direction. Testing the sender against the
 * implementation it has to interoperate with — on a desktop, where a failure
 * can be read — is what makes the phone step about the phone rather than about
 * the protocol.
 */
object SendProbe {

    private const val NODE_SEED = "flyshare-interop-node"
    private const val KOTLIN_SEED = "flyshare-interop-kotlin"
    private const val NODE_DEVICE_ID = "1111111111111111"
    private const val KOTLIN_DEVICE_ID = "2222222222222222"

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args.getOrNull(0) ?: "build/to-send").absoluteFile
        val host = args.getOrNull(1) ?: "127.0.0.1"
        val port = args.getOrNull(2)?.toIntOrNull() ?: TRANSFER_PORT

        if (!directory.exists()) {
            println("nothing at ${directory.path}")
            kotlin.system.exitProcess(2)
        }

        val storage = MemoryStorage()
        storage.write("identity.json", seededIdentity(KOTLIN_SEED, KOTLIN_DEVICE_ID))
        storage.write("peers.json", pinned(NODE_DEVICE_ID, NODE_SEED))

        val identity = Identity(storage)
        val trust = TrustStore(storage)
        val self = SelfDescription(id = identity.deviceId, name = "Kotlin sender", os = "android")

        val peer = Peer(
            id = NODE_DEVICE_ID,
            name = "Node interop receiver",
            os = "windows",
            address = host,
            port = port,
            version = PROTOCOL_VERSION,
            lastSeen = System.currentTimeMillis(),
        )

        val files = FileSource.tree(directory)
        println("sending ${files.size} file(s), ${files.sumOf { it.size }} bytes to $host:$port")

        var lastPercent = -1
        var startedAt = 0L

        val outcome = TransferSender(self, identity, trust).send(peer, files) { progress ->
            when (progress.status) {
                SendStatus.Sending -> {
                    if (startedAt == 0L) startedAt = System.currentTimeMillis()
                    val percent = (progress.fraction * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        print("\r  $percent%   ")
                        System.out.flush()
                    }
                }
                SendStatus.Waiting -> println("waiting for the other device to accept…")
                else -> {}
            }
        }

        print("\r")
        val seconds = (System.currentTimeMillis() - startedAt).coerceAtLeast(1) / 1000.0
        println("${outcome.status}: ${outcome.sent} of ${outcome.totalSize} bytes")
        outcome.detail?.let { println("  $it") }
        outcome.security?.let { println("  $it") }
        if (outcome.status == SendStatus.Complete && startedAt > 0) {
            println("  %.2f s, %.1f MB/s".format(Locale.ROOT, seconds, outcome.totalSize / seconds / 1e6))
        }

        // What was sent, so the receiver's digests can be compared to it.
        files.sortedBy { it.rel }.forEach { source ->
            println("  ${digest(source)}  ${source.size.toString().padStart(12)}  ${source.rel}")
        }

        kotlin.system.exitProcess(if (outcome.status == SendStatus.Complete) 0 else 1)
    }

    private fun digest(source: TransferSource): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        source.open().use { handle ->
            var offset = 0L
            while (offset < source.size) {
                val want = minOf(buffer.size.toLong(), source.size - offset).toInt()
                val read = handle.read(offset, buffer, want)
                if (read <= 0) break
                md.update(buffer, 0, read)
                offset += read
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun seededIdentity(seed: String, deviceId: String): String = json(buildJsonObject {
        put("privateKey", JsonPrimitive(privateKeyFromSeed(seed).toBase64Url()))
        put("deviceId", JsonPrimitive(deviceId))
    })

    private fun pinned(deviceId: String, seed: String): String = json(buildJsonObject {
        put(deviceId, buildJsonObject {
            put("name", JsonPrimitive("Node interop receiver"))
            put("os", JsonPrimitive("windows"))
            put("publicKey", JsonPrimitive(Crypto.publicKeyOf(privateKeyFromSeed(seed)).toBase64Url()))
            put("pairedAt", JsonPrimitive(0))
        })
    })

    private fun privateKeyFromSeed(seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))

    private fun json(value: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), value)
}
