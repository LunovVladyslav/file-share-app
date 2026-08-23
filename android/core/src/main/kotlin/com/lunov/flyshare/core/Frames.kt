package com.lunov.flyshare.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed JSON frames — docs/PROTOCOL.md §5.
 *
 *     [4-byte big-endian length][UTF-8 JSON]
 */
object Frames {

    const val MAX_FRAME = 4 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: JsonObject): ByteArray {
        val body = json.encodeToString(JsonObject.serializer(), message).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_FRAME) { "frame body of ${body.size} exceeds the 4 MiB limit" }
        return byteArrayOf(
            (body.size ushr 24).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        ) + body
    }

    fun write(out: OutputStream, message: JsonObject) {
        out.write(encode(message))
        out.flush()
    }

    /**
     * Read exactly one frame. Throws rather than returning null on a malformed
     * stream: at this layer there is no way to resynchronise, and continuing
     * would read payload bytes as though they were a header.
     */
    fun read(input: InputStream): JsonObject {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (_: EOFException) {
            throw FrameException("connection closed while waiting for a frame")
        }
        if (length < 0 || length > MAX_FRAME) throw FrameException("frame too large: $length")

        val body = ByteArray(length)
        data.readFully(body)
        return try {
            json.parseToJsonElement(body.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw FrameException("frame body was not a JSON object: ${e.message}")
        }
    }
}

class FrameException(message: String) : Exception(message)

// Small helpers so the protocol code reads like the specification does.

fun frame(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            null -> {}
            is String -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
            is Int -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
            is Long -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
            is Boolean -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
            is JsonElement -> put(key, value)
            else -> error("unsupported frame value for '$key': ${value::class}")
        }
    }
}

fun JsonObject.type(): String? = this["t"]?.jsonPrimitive?.contentOrNull()

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull()

fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.content?.toIntOrNull()

fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.content?.let { it == "true" }

fun JsonObject.obj(key: String): JsonObject? = this[key]?.let {
    runCatching { it.jsonObject }.getOrNull()
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
