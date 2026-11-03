package com.praveen.ridernotes.media

import java.net.URLEncoder

/**
 * Extracts a stable public YouTube link from media-session metadata.
 *
 * YouTube does not promise to publish a URL in its Android MediaSession, so
 * callers must treat a missing result as normal. We deliberately never build
 * a link from the title because that could open the wrong video.
 */
object YouTubeSourceLink {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

    private val supportedPackages = setOf(YOUTUBE_PACKAGE, YOUTUBE_MUSIC_PACKAGE)
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{6,20}$")
    private val youtubeUrlPattern = Regex(
        pattern = """(?i)(?:https?://)?(?:www\.)?(?:music\.)?(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?[^\s#]*?v=|shorts/|embed/)([A-Za-z0-9_-]{6,20})|(?:https?://)?(?:www\.)?youtu\.be/([A-Za-z0-9_-]{6,20})"""
    )
    private val appSchemePattern = Regex(
        pattern = """(?i)(?:vnd\.youtube|youtube|ytmusic):(?://)?(?:watch\?v=)?([A-Za-z0-9_-]{6,20})"""
    )

    fun fromMetadata(
        packageName: String,
        metadataValues: List<Pair<String, String>>
    ): String? {
        if (packageName !in supportedPackages) return null

        val idFromUrl = metadataValues.asSequence()
            .mapNotNull { (_, value) -> videoIdFromUrl(value) }
            .firstOrNull()
        val idFromMediaId = metadataValues.asSequence()
            .filter { (key, _) -> key.isMediaIdKey() }
            .mapNotNull { (_, value) -> videoIdFromMediaId(value) }
            .firstOrNull()
        val videoId = idFromUrl ?: idFromMediaId ?: return null
        val host = if (packageName == YOUTUBE_MUSIC_PACKAGE) {
            "music.youtube.com"
        } else {
            "www.youtube.com"
        }
        return "https://$host/watch?v=$videoId"
    }

    fun atPosition(sourceUrl: String?, playbackPositionMs: Long?): String? {
        val url = sourceUrl?.takeIf { videoIdFromUrl(it) != null } ?: return null
        val position = playbackPositionMs?.takeIf { it >= 0L } ?: return url
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}t=${position / 1_000L}s"
    }

    fun searchUrl(sourceApp: String, title: String): String? {
        val path = when {
            sourceApp.equals("YouTube", ignoreCase = true) ->
                "https://www.youtube.com/results?search_query="
            sourceApp.equals("YouTube Music", ignoreCase = true) ->
                "https://music.youtube.com/search?q="
            else -> return null
        }
        val query = title.trim().takeIf { it.isNotBlank() } ?: return null
        return path + URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    private fun videoIdFromUrl(value: String): String? {
        val match = youtubeUrlPattern.find(value)
        return match?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?: appSchemePattern.find(value)?.groupValues?.getOrNull(1)
    }

    private fun videoIdFromMediaId(value: String): String? {
        videoIdFromUrl(value)?.let { return it }
        return value.trim().takeIf { videoIdPattern.matches(it) }
    }

    private fun String.isMediaIdKey(): Boolean {
        val normalized = lowercase().replace('-', '_')
        return normalized.endsWith("media_id") ||
            normalized.endsWith("mediaid") ||
            normalized.endsWith("video_id") ||
            normalized.endsWith("videoid")
    }
}
