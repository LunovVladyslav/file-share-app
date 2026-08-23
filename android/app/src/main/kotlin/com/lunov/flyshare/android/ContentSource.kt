package com.lunov.flyshare.android

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.lunov.flyshare.core.SafePath
import com.lunov.flyshare.core.SourceHandle
import com.lunov.flyshare.core.TransferException
import com.lunov.flyshare.core.TransferSource
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * A file the person picked, or shared into the app, as something several
 * streams can read at once.
 *
 * Content URIs are not all alike. A document from the file system opens onto a
 * real descriptor that can be read at any offset; some providers hand back a
 * pipe instead, which can only be read once, forwards. Parallel streams need
 * the first kind, so anything else is staged to the cache first — slower, but
 * it happens rather than failing.
 */
class ContentSource private constructor(
    private val context: Context,
    private val uri: Uri,
    override val rel: String,
    override val size: Long,
    private val seekable: Boolean,
) : TransferSource {

    override fun open(): SourceHandle =
        if (seekable) DescriptorHandle(context, uri) else StagedHandle(context, uri)

    companion object {
        /**
         * Describe what was picked. Names come from the provider, then go
         * through the same sanitising the receiving side applies — a display
         * name is chosen by whichever app exported the file, so it is no more
         * trustworthy here than a path arriving over the network.
         */
        fun of(context: Context, uri: Uri, folder: String? = null): ContentSource? {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L

            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor: Cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                        }
                    }
            }

            val descriptor = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                // A silent null here means the person taps send and nothing at
                // all happens, which is the worst way to fail.
                android.util.Log.w("FlyShare", "cannot open $uri: ${e::class.simpleName}: ${e.message}")
                null
            } ?: return null
            val statSize = descriptor.statSize
            // A negative stat size means there is no file behind this — a pipe,
            // or a socket — so it cannot be read at an offset.
            val seekable = statSize >= 0
            if (size < 0) size = statSize
            descriptor.close()

            if (size < 0) {
                android.util.Log.w("FlyShare", "no size for $uri")
                return null
            }

            val chosen = SafePath.sanitise(name ?: uri.lastPathSegment ?: "file")
                ?: return null
            val rel = if (folder.isNullOrBlank()) chosen else "$folder/$chosen"
            return ContentSource(context.applicationContext, uri, rel, size, seekable)
        }

        /**
         * Give a set of picked files distinct names.
         *
         * Two pictures from different albums are often both `IMG_0001.jpg`, and
         * the receiver would otherwise store the second as `IMG_0001 (2).jpg`
         * — correct, but it looks like it went wrong.
         */
        fun distinct(sources: List<ContentSource>): List<TransferSource> =
            SafePath.deduplicate(sources.map { it.rel })
                .zip(sources) { name, source ->
                    if (name == source.rel) source else Renamed(source, name)
                }
    }

    private class Renamed(
        private val source: ContentSource,
        override val rel: String,
    ) : TransferSource {
        override val size: Long get() = source.size
        override fun open(): SourceHandle = source.open()
    }

    /** The good case: a real descriptor, read at whatever offset is asked for. */
    private class DescriptorHandle(context: Context, uri: Uri) : SourceHandle {
        private val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw TransferException("could not open a file that was picked")
        private val stream = FileInputStream(descriptor.fileDescriptor)
        private val channel = stream.channel

        override fun read(offset: Long, into: ByteArray, length: Int): Int =
            channel.read(ByteBuffer.wrap(into, 0, length), offset)

        override fun close() {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
        }
    }

    /**
     * The awkward case: copy it out once, then read the copy freely. The cache
     * directory is the right home — the system may reclaim it, and by then the
     * transfer is long over.
     */
    private class StagedHandle(context: Context, uri: Uri) : SourceHandle {
        private val staged: File = File.createTempFile("flyshare-", ".tmp", context.cacheDir).apply {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            } ?: throw TransferException("could not read a file that was shared")
        }
        private val handle = java.io.RandomAccessFile(staged, "r")

        override fun read(offset: Long, into: ByteArray, length: Int): Int =
            handle.channel.read(ByteBuffer.wrap(into, 0, length), offset)

        override fun close() {
            runCatching { handle.close() }
            runCatching { staged.delete() }
        }
    }
}
