package com.lunov.flyshare.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** A file the sender says it is about to send. */
data class IncomingFile(val rel: String, val size: Long)

/** An offer, as it should be described to a person. */
data class IncomingOffer(
    val transferId: String,
    val peerId: String,
    val peerName: String,
    val files: List<IncomingFile>,
    val totalSize: Long,
)

enum class TransferStatus { Offered, Receiving, Complete, Declined, Failed }

/** Where one file is up to. */
enum class FileState { Waiting, Moving, Done }

/**
 * One file inside a transfer.
 *
 * Carried per update rather than fetched on demand: a person who opens a
 * transfer wants to watch it, and a list that has to be polled separately
 * always lags the bar above it by however long the poll took.
 */
data class FileProgress(
    val rel: String,
    val size: Long,
    val transferred: Long,
    /** Where it landed, once there is somewhere to point at. */
    val location: String? = null,
) {
    val state: FileState get() = when {
        size == 0L || transferred >= size -> FileState.Done
        transferred > 0 -> FileState.Moving
        else -> FileState.Waiting
    }

    val fraction: Float get() = if (size <= 0) 1f else (transferred.toDouble() / size).toFloat()
}

data class TransferProgress(
    val transferId: String,
    val peerName: String,
    val fileCount: Int,
    val received: Long,
    val totalSize: Long,
    val status: TransferStatus,
    val detail: String? = null,
    val savedTo: String? = null,
    /** When bytes started moving, or 0 before that. */
    val startedAt: Long = 0,
    val files: List<FileProgress> = emptyList(),
) {
    val fraction: Float get() = if (totalSize <= 0) 1f else (received.toDouble() / totalSize).toFloat()
}

/**
 * The receiving half of a transfer — docs/PROTOCOL.md §9.
 *
 * One control connection settles what is coming and whether it is wanted; the
 * data connections carry bytes and nothing else. Splitting them is what lets
 * several streams run at once without any of them having to agree on whose
 * turn it is to speak.
 */
class TransferReceiver(
    private val store: () -> DownloadStore,
    private val ask: (IncomingOffer) -> Boolean,
    private val onUpdate: (TransferProgress) -> Unit = {},
) {

    private val active = ConcurrentHashMap<String, Active>()

    /**
     * Handle a control connection whose first frame inside TLS was an offer.
     *
     * [peerId] is the id proved by the handshake. Section 9.1 is explicit that
     * the sender's own `from.id` is display data and must not be trusted: a
     * peer can write anything there, but it cannot forge the key.
     */
    fun control(
        connection: SecureChannel,
        offer: JsonObject,
        peerId: String,
        peerName: String,
    ) {
        val transferId = offer.string("transferId")
            ?: throw TransferException("offer without a transfer id")

        val files = (offer["files"] as? JsonArray).orEmpty().map { element ->
            val entry = element.jsonObject
            IncomingFile(
                rel = entry["rel"]?.jsonPrimitive?.content
                    ?: throw TransferException("a file in the offer has no path"),
                size = entry["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
        if (files.isEmpty()) throw TransferException("offer with no files")

        val declared = offer["totalSize"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: files.sumOf { it.size }
        val incoming = IncomingOffer(transferId, peerId, peerName, files, declared)

        onUpdate(TransferProgress(
            transferId, peerName, files.size, 0, declared, TransferStatus.Offered,
            files = files.map { FileProgress(it.rel, it.size, 0) },
        ))

        // Nothing touches storage before the answer — section 9.2.
        if (!ask(incoming)) {
            Frames.write(connection.output, frame(
                "t" to "offer-result", "accept" to false, "reason" to "declined by user",
            ))
            onUpdate(TransferProgress(transferId, peerName, files.size, 0, declared, TransferStatus.Declined))
            return
        }

        val token = Crypto.randomBytes(16).joinToString("") { "%02x".format(it) }
        val transfer = Active(transferId, token, peerName, declared)

        try {
            val target = store()
            // Create every file before accepting, so a full disk is refused now
            // rather than three quarters of the way through.
            transfer.files = files
            transfer.sinks = files.map { target.create(it.rel, it.size) }
            transfer.perFile = AtomicLongArray(files.size)
        } catch (e: Exception) {
            transfer.closeAll(discard = true)
            Frames.write(connection.output, frame(
                "t" to "offer-result", "accept" to false,
                "reason" to (e.message ?: "could not create the files"),
            ))
            onUpdate(TransferProgress(
                transferId, peerName, files.size, 0, declared, TransferStatus.Failed, e.message,
            ))
            return
        }

        active[transferId] = transfer
        Frames.write(connection.output, frame(
            "t" to "offer-result",
            "accept" to true,
            "token" to token,
            // Section 9.5: saying this is an undertaking to understand the
            // pause and resume frames. A sender must not send them otherwise.
            "canPause" to true,
        ))
        // From acceptance, not from the offer: the wait for a person to decide
        // is not the transfer's time.
        transfer.startedAt = System.currentTimeMillis()
        onUpdate(TransferProgress(
            transferId, peerName, files.size, 0, declared, TransferStatus.Receiving,
            startedAt = transfer.startedAt, files = transfer.fileProgress(),
        ))

        try {
            connection.readTimeout(0) // idle for the whole transfer; only EOF ends it
            transfer.watcher = watchForCancel(connection, transfer)

            // Every file empty: no data connection is coming at all.
            if (declared == 0L) transfer.settle("empty offer", SUCCESS)

            val outcome = transfer.signals.take()
            if (outcome == SUCCESS) {
                transfer.sinks.forEach { it.finish() }
                Frames.write(connection.output, frame("t" to "done", "transferId" to transferId))
                // Let the sender read that and hang up first. Closing on top of
                // it arrives as a reset, and a sender that has just succeeded
                // should not be told the connection was lost.
                transfer.watcher?.join(CLOSE_GRACE_MS)
                onUpdate(TransferProgress(
                    transferId, peerName, files.size, transfer.received.get(), declared,
                    TransferStatus.Complete, savedTo = store().label,
                    startedAt = transfer.startedAt, files = transfer.fileProgress(),
                ))
            } else {
                transfer.closeAll(discard = true)
                runCatching {
                    Frames.write(connection.output, frame("t" to "error", "reason" to outcome))
                }
                onUpdate(TransferProgress(
                    transferId, peerName, files.size, transfer.received.get(), declared,
                    TransferStatus.Failed, outcome, startedAt = transfer.startedAt,
                    files = transfer.fileProgress(),
                ))
            }
        } finally {
            transfer.finished.set(true)
            active.remove(transferId)
        }
    }

    /** Handle a data connection whose first frame inside TLS was a data request. */
    fun data(connection: SecureChannel, request: JsonObject) {
        val transfer = active[request.string("transferId")]
        val token = request.string("token")

        // Constant-time, and only once the transfer is known: a wrong token is
        // the one thing here that an attacker could retry cheaply.
        if (transfer == null || token == null ||
            !Crypto.equalsConstantTime(token.toByteArray(), transfer.token.toByteArray())
        ) {
            Frames.write(connection.output, frame("t" to "data-err", "reason" to "not authorised"))
            return
        }

        Frames.write(connection.output, frame("t" to "data-ok"))
        connection.readTimeout(STALL_TIMEOUT_MS)

        val input = DataInputStream(connection.input)
        val buffer = ByteArray(CHUNK_BUFFER)
        val label = Thread.currentThread().name
        if (Active.TRACE) println("[trace] $label: data connection open")

        try {
            while (true) {
                val message = readTolerantOfPause(input, transfer) ?: return
                when (message.type()) {
                    "chunk" -> receiveChunk(message, input, buffer, transfer)
                    "end" -> {
                        drainUntilPeerCloses(connection)
                        if (Active.TRACE) println("[trace] $label: end, closed cleanly")
                        return
                    }
                    else -> throw TransferException(
                        "unexpected frame on a data connection: ${message.type()}",
                    )
                }
            }
        } catch (e: Exception) {
            if (Active.TRACE) println("[trace] $label: ${e::class.simpleName}: ${e.message}")
            if (!transfer.finished.get()) {
                transfer.settle("data connection", e.message ?: "a data connection failed")
            }
            throw e
        }
    }

    /**
     * Wait for the sender to hang up before closing this end.
     *
     * The sender's own close_notify is still in flight when its last `end`
     * arrives. Closing a socket that has unread bytes waiting makes the kernel
     * send a reset rather than a clean shutdown, and the sender reports that
     * as a lost connection — on a transfer that had in fact succeeded. Reading
     * to the end of the stream costs a few milliseconds and removes the whole
     * class of phantom failure.
     */
    private fun drainUntilPeerCloses(connection: SecureChannel) {
        runCatching {
            connection.readTimeout(CLOSE_DRAIN_MS)
            val scratch = ByteArray(1024)
            while (connection.input.read(scratch) >= 0) { /* discard */ }
        }
    }


    /**
     * Read the next frame, waiting out a pause rather than calling it a stall.
     *
     * The idle timeout exists to notice a sender that has died. A sender that
     * has paused looks identical from here, which is why §9.5 makes it say so
     * on the control connection. A timeout can only land between frames — the
     * sender is required to pause between chunks — so nothing is half-read.
     */
    private fun readTolerantOfPause(input: DataInputStream, transfer: Active): JsonObject? {
        while (true) {
            try {
                return Frames.read(input)
            } catch (e: java.net.SocketTimeoutException) {
                if (transfer.finished.get()) return null
                if (!transfer.paused.get()) throw e
            }
        }
    }

    private fun receiveChunk(
        message: JsonObject,
        input: DataInputStream,
        buffer: ByteArray,
        transfer: Active,
    ) {
        val index = message.int("fileIndex") ?: throw TransferException("chunk without a file index")
        val offset = message.long("offset") ?: throw TransferException("chunk without an offset")
        val length = message.long("length") ?: throw TransferException("chunk without a length")

        val file = transfer.files.getOrNull(index)
            ?: throw TransferException("chunk for a file that was not offered")
        // Section 9.3: a chunk may not reach past the size the sender declared.
        if (offset < 0 || length < 0 || offset + length > file.size) {
            throw TransferException("chunk would write past the end of ${file.rel}")
        }

        val sink = transfer.sinks[index]
        var written = 0L
        while (written < length) {
            val want = minOf(buffer.size.toLong(), length - written).toInt()
            input.readFully(buffer, 0, want)
            sink.write(offset + written, buffer, want)
            written += want
            transfer.perFile.addAndGet(index, want.toLong())
            transfer.report(transfer.received.addAndGet(want.toLong()), onUpdate)
        }

        if (transfer.received.get() >= transfer.totalSize) transfer.settle("last chunk", SUCCESS)
    }

    /**
     * The control connection stays silent during a transfer, so a read on it
     * only returns for a reason: an explicit cancel, or the sender going away.
     */
    private fun watchForCancel(connection: SecureChannel, transfer: Active): Thread {
        val watcher = Thread({
            val reason = runCatching {
                var outcome: String? = null
                while (outcome == null) {
                    val message = Frames.read(connection.input)
                    outcome = when (message.type()) {
                        "pause" -> { transfer.paused.set(true); null }
                        "resume" -> { transfer.paused.set(false); null }
                        "cancel" -> message.string("reason") ?: "the sender cancelled the transfer"
                        else -> "unexpected frame during the transfer: ${message.type()}"
                    }
                }
                outcome
            }.getOrElse { "the sender disconnected" }

            if (!transfer.finished.get()) transfer.settle("control connection", reason)
        }, "flyshare-control-${transfer.id}")
        watcher.isDaemon = true
        watcher.start()
        return watcher
    }

    internal class Active(
        val id: String,
        val token: String,
        val peerName: String,
        val totalSize: Long,
    ) {
        var files: List<IncomingFile> = emptyList()
        var sinks: List<DownloadSink> = emptyList()
        var watcher: Thread? = null
        var startedAt: Long = 0

        /** Bytes written per file, indexed the same as [files]. */
        var perFile: AtomicLongArray = AtomicLongArray(0)

        /** A snapshot for the interface, built where the counters live. */
        fun fileProgress(): List<FileProgress> = files.mapIndexed { index, file ->
            FileProgress(
                rel = file.rel,
                size = file.size,
                transferred = if (index < perFile.length()) perFile.get(index) else 0,
                location = sinks.getOrNull(index)?.location,
            )
        }

        /** Set by the control connection; read by every data connection. */
        val paused = AtomicBoolean(false)

        companion object {
            val TRACE: Boolean = System.getenv("FLYSHARE_TRACE") != null
        }

        val received = AtomicLong()
        val finished = AtomicBoolean(false)

        /** The outcome: [SUCCESS], or a sentence saying what went wrong. */
        val signals = LinkedBlockingQueue<String>(8)

        /**
         * Record an outcome. Only the first one counts; the rest are the
         * wreckage it caused, and reporting those instead of the cause is how
         * a transfer failure ends up describing the wrong thing.
         */
        fun settle(source: String, outcome: String) {
            if (TRACE) {
                println("[trace] $source: ${outcome.ifEmpty { "complete" }} " +
                    "at ${received.get()} of $totalSize")
            }
            signals.offer(outcome)
        }

        private val lastReport = AtomicLong()

        /**
         * Progress is reported on a timer rather than once per chunk. At full
         * speed this is reached thousands of times a second, and a UI that
         * recomposes that often is slower than the transfer it describes.
         */
        fun report(total: Long, onUpdate: (TransferProgress) -> Unit) {
            val now = System.currentTimeMillis()
            val previous = lastReport.get()
            if (now - previous < PROGRESS_INTERVAL_MS) return
            if (!lastReport.compareAndSet(previous, now)) return
            onUpdate(TransferProgress(
                id, peerName, files.size, total, totalSize, TransferStatus.Receiving,
                startedAt = startedAt, files = fileProgress(),
            ))
        }

        fun closeAll(discard: Boolean) {
            sinks.forEach { if (discard) it.discard() else it.close() }
        }
    }

    private companion object {
        /** Not a reason, so it cannot collide with one. */
        const val SUCCESS = ""

        /** Big enough to keep writes cheap, small enough not to strain a phone. */
        const val CHUNK_BUFFER = 256 * 1024

        /** A data connection silent for this long has stalled. */
        const val STALL_TIMEOUT_MS = 60_000

        /** Long enough for a close_notify already on the wire to arrive. */
        const val CLOSE_DRAIN_MS = 5_000

        /** How long to let the sender close first once it has been told. */
        const val CLOSE_GRACE_MS = 5_000L

        const val PROGRESS_INTERVAL_MS = 100L


    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.content?.toLongOrNull()
