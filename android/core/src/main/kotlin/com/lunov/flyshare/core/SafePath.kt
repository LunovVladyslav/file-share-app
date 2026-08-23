package com.lunov.flyshare.core

/**
 * Turning a sender's `rel` into something safe to create — docs/PROTOCOL.md §10.
 *
 * The path arrives from another machine and is treated as hostile. A manifest
 * naming `../../ESCAPED.txt` must land inside the download folder, not beside
 * it, and the containment check at the end is what actually guarantees that:
 * the rules above it are cleanup, not security.
 */
object SafePath {

    private val ILLEGAL = Regex("""[<>:"|?*\u0000-\u001f]""")
    private val RESERVED = Regex(
        """^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val onWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * A relative path with forward slashes, safe to join onto a download
     * directory. Returns null when nothing usable survives — a `rel` of
     * `../..` has no filename in it at all, and inventing one would be worse
     * than refusing the file.
     */
    fun sanitise(rel: String, windowsRules: Boolean = onWindows): String? {
        val segments = rel.split('/', '\\')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { segment(it, windowsRules) }
            .filter { it.isNotEmpty() }

        return if (segments.isEmpty()) null else segments.joinToString("/")
    }

    private fun segment(raw: String, windowsRules: Boolean): String {
        var name = ILLEGAL.replace(raw, "_")
        if (windowsRules) {
            // A trailing dot or space is silently dropped by the Windows API,
            // so "report. " and "report" would collide without this.
            name = name.trimEnd('.', ' ')
            if (RESERVED.matches(name)) name = "_$name"
        }
        return name
    }

    /** `photo.CR3` → (`photo`, `.CR3`); a dotfile keeps its leading dot. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /**
     * `report.pdf` → `report (2).pdf`, matching what the desktop does. Nothing
     * is ever overwritten silently: a file the person already has is not a
     * detail the sender gets to decide about.
     */
    fun nextFreeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val (stem, extension) = splitExtension(name)
        var n = 2
        while (taken("$stem ($n)$extension")) n++
        return "$stem ($n)$extension"
    }
}
