package com.lunov.flyshare.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * One file about to be sent.
 *
 * [rel] uses forward slashes on every platform — docs/PROTOCOL.md §9.1 — so a
 * phone and a Windows desktop agree on the layout.
 */
interface TransferSource {
    val rel: String
    val size: Long

    /** A handle that can be read at any offset, by several threads at once. */
    fun open(): SourceHandle
}

interface SourceHandle : Closeable {
    /** Read [length] bytes starting at [offset]; returns how many were read. */
    fun read(offset: Long, into: ByteArray, length: Int): Int
}

/** A file on disk. Used by the tests and the JVM probe. */
class FileSource(private val file: File, override val rel: String) : TransferSource {
    override val size: Long get() = file.length()

    override fun open(): SourceHandle {
        val handle = RandomAccessFile(file, "r")
        return object : SourceHandle {
            override fun read(offset: Long, into: ByteArray, length: Int): Int =
                handle.channel.read(ByteBuffer.wrap(into, 0, length), offset)

            override fun close() { runCatching { handle.close() } }
        }
    }

    companion object {
        /**
         * Everything under [root], named relative to it — so sending a folder
         * arrives as that folder rather than as a heap of loose files.
         */
        fun tree(root: File): List<TransferSource> {
            val base = root.absoluteFile
            if (base.isFile) return listOf(FileSource(base, base.name))
            return base.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    val rel = base.name + "/" + file.relativeTo(base).path.replace(File.separatorChar, '/')
                    FileSource(file, rel)
                }
                .toList()
        }
    }
}

enum class SendStatus { Connecting, Waiting, Sending, Paused, Complete, Declined, Failed }

data class SendProgress(
    val transferId: String,
    val peerName: String,
    val fileCount: Int,
    val sent: Long,
    val totalSize: Long,
    val status: SendStatus,
    val detail: String? = null,
    val security: String? = null,
    /** When bytes started moving, or 0 before that. */
    val startedAt: Long = 0,
    val files: List<FileProgress> = emptyList(),
) {
    val fraction: Float get() = if (totalSize <= 0) 1f else (sent.toDouble() / totalSize).toFloat()
}

/**
 * The sending half of a transfer — docs/PROTOCOL.md §9.
 *
 * The work is cut into chunks up front and the connections pull from one queue,
 * so a slow stream simply takes fewer of them. Handing each connection a fixed
 * share instead would make every transfer as slow as its unluckiest stream.
 */
class TransferSender(
    private val self: SelfDescription,
    private val identity: Identity,
    private val trust: TrustStore,
    private val streams: Int = DEFAULT_STREAMS,
) {

    /** Cancels the transfer in progress, if there is one. */
    @Volatile private var cancelled: String? = null

    /** Held while paused; the workers park on it between chunks. */
    private val gate = Object()
    @Volatile private var paused = false

    /** Only offered when the receiver said it understands §9.5. */
    @Volatile var canPause: Boolean = false
        private set

    fun cancel(reason: String = "cancelled") {
        cancelled = reason
        resume() // so a paused worker wakes up to notice
    }

    fun pause() {
        if (!canPause) return
        paused = true
    }

    fun resume() {
        synchronized(gate) {
            paused = false
            gate.notifyAll()
        }
    }

    fun send(
        peer: Peer,
        files: List<TransferSource>,
        onProgress: (SendProgress) -> Unit = {},
    ): SendProgress {
        require(files.isNotEmpty()) { "nothing to send" }
        cancelled = null
        paused = false
        sentSoFar.set(0)

        val transferId = UUID.randomUUID().toString()
        val totalSize = files.sumOf { it.size }
        val streamCount = streams.coerceIn(1, MAX_STREAMS)

        // Set once, when bytes actually start moving. Counting from the offer
        // would fold in however long the person took to accept, which is not
        // the transfer's time.
        var startedAt = 0L
        val perFile = AtomicLongArray(files.size)

        fun fileProgress(): List<FileProgress> = files.mapIndexed { index, file ->
            FileProgress(file.rel, file.size, perFile.get(index))
        }

        fun report(status: SendStatus, sent: Long = 0, detail: String? = null, security: String? = null) =
            SendProgress(
                transferId, peer.name, files.size, sent, totalSize,
                status, detail, security, startedAt, fileProgress(),
            ).also(onProgress)

        report(SendStatus.Connecting)

        val control = try {
            SecureSession.connect(peer, self, identity, trust)
        } catch (e: Exception) {
            return report(SendStatus.Failed, detail = e.message ?: "could not connect")
        }

        control.use { channel ->
            Frames.write(channel.output, offerFrame(transferId, files, totalSize, streamCount))
            report(SendStatus.Waiting, security = channel.description)

            // A person on the other end has to agree, and may be walking to
            // their desk; the connection stays open meanwhile.
            channel.readTimeout(DECISION_TIMEOUT_MS)
            val answer = try {
                Frames.read(channel.input)
            } catch (e: Exception) {
                return report(SendStatus.Failed, detail = e.message ?: "no answer from the other device")
            }

            if (answer.type() != "offer-result" || answer.bool("accept") != true) {
                return report(
                    SendStatus.Declined,
                    detail = answer.string("reason") ?: "the other device declined",
                )
            }
            val token = answer.string("token")
                ?: return report(SendStatus.Failed, detail = "the other device sent no token")
            canPause = answer.bool("canPause") == true
            paused = false

            startedAt = System.currentTimeMillis()
            report(SendStatus.Sending, security = channel.description)

            // The workers park between chunks; this is what tells the receiver
            // to stop counting the silence as a stall, and what wakes them.
            var announced = false
            val announcer = thread(name = "flyshare-pause", isDaemon = true) {
                // Interrupting a sleeping thread throws; that is how it stops,
                // not something to report.
                runCatching {
                while (!Thread.currentThread().isInterrupted) {
                    val now = paused
                    if (now != announced) {
                        announced = now
                        runCatching {
                            Frames.write(channel.output, frame("t" to if (now) "pause" else "resume"))
                        }
                        report(
                            if (now) SendStatus.Paused else SendStatus.Sending,
                            sentSoFar.get(),
                            security = channel.description,
                        )
                    }
                    Thread.sleep(PAUSE_POLL_MS)
                }
                }
            }

            val sent = sentSoFar
            val failure = AtomicReference<String?>(null)
            val work = ConcurrentLinkedQueue(plan(files))

            // Empty files were created during preallocation and carry no chunk,
            // so a transfer of nothing but empty files has no work at all.
            val workers = (1..minOf(streamCount, maxOf(1, work.size))).map { index ->
                thread(name = "flyshare-send-$index") {
                    runCatching { pump(peer, transferId, token, files, work, sent, perFile, ::report) }
                        .onFailure { failure.compareAndSet(null, it.message ?: "a data connection failed") }
                }
            }
            workers.forEach { it.join() }
            announcer.interrupt()

            cancelled?.let { reason ->
                runCatching { Frames.write(channel.output, frame("t" to "cancel", "reason" to reason)) }
                return report(SendStatus.Failed, sent.get(), reason)
            }
            failure.get()?.let { reason ->
                runCatching { Frames.write(channel.output, frame("t" to "cancel", "reason" to reason)) }
                return report(SendStatus.Failed, sent.get(), reason)
            }

            // Every byte is out; the receiver confirms once it has them all.
            channel.readTimeout(CONFIRM_TIMEOUT_MS)
            val confirmation = try {
                Frames.read(channel.input)
            } catch (e: Exception) {
                return report(SendStatus.Failed, sent.get(), e.message ?: "the receiver never confirmed")
            }

            return when (confirmation.type()) {
                "done" -> report(SendStatus.Complete, sent.get(), security = channel.description)
                else -> report(
                    SendStatus.Failed,
                    sent.get(),
                    confirmation.string("reason") ?: "the receiver reported a problem",
                )
            }
        }
    }


    /**
     * Park while paused. Called only between chunks, which is what makes a
     * pause free: nothing is half-delivered, so nothing is re-sent and the
     * receiver counts no byte twice.
     */
    private fun awaitResume() {
        synchronized(gate) {
            while (paused && cancelled == null) {
                runCatching { gate.wait(PAUSE_POLL_MS) }
            }
        }
    }

    /** One data connection, taking chunks until the queue is empty. */
    private fun pump(
        peer: Peer,
        transferId: String,
        token: String,
        files: List<TransferSource>,
        work: ConcurrentLinkedQueue<Chunk>,
        sent: AtomicLong,
        perFile: AtomicLongArray,
        report: (SendStatus, Long, String?, String?) -> SendProgress,
    ) {
        if (work.peek() == null) return

        SecureSession.connect(peer, self, identity, trust).use { data ->
            Frames.write(data.output, frame("t" to "data", "transferId" to transferId, "token" to token))
            data.readTimeout(STEP_TIMEOUT_MS)
            val reply = Frames.read(data.input)
            if (reply.type() != "data-ok") {
                throw TransferException(reply.string("reason") ?: "the data connection was refused")
            }

            val buffer = ByteArray(READ_BUFFER)
            val open = HashMap<Int, SourceHandle>()
            try {
                while (true) {
                    if (cancelled != null) return
                    awaitResume()
                    if (cancelled != null) return
                    val chunk = work.poll() ?: break
                    val source = files[chunk.fileIndex]
                    val handle = open.getOrPut(chunk.fileIndex) { source.open() }

                    Frames.write(data.output, frame(
                        "t" to "chunk",
                        "fileIndex" to chunk.fileIndex,
                        "offset" to chunk.offset,
                        "length" to chunk.length,
                    ))

                    var written = 0L
                    while (written < chunk.length) {
                        val want = minOf(buffer.size.toLong(), chunk.length - written).toInt()
                        val read = handle.read(chunk.offset + written, buffer, want)
                        if (read <= 0) throw TransferException("${source.rel} ended sooner than declared")
                        data.output.write(buffer, 0, read)
                        written += read
                        perFile.addAndGet(chunk.fileIndex, read.toLong())
                        report(SendStatus.Sending, sent.addAndGet(read.toLong()), null, null)
                    }
                }
                Frames.write(data.output, frame("t" to "end"))
                // Let the receiver close first, so our close is a clean
                // shutdown rather than a reset on top of its close_notify.
                data.readTimeout(CLOSE_DRAIN_MS)
                runCatching { while (data.input.read() >= 0) { /* discard */ } }
            } finally {
                open.values.forEach { it.close() }
            }
        }
    }

    private fun offerFrame(
        transferId: String,
        files: List<TransferSource>,
        totalSize: Long,
        streamCount: Int,
    ): JsonObject = buildJsonObject {
        put("t", JsonPrimitive("offer"))
        put("ver", JsonPrimitive(PROTOCOL_VERSION))
        put("transferId", JsonPrimitive(transferId))
        put("from", buildJsonObject {
            put("id", JsonPrimitive(self.id))
            put("name", JsonPrimitive(self.name))
            put("os", JsonPrimitive(self.os))
        })
        put("files", fileArray(files))
        put("totalSize", JsonPrimitive(totalSize))
        put("streams", JsonPrimitive(streamCount))
    }

    private fun fileArray(files: List<TransferSource>): JsonArray = buildJsonArray {
        for (file in files) {
            add(buildJsonObject {
                put("rel", JsonPrimitive(file.rel))
                put("size", JsonPrimitive(file.size))
            })
        }
    }

    /**
     * Cut the work up. Large files are split so one huge file still uses every
     * stream; small ones go whole, which keeps a thousand-file folder from
     * turning into a thousand tiny reads spread across four connections.
     */
    private fun plan(files: List<TransferSource>): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        files.forEachIndexed { index, file ->
            var offset = 0L
            // Size 0 gets no chunk at all — §9.3. The receiver created it.
            while (offset < file.size) {
                val length = minOf(CHUNK_SIZE, file.size - offset)
                chunks += Chunk(index, offset, length)
                offset += length
            }
        }
        return chunks
    }

    private data class Chunk(val fileIndex: Int, val offset: Long, val length: Long)

    /** Shared with the announcer thread so a paused card still shows progress. */
    private val sentSoFar = AtomicLong()

    private companion object {
        const val PAUSE_POLL_MS = 200L
        const val DEFAULT_STREAMS = 4
        const val MAX_STREAMS = 16

        /** Matches the desktop, so a mixed pair splits work the same way. */
        const val CHUNK_SIZE = 32L * 1024 * 1024

        const val READ_BUFFER = 256 * 1024
        const val STEP_TIMEOUT_MS = 20_000
        const val DECISION_TIMEOUT_MS = 5 * 60_000
        const val CONFIRM_TIMEOUT_MS = 2 * 60_000
        const val CLOSE_DRAIN_MS = 5_000
    }
}
