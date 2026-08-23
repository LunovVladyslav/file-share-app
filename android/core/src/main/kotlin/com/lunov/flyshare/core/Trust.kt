package com.lunov.flyshare.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive

/**
 * Somewhere to keep a handful of small files. The platform supplies it, so this
 * module stays free of Android imports and the tests can hand it a map.
 */
interface Storage {
    fun read(name: String): String?
    fun write(name: String, content: String)
}

/** For tests and the JVM probe. */
class MemoryStorage : Storage {
    private val files = mutableMapOf<String, String>()
    override fun read(name: String): String? = files[name]
    override fun write(name: String, content: String) { files[name] = content }
}

/** A device this one has paired with, and the key that proves it. */
data class PairedPeer(
    val id: String,
    val name: String,
    val os: String,
    val publicKey: String,
    val pairedAt: Long,
)

/**
 * This device's long-term identity — docs/PROTOCOL.md §3.
 *
 * Generated once, never rotated. Losing it invalidates every pairing, which is
 * meant to be a visible failure rather than a silent one.
 */
class Identity(private val storage: Storage) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val state: JsonObject by lazy {
        storage.read(FILE)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: mint()
    }

    val privateKey: ByteArray get() = state["privateKey"]!!.jsonPrimitive.content.fromBase64Url()
    val publicKey: ByteArray get() = Crypto.publicKeyOf(privateKey)
    val publicKeyString: String get() = publicKey.toBase64Url()
    val deviceId: String get() = state["deviceId"]!!.jsonPrimitive.content

    private fun mint(): JsonObject {
        val (privateKey, _) = Crypto.newKeyPair()
        val fresh = buildJsonObject {
            put("privateKey", JsonPrimitive(privateKey.toBase64Url()))
            put("deviceId", JsonPrimitive(newDeviceId()))
        }
        storage.write(FILE, json.encodeToString(JsonObject.serializer(), fresh))
        return fresh
    }

    private companion object { const val FILE = "identity.json" }
}

/**
 * Pinned peer keys, in the same shape as the desktop's `peers.json` — which
 * makes a cross-platform problem readable by eye instead of needing a tool.
 */
class TrustStore(private val storage: Storage) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun load(): MutableMap<String, PairedPeer> {
        val raw = storage.read(FILE) ?: return mutableMapOf()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return mutableMapOf()
        return parsed.entries.mapNotNull { (id, element) ->
            val entry = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val key = entry["publicKey"]?.jsonPrimitive?.content ?: return@mapNotNull null
            id to PairedPeer(
                id = id,
                name = entry["name"]?.jsonPrimitive?.content ?: id,
                os = entry["os"]?.jsonPrimitive?.content ?: "unknown",
                publicKey = key,
                pairedAt = entry["pairedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }.toMap().toMutableMap()
    }

    private fun save(peers: Map<String, PairedPeer>) {
        val out = buildJsonObject {
            for ((id, peer) in peers) {
                put(id, buildJsonObject {
                    put("name", JsonPrimitive(peer.name))
                    put("os", JsonPrimitive(peer.os))
                    put("publicKey", JsonPrimitive(peer.publicKey))
                    put("pairedAt", JsonPrimitive(peer.pairedAt))
                })
            }
        }
        storage.write(FILE, json.encodeToString(JsonObject.serializer(), out))
    }

    @Synchronized
    fun all(): List<PairedPeer> = load().values.sortedByDescending { it.pairedAt }

    @Synchronized
    fun publicKeyOf(deviceId: String): ByteArray? =
        load()[deviceId]?.publicKey?.runCatching { fromBase64Url() }?.getOrNull()

    @Synchronized
    fun isPaired(deviceId: String): Boolean = load().containsKey(deviceId)

    @Synchronized
    fun remember(peer: PairedPeer) {
        val peers = load()
        peers[peer.id] = peer
        save(peers)
    }

    @Synchronized
    fun forget(deviceId: String): Boolean {
        val peers = load()
        val removed = peers.remove(deviceId) != null
        if (removed) save(peers)
        return removed
    }

    private companion object { const val FILE = "peers.json" }
}
