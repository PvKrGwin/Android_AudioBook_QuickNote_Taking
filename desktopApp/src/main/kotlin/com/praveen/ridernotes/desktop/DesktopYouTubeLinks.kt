package com.praveen.ridernotes.desktop

import java.util.Locale
import java.net.URI

internal object DesktopYouTubeLinks {
    private val youtubeUrlPattern = Regex(
        """(?i)(?:https?://)?(?:www\.)?(?:music\.)?(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?[^\s#]*?v=|shorts/|embed/)([A-Za-z0-9_-]{6,20})|(?:https?://)?(?:www\.)?youtu\.be/([A-Za-z0-9_-]{6,20})"""
    )

    fun isSupportedSource(sourceApp: String): Boolean =
        sourceApp.equals("YouTube", ignoreCase = true) ||
            sourceApp.equals("YouTube Music", ignoreCase = true)

    fun canonicalUrl(sourceApp: String, value: String?): String? {
        if (!isSupportedSource(sourceApp) || value.isNullOrBlank()) return null
        val videoId = parseVideoId(value) ?: return null
        val host = if (sourceApp.equals("YouTube Music", ignoreCase = true)) {
            "music.youtube.com"
        } else {
            "www.youtube.com"
        }
        return "https://$host/watch?v=$videoId"
    }

    private fun parseVideoId(value: String): String? {
        val trimmed = value.trim()
        val uri = runCatching {
            URI(if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed")
        }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT).orEmpty().removePrefix("www.")
        val candidate = when {
            host == "youtu.be" -> uri?.path?.trim('/')?.substringBefore('/')
            host == "youtube.com" || host == "music.youtube.com" || host == "youtube-nocookie.com" -> {
                val segments = uri?.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
                when (segments.firstOrNull()?.lowercase(Locale.ROOT)) {
                    "shorts", "embed", "live" -> segments.getOrNull(1)
                    else -> uri?.rawQuery.orEmpty().split('&')
                        .firstOrNull { it.substringBefore('=') == "v" }
                        ?.substringAfter('=', "")
                }
            }
            else -> null
        }
        return candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
            ?: youtubeUrlPattern.find(trimmed)
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull(String::isNotBlank)
    }

    fun atPosition(sourceApp: String, sourceUrl: String?, positionMs: Long?): String? {
        val base = canonicalUrl(sourceApp, sourceUrl) ?: sourceUrl?.takeIf(String::isNotBlank)
            ?: return null
        if (!isSupportedSource(sourceApp)) return base
        val seconds = positionMs?.takeIf { it >= 0L }?.div(1_000L) ?: return base
        return "$base&t=${seconds}s"
    }

    fun sameIdentity(note: DesktopNote, media: CapturedMedia): Boolean =
        identityKey(note.sourceApp, note.title, note.author) ==
            identityKey(media.sourceApp, media.title, media.author)

    private fun identityKey(sourceApp: String, title: String, channel: String?): String =
        listOf(sourceApp, title, channel.orEmpty()).joinToString("\u001F") {
            it.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        }
}
