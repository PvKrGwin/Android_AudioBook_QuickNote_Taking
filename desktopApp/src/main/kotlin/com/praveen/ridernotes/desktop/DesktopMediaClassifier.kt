package com.praveen.ridernotes.desktop

import java.util.Locale

/** Resolves browser-owned media sessions back to their real Rider Notes source. */
object DesktopMediaClassifier {
    fun resolve(media: CapturedMedia, existingNotes: List<DesktopNote>): CapturedMedia {
        if (!isGenericBrowserSource(media.sourceApp)) return media
        val titleKey = key(media.title)
        if (titleKey.isBlank()) return media

        val matches = existingNotes.filter {
            !isGenericBrowserSource(it.sourceApp) && key(it.title) == titleKey
        }
        if (matches.isEmpty()) return media

        // Prefer the source/title spelling used most often in the existing library.
        // This makes a PC note join the same heading and subgroup immediately.
        val match = matches.groupBy { key(it.sourceApp) to key(it.title) }
            .maxByOrNull { it.value.size }
            ?.value
            ?.maxByOrNull { it.recordedAtEpochMs }
            ?: return media

        return media.copy(sourceApp = match.sourceApp, title = match.title)
    }

    internal fun isGenericBrowserSource(source: String): Boolean {
        val value = source.trim().lowercase(Locale.ROOT)
        return value.isBlank() || value == "windows media" ||
            value == "chrome media" || value == "edge media" || value == "firefox media" ||
            value == "chrome" || value == "msedge" || value == "edge" || value == "firefox" ||
            value.contains("google chrome") || value.contains("microsoft edge")
    }

    private fun key(value: String): String = value.lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
