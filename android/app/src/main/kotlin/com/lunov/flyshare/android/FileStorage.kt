package com.lunov.flyshare.android

import android.content.Context
import com.lunov.flyshare.core.Storage
import java.io.File

/**
 * The core's small files, kept in app-private storage.
 *
 * That directory is already isolated from other apps, which is what the private
 * key needs. Keystore-backed keys would be a further step, but they are only
 * available from API 33 and the app supports 26 — so this is the floor, not a
 * shortcut around one.
 */
class FileStorage(context: Context) : Storage {

    private val directory: File = File(context.applicationContext.filesDir, "flyshare")
        .apply { mkdirs() }

    override fun read(name: String): String? {
        val file = File(directory, name)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    override fun write(name: String, content: String) {
        // Write beside, then rename: a process killed mid-write must not leave
        // a truncated identity behind, which would look like every pairing
        // having silently expired.
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            target.writeText(content)
            temporary.delete()
        }
    }
}
