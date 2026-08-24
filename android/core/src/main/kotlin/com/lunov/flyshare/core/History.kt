package com.lunov.flyshare.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a transfer ended. */
enum class Outcome { Complete, Failed, Declined }

/**
 * One finished transfer, as it is worth remembering.
 *
 * Summary only — no file list. A thousand-file transfer would put a megabyte
 * of names into storage for something nobody reads afterwards, and the folder
 * it landed in is a better answer to "what did I get" than a list the app
 * copied out of it. While a transfer is running the full per-file view is
 * live; once it is over, the destination is the record.
 */
data class HistoryEntry(
    val id: String,
    val outgoing: Boolean,
    val peerName: String,
    val fileCount: Int,
    val totalSize: Long,
    val transferred: Long,
    val outcome: Outcome,
    val detail: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /** Where received files landed; null for a transfer that went out. */
    val destination: String? = null,
) {
    val seconds: Double
        get() = if (startedAt > 0 && finishedAt > startedAt) (finishedAt - startedAt) / 1000.0 else 0.0

    /** Bytes per second over the whole transfer, or null if it never ran. */
    val averageRate: Double? get() = seconds.takeIf { it > 0 }?.let { transferred / it }
}

/**
 * The last few transfers, kept across restarts.
 *
 * Capped rather than unbounded: this is a record for a person to glance at,
 * not an audit log, and a file that grows forever is a bug that takes a year
 * to show up.
 */
class History(private val storage: Storage, private val limit: Int = 50) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /**
     * Record a finished transfer. Repeats of one already recorded replace it,
     * because a state machine can settle more than once on its way down.
     */
    @Synchronized
    fun record(entry: HistoryEntry) {
        val kept = (listOf(entry) + _entries.value.filterNot { it.id == entry.id }).take(limit)
        _entries.value = kept
        save(kept)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<HistoryEntry> {
        val raw = storage.read(FILE) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                HistoryEntry(
                    id = o.text("id") ?: return@runCatching null,
                    outgoing = o.text("outgoing") == "true",
                    peerName = o.text("peerName") ?: "",
                    fileCount = o.text("fileCount")?.toIntOrNull() ?: 0,
                    totalSize = o.text("totalSize")?.toLongOrNull() ?: 0,
                    transferred = o.text("transferred")?.toLongOrNull() ?: 0,
                    outcome = runCatching { Outcome.valueOf(o.text("outcome") ?: "") }
                        .getOrDefault(Outcome.Failed),
                    detail = o.text("detail"),
                    startedAt = o.text("startedAt")?.toLongOrNull() ?: 0,
                    finishedAt = o.text("finishedAt")?.toLongOrNull() ?: 0,
                    destination = o.text("destination"),
                )
            }.getOrNull()
        }
    }

    private fun save(entries: List<HistoryEntry>) {
        val array: JsonArray = buildJsonArray {
            for (e in entries) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(e.id))
                    put("outgoing", JsonPrimitive(e.outgoing))
                    put("peerName", JsonPrimitive(e.peerName))
                    put("fileCount", JsonPrimitive(e.fileCount))
                    put("totalSize", JsonPrimitive(e.totalSize))
                    put("transferred", JsonPrimitive(e.transferred))
                    put("outcome", JsonPrimitive(e.outcome.name))
                    e.detail?.let { put("detail", JsonPrimitive(it)) }
                    put("startedAt", JsonPrimitive(e.startedAt))
                    put("finishedAt", JsonPrimitive(e.finishedAt))
                    e.destination?.let { put("destination", JsonPrimitive(it)) }
                })
            }
        }
        runCatching { storage.write(FILE, json.encodeToString(JsonArray.serializer(), array)) }
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }

    private companion object { const val FILE = "history.json" }
}
