package com.lunov.flyshare.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.lunov.flyshare.core.DownloadSink
import com.lunov.flyshare.core.DownloadStore
import com.lunov.flyshare.core.SafePath
import com.lunov.flyshare.core.TransferException
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Received files, written into a folder the person chose.
 *
 * A document tree rather than a path: it is the only way to write somewhere
 * the person can actually find afterwards without asking for broad storage
 * access, and it survives reboots because the permission is persisted.
 *
 * The interesting question is whether a document tree supports what a parallel
 * transfer needs — a file created at its final size, then written at arbitrary
 * offsets from several threads. It does: the descriptor behind a document is a
 * real file descriptor, so ftruncate and positional writes work on it exactly
 * as they do on a path.
 */
class AndroidDownloadStore(
    private val context: Context,
    private val tree: Uri,
) : DownloadStore {

    private val resolver = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, tree)
        ?: throw TransferException("the chosen download folder is no longer available")

    /** Directories already created for this transfer, so each is made once. */
    private val directories = mutableMapOf<String, DocumentFile>()

    override val label: String get() = describeTree(context, tree)

    @Synchronized
    override fun create(relativePath: String, size: Long): DownloadSink {
        val relative = SafePath.sanitise(relativePath)
            ?: throw TransferException("a file in this transfer has no usable name")

        val parts = relative.split('/')
        val directory = parts.dropLast(1).fold(root) { at, part -> childDirectory(at, part) }

        // Our own naming rather than the provider's, so a file that already
        // exists gets the same "name (2).ext" treatment as on the desktop.
        val name = SafePath.nextFreeName(parts.last()) { directory.findFile(it) != null }
        val document = directory.createFile(mimeTypeFor(name), name)
            ?: throw TransferException("could not create $name in the download folder")

        // A provider is free to adjust the display name — appending an
        // extension it thinks the type needs, say. Whatever it settled on is
        // what the person will look for, so that is what gets reported.
        val actual = document.name ?: name

        val descriptor = resolver.openFileDescriptor(document.uri, "rw")
            ?: throw TransferException("could not open $actual for writing")

        if (size > 0) {
            // Section 9.2: full size before the first byte, so parallel streams
            // never have to extend the file.
            runCatching { Os.ftruncate(descriptor.fileDescriptor, size) }
        }
        return DocumentSink(document, descriptor, "$label/$actual")
    }

    private fun childDirectory(parent: DocumentFile, name: String): DocumentFile {
        val key = "${parent.uri}/$name"
        directories[key]?.let { return it }

        val existing = parent.findFile(name)?.takeIf { it.isDirectory }
        val directory = existing
            ?: parent.createDirectory(name)
            ?: throw TransferException("could not create the folder $name")
        directories[key] = directory
        return directory
    }

    /**
     * A wrong type here is not cosmetic: providers append an extension when the
     * name does not match the type, turning `photo.CR3` into `photo.CR3.bin`.
     */
    private fun mimeTypeFor(name: String): String {
        val extension = SafePath.splitExtension(name).second.removePrefix(".").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private class DocumentSink(
        private val document: DocumentFile,
        private val descriptor: ParcelFileDescriptor,
        override val location: String,
    ) : DownloadSink {

        private val stream = FileOutputStream(descriptor.fileDescriptor)
        private val channel = stream.channel

        override fun write(offset: Long, data: ByteArray, length: Int) {
            // Positional, not seek-then-write: the channel's own cursor is
            // shared and several streams are inside this method at once.
            channel.write(ByteBuffer.wrap(data, 0, length), offset)
        }

        override fun finish() {
            runCatching { channel.force(true) }
            close()
        }

        override fun discard() {
            close()
            // A partial file is worse than none: it looks like it arrived.
            runCatching { document.delete() }
        }

        override fun close() {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
        }
    }
}

/**
 * The folder's name as the person would recognise it, not its content URI.
 *
 * Tree URIs read like `content://…/tree/primary%3ADownload`, which is exactly
 * the sort of thing an interface should never show anybody.
 */
fun describeTree(context: Context, tree: Uri): String {
    val name = runCatching { DocumentFile.fromTreeUri(context, tree)?.name }.getOrNull()
    if (name != null) return name

    val id = runCatching { tree.lastPathSegment }.getOrNull() ?: return "the chosen folder"
    return id.substringAfter(':').ifBlank { id }
}
