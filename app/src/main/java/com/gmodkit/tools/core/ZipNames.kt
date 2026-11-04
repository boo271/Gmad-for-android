package com.gmodkit.tools.core

/**
 * Archive entries can contain absolute paths, parent traversal, Windows
 * reserved names, or characters that are illegal on some platforms. The
 * output ZIP is meant to be opened on a phone or copied to a PC, so every
 * name is normalised before it is written.
 */
object ZipNames {

    private val invalidChars = charArrayOf('<', '>', ':', '"', '|', '?', '*')
    private val reservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitizeEntry(raw: String): String {
        var value = raw.replace('\\', '/')
        if (value.length >= 2 && value[1] == ':') {
            value = value.substring(2)
        }
        while (value.startsWith("/")) {
            value = value.substring(1)
        }

        val parts = ArrayList<String>()
        for (part in value.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") continue
            var clean = buildString(part.length) {
                for (ch in part) {
                    when {
                        ch.code < 0x20 -> append('_')
                        ch in invalidChars -> append('_')
                        else -> append(ch)
                    }
                }
            }
            clean = clean.trim()
            if (clean.isEmpty() || clean == "." || clean == "..") continue
            if (reservedNames.contains(clean.substringBefore('.').uppercase())) {
                clean = "_$clean"
            }
            parts.add(clean)
        }

        var result = parts.joinToString("/")
        if (result.isEmpty()) result = "unnamed"
        if (result.length > 1024) {
            result = result.take(1024)
        }
        return result
    }

    fun sanitizeFileName(raw: String, fallback: String = "addon"): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = buildString(name.length) {
            for (ch in name) {
                when {
                    ch.code < 0x20 -> append('_')
                    ch in invalidChars -> append('_')
                    else -> append(ch)
                }
            }
        }
        name = name.trim().trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") name = fallback
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0) {
                name.substring(0, minOf(dot, 110)) + name.substring(dot)
            } else {
                name.take(120)
            }
        }
        return name
    }
}

/** Ensures that two source entries never collapse to the same ZIP path. */
class UniqueNames {
    private val used = HashSet<String>(1024)

    fun unique(raw: String): String {
        val baseName = ZipNames.sanitizeEntry(raw)
        if (used.add(baseName)) return baseName

        val dot = baseName.lastIndexOf('.')
        val base = if (dot > 0) baseName.substring(0, dot) else baseName
        val extension = if (dot > 0) baseName.substring(dot) else ""
        var counter = 2
        while (true) {
            val candidate = "$base~$counter$extension"
            if (used.add(candidate)) return candidate
            counter++
        }
    }
}
