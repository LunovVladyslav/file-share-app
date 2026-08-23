package com.lunov.flyshare.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The receiver's obligations from docs/PROTOCOL.md §9–10.
 *
 * The happy path is covered far better by the interop run against the real Node
 * sender (`:core:receive` plus `spike/send-to.mjs`) than it could be here. What
 * these cover is what interop cannot: a sender that misbehaves. No cooperating
 * implementation will ever send a chunk that reaches past the end of a file, so
 * the check that refuses one is only ever exercised deliberately.
 */
class TransferTest {

    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = cleanup.forEach { runCatching { it.close() } }

    // ---- §10: a path from another machine is hostile until proven otherwise --

    @Test
    fun `traversal segments are dropped`() {
        assertEquals("ESCAPED.txt", SafePath.sanitise("../../ESCAPED.txt"))
        assertEquals("a/b.txt", SafePath.sanitise("a/./b.txt"))
        assertEquals("etc/passwd", SafePath.sanitise("/etc/passwd"))
    }

    @Test
    fun `both separators are split, so a windows sender cannot smuggle one`() {
        // A receiver that only split on '/' would create one file literally
        // named `..\..\escaped.txt` — harmless on Linux, and a real escape the
        // moment that name reaches a Windows machine.
        assertEquals("escaped.txt", SafePath.sanitise("..\\..\\escaped.txt"))
        assertEquals("a/b/c.txt", SafePath.sanitise("a\\b/c.txt"))
    }

    @Test
    fun `illegal characters are replaced rather than the file refused`() {
        assertEquals("a_b_c.txt", SafePath.sanitise("a<b>c.txt"))
        assertEquals("q_.txt", SafePath.sanitise("q?.txt"))
        assertEquals("tab_here.txt", SafePath.sanitise("tab\there.txt"))
    }

    @Test
    fun `windows device names and trailing dots are defused`() {
        assertEquals("_CON.txt", SafePath.sanitise("CON.txt", windowsRules = true))
        assertEquals("_com1", SafePath.sanitise("com1", windowsRules = true))
        // "report. " and "report" are the same file to Windows, so they must
        // not both be creatable under names that only differ by what it drops.
        assertEquals("report", SafePath.sanitise("report. ", windowsRules = true))
        assertEquals("CONTENTS.txt", SafePath.sanitise("CONTENTS.txt", windowsRules = true))
    }

    @Test
    fun `a path with nothing usable left is refused, not invented`() {
        assertNull(SafePath.sanitise("../.."))
        assertNull(SafePath.sanitise("/"))
        assertNull(SafePath.sanitise(""))
    }

    @Test
    fun `a hostile path lands inside the download folder`() {
        val root = tempDir()
        FileDownloadStore(root).create("../../ESCAPED.txt", 4).use { sink ->
            sink.write(0, "safe".toByteArray(), 4)
            sink.finish()
        }

        assertTrue(File(root, "ESCAPED.txt").isFile, "should have landed inside the folder")
        assertFalse(File(root.parentFile, "ESCAPED.txt").exists(), "must not have escaped")
    }

    @Test
    fun `an existing file is never overwritten silently`() {
        val root = tempDir()
        File(root, "notes.txt").writeText("the original")

        FileDownloadStore(root).create("notes.txt", 3).use {
            it.write(0, "new".toByteArray(), 3)
            it.finish()
        }

        assertEquals("the original", File(root, "notes.txt").readText())
        assertEquals("new", File(root, "notes (2).txt").readText())
    }

    @Test
    fun `files picked together get names that do not collide`() {
        // Two photos from different albums are very often both IMG_0001.jpg.
        assertEquals(
            listOf("IMG_0001.jpg", "IMG_0001 (2).jpg", "IMG_0001 (3).jpg", "note.txt"),
            SafePath.deduplicate(
                listOf("IMG_0001.jpg", "IMG_0001.jpg", "IMG_0001.jpg", "note.txt"),
            ),
        )
    }

    @Test
    fun `de-duplication does not rename what is already unique`() {
        val names = listOf("a.txt", "b/a.txt", "c.bin")
        assertEquals(names, SafePath.deduplicate(names))
    }

    @Test
    fun `renaming keeps the extension, which is what opens the file`() {
        assertEquals("photo (2).CR3", SafePath.nextFreeName("photo.CR3") { it == "photo.CR3" })
        assertEquals(".bashrc (2)", SafePath.nextFreeName(".bashrc") { it == ".bashrc" })
    }

    // ---- Parallel writes: several streams share one file ---------------------

    @Test
    fun `concurrent positional writes land in the right places`() {
        val root = tempDir()
        val block = 64 * 1024
        val blocks = 64
        val expected = ByteArray(block * blocks) { (it % 251).toByte() }

        FileDownloadStore(root).create("big.bin", expected.size.toLong()).use { sink ->
            // Sixteen threads writing interleaved blocks — the shape a real
            // multi-stream transfer produces, and what a shared file cursor
            // would silently corrupt.
            (0 until 16).map { worker ->
                thread {
                    for (b in worker until blocks step 16) {
                        val from = b * block
                        sink.write(from.toLong(), expected.copyOfRange(from, from + block), block)
                    }
                }
            }.forEach { it.join() }
            sink.finish()
        }

        assertTrue(File(root, "big.bin").readBytes().contentEquals(expected))
    }

    @Test
    fun `a file is created at its full size before any byte arrives`() {
        val root = tempDir()
        FileDownloadStore(root).create("preallocated.bin", 1_000_000).use {
            // §9.2: created at final size, so parallel streams never extend it.
            assertEquals(1_000_000, File(root, "preallocated.bin").length())
        }
    }

    // ---- §9.2–9.3: what a data connection is allowed to do ------------------

    @Test
    fun `a data connection with the wrong token is refused`() {
        val transfer = start(fileSize = 16)
        val reply = transfer.dataConnection(token = "0".repeat(32), frames = byteArrayOf())

        assertEquals("data-err", reply.type())
        assertEquals("not authorised", reply.string("reason"))
    }

    @Test
    fun `a data connection for an unknown transfer is refused`() {
        val transfer = start(fileSize = 16)
        val reply = transfer.dataConnection(
            transferId = "not-a-transfer",
            token = transfer.token,
            frames = byteArrayOf(),
        )

        assertEquals("data-err", reply.type())
    }

    @Test
    fun `an oversized chunk is refused`() {
        val transfer = start(fileSize = 10)
        val failure = transfer.sendChunk(fileIndex = 0, offset = 8, length = 8)

        assertNotNull(failure, "a chunk past the declared size must be refused")
        assertTrue(failure.contains("past the end"), failure)
        assertEquals(TransferStatus.Failed, transfer.awaitOutcome().status)
    }

    @Test
    fun `a chunk for a file that was never offered is refused`() {
        val transfer = start(fileSize = 10)
        val failure = transfer.sendChunk(fileIndex = 7, offset = 0, length = 1)

        assertNotNull(failure)
        assertTrue(failure.contains("not offered"), failure)
    }

    @Test
    fun `a negative offset cannot be used to write behind the file`() {
        val transfer = start(fileSize = 10)
        val failure = transfer.sendChunk(fileIndex = 0, offset = -4096, length = 4)

        assertNotNull(failure, "a negative offset must be refused")
    }

    @Test
    fun `a failed transfer leaves no partial file behind`() {
        val transfer = start(fileSize = 10)
        transfer.sendChunk(fileIndex = 0, offset = 8, length = 8)
        transfer.awaitOutcome()

        assertTrue(
            transfer.root.walkTopDown().none { it.isFile },
            "a partial file is worse than no file: it looks like it arrived",
        )
    }

    @Test
    fun `nothing is written when the offer is declined`() {
        val transfer = start(fileSize = 10, accept = false)
        val result = transfer.offerResult

        assertEquals(false, result.bool("accept"))
        assertNull(result.string("token"), "a declined transfer must not hand out a token")
        assertTrue(transfer.root.walkTopDown().none { it.isFile })
    }

    // ---- Fixture ------------------------------------------------------------

    private fun tempDir(): File =
        Files.createTempDirectory("flyshare-test").toFile().also { it.deleteOnExit() }

    private fun start(fileSize: Long, accept: Boolean = true): Fixture =
        Fixture(tempDir(), fileSize, accept).also { cleanup += it }

    /**
     * One transfer in progress, driven from the sender's side over pipes.
     *
     * The control connection runs on its own thread because that is what it
     * does in production — it blocks until the transfer resolves — and the data
     * connections are fed synchronously from precomputed bytes, so a failing
     * test fails immediately instead of timing out.
     */
    private class Fixture(
        val root: File,
        fileSize: Long,
        accept: Boolean,
    ) : AutoCloseable {

        private val updates = LinkedBlockingQueue<TransferProgress>()
        private val receiver = TransferReceiver(
            store = { FileDownloadStore(root) },
            ask = { accept },
            onUpdate = { updates.offer(it) },
        )

        private val control = PipeChannel()
        private val worker: Thread
        val transferId = "test-transfer"
        val offerResult: JsonObject
        val token: String

        init {
            val offer = buildJsonObject {
                put("t", JsonPrimitive("offer"))
                put("ver", JsonPrimitive(PROTOCOL_VERSION))
                put("transferId", JsonPrimitive(transferId))
                put("totalSize", JsonPrimitive(fileSize))
                put("files", buildJsonArray {
                    add(buildJsonObject {
                        put("rel", JsonPrimitive("payload.bin"))
                        put("size", JsonPrimitive(fileSize))
                    })
                })
            }

            worker = thread(name = "control") {
                runCatching { receiver.control(control.receiver, offer, PEER_ID, "Test sender") }
            }

            offerResult = Frames.read(control.sender)
            token = offerResult.string("token") ?: ""
        }

        /** Open a data connection and return the receiver's first answer. */
        fun dataConnection(
            transferId: String = this.transferId,
            token: String = this.token,
            frames: ByteArray,
        ): JsonObject {
            val request = frame("t" to "data", "transferId" to transferId, "token" to token)
            val channel = ReplayChannel(frames)
            runCatching { receiver.data(channel, request) }
            return Frames.read(ByteArrayInputStream(channel.written()))
        }

        /**
         * Send one chunk header plus its bytes. Returns the message the
         * receiver rejected it with, or null if it accepted.
         */
        fun sendChunk(fileIndex: Int, offset: Long, length: Long): String? {
            val body = ByteArrayOutputStream()
            body.write(Frames.encode(frame(
                "t" to "chunk",
                "fileIndex" to fileIndex,
                "offset" to offset,
                "length" to length,
            )))
            body.write(ByteArray(maxOf(0, length.toInt())))

            val request = frame("t" to "data", "transferId" to transferId, "token" to token)
            val channel = ReplayChannel(body.toByteArray())
            return runCatching { receiver.data(channel, request) }
                .exceptionOrNull()?.message
        }

        fun awaitOutcome(): TransferProgress {
            while (true) {
                val update = updates.poll(10, TimeUnit.SECONDS)
                    ?: error("the transfer never resolved")
                if (update.status == TransferStatus.Complete ||
                    update.status == TransferStatus.Failed ||
                    update.status == TransferStatus.Declined
                ) {
                    return update
                }
            }
        }

        override fun close() {
            control.close()
            worker.join(2_000)
            root.deleteRecursively()
        }

        private companion object { const val PEER_ID = "aaaaaaaaaaaaaaaa" }
    }

    /** Two channel ends wired together, for the connection that stays open. */
    private class PipeChannel : AutoCloseable {
        private val toReceiver = PipedOutputStream()
        private val fromReceiver = PipedOutputStream()
        private val receiverIn = PipedInputStream(toReceiver, BUFFER)
        val sender: InputStream = PipedInputStream(fromReceiver, BUFFER)

        val receiver: SecureChannel = object : SecureChannel {
            override val input: InputStream get() = receiverIn
            override val output: OutputStream get() = fromReceiver
            override val peerDeviceId = "aaaaaaaaaaaaaaaa"
            override fun readTimeout(milliseconds: Int) {}
            override fun close() {}
        }

        override fun close() {
            runCatching { toReceiver.close() }
            runCatching { fromReceiver.close() }
        }

        private companion object { const val BUFFER = 64 * 1024 }
    }

    /** A channel whose input is already decided — for one data connection. */
    private class ReplayChannel(input: ByteArray) : SecureChannel {
        private val out = ByteArrayOutputStream()
        override val input: InputStream = ByteArrayInputStream(input)
        override val output: OutputStream get() = out
        override val peerDeviceId = "aaaaaaaaaaaaaaaa"
        override fun readTimeout(milliseconds: Int) {}
        override fun close() {}
        fun written(): ByteArray = out.toByteArray()
    }
}
