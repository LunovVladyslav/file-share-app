package com.lunov.flyshare.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.lunov.flyshare.core.DownloadStore
import com.lunov.flyshare.core.FileDownloadStore
import java.io.File

/**
 * Where received files go, and how that choice survives a restart.
 *
 * There is a working default so that a new install can receive something
 * before anyone visits a settings screen. It is app-private external storage,
 * which needs no permission but is awkward to browse to — so the interface
 * says where files landed and offers to change it.
 */
class DownloadFolder(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val fallback: File
        get() = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads").apply { mkdirs() }

    /** The document tree the person chose, if it is still usable. */
    val tree: Uri?
        get() {
            val stored = preferences.getString(KEY, null)?.let(Uri::parse) ?: return null
            // A folder can be removed, or an SD card ejected, long after it was
            // picked. Losing the grant silently would mean transfers failing
            // with nothing on screen explaining why.
            val granted = context.contentResolver.persistedUriPermissions
                .any { it.uri == stored && it.isWritePermission }
            return if (granted) stored else null
        }

    /**
     * Remember a folder the person picked, and keep the grant across restarts.
     */
    fun remember(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.edit().putString(KEY, uri.toString()).apply()
    }

    /**
     * Built fresh for each transfer, so a folder chosen a moment ago is used
     * by the next one without anything having to be rewired.
     */
    fun store(): DownloadStore =
        tree?.let { AndroidDownloadStore(context, it) } ?: FileDownloadStore(fallback)

    /**
     * The chosen folder's name, or null when the built-in default is in use.
     *
     * Null rather than a ready-made sentence: the default has to be described
     * in the language the screen is currently drawn in, and only the screen
     * knows that.
     */
    fun treeLabel(): String? = tree?.let { describeTree(context, it) }

    private companion object {
        const val PREFERENCES = "flyshare"
        const val KEY = "downloadTree"
    }
}
