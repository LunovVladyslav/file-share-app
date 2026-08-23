package com.lunov.flyshare.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * One file being written. Several data connections hold the same sink at once
 * and write at different offsets, so [write] must be safe to call concurrently
 * and must not depend on any shared cursor.
 */
interface DownloadSink : Closeable {
    /** Where it actually landed, for the person to read. */
    val location: String

    fun write(offset: Long, data: ByteArray, length: Int)

    /** Everything is in; flush to durable storage. */
    fun finish()

    /** The transfer failed — remove the partial file. */
    fun discard()
}

/**
 * Where received files go. The core does not know whether that is a directory,
 * a document tree, or a test's temporary folder.
 */
interface DownloadStore {
    /** What to show the person: "Downloads/FlyShare", say. */
    val label: String

    /**
     * Create a file of exactly [size] bytes, ready to be written at any offset.
     *
     * Creating it full-size up front is what lets parallel streams write
     * wherever they like without extending the file, which on a slow phone
     * filesystem is the difference between one allocation and thousands.
     */
    fun create(relativePath: String, size: Long): DownloadSink
}

/**
 * A plain directory. Used by the tests and the JVM probe, and by the phone
 * whenever the person has not chosen a folder of their own.
 */
class FileDownloadStore(private val root: File) : DownloadStore {

    override val label: String get() = root.absolutePath

    override fun create(relativePath: String, size: Long): DownloadSink {
        val relative = SafePath.sanitise(relativePath)
            ?: throw TransferException("a file in this transfer has no usable name")

        val target = resolveInside(relative)
        target.parentFile?.mkdirs()

        val handle = RandomAccessFile(target, "rw")
        // Ask for the whole length in one go. Sparse where the filesystem
        // supports it, a real allocation where it does not; either way the
        // parallel writes that follow never have to grow the file.
        if (size > 0) handle.setLength(size)
        return FileSink(target, handle)
    }

    /**
     * §10.5: the joined path must still be inside the download directory.
     * The segment rules above should already guarantee it — this is the check
     * that makes it true rather than likely.
     */
    private fun resolveInside(relative: String): File {
        val root = this.root.canonicalFile
        val parts = relative.split('/')
        val directory = parts.dropLast(1).fold(root) { at, part -> File(at, part) }

        val chosen = SafePath.nextFreeName(parts.last()) { File(directory, it).exists() }
        val target = File(directory, chosen)

        val resolved = target.canonicalFile
        if (resolved != root && !resolved.toPath().startsWith(root.toPath())) {
            throw TransferException("refused a path that pointed outside the download folder")
        }
        return target
    }

    private class FileSink(private val file: File, private val handle: RandomAccessFile) : DownloadSink {
        override val location: String get() = file.absolutePath

        override fun write(offset: Long, data: ByteArray, length: Int) {
            // Positional, not seek-then-write: the channel's own cursor is
            // shared and several streams are inside this method at once.
            handle.channel.write(ByteBuffer.wrap(data, 0, length), offset)
        }

        override fun finish() {
            runCatching { handle.channel.force(true) }
            handle.close()
        }

        override fun discard() {
            runCatching { handle.close() }
            runCatching { file.delete() }
        }

        override fun close() { runCatching { handle.close() } }
    }
}

class TransferException(message: String) : Exception(message)
