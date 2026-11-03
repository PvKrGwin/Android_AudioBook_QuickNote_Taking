package com.praveen.ridernotes.media

import android.content.Context
import java.util.Locale

/**
 * Stores links shared explicitly from YouTube/YouTube Music.
 *
 * Links are keyed conservatively by source app + title + channel/artist. An
 * autoplay title change therefore cannot inherit the preceding video's link.
 */
object SharedMediaLinkStore {
    private const val PREFS_NAME = "shared_media_links"

    fun save(
        context: Context,
        packageName: String,
        title: String,
        author: String?,
        sourceUrl: String
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(identityKey(packageName, title, author), sourceUrl)
            .apply()
    }

    fun find(
        context: Context,
        packageName: String,
        title: String?,
        author: String?
    ): String? {
        val usableTitle = title?.takeIf { it.isNotBlank() } ?: return null
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(identityKey(packageName, usableTitle, author), null)
    }

    internal fun identityKey(packageName: String, title: String, author: String?): String =
        listOf(packageName, normalize(title), normalize(author.orEmpty())).joinToString("\u001F")

    private fun normalize(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
}
