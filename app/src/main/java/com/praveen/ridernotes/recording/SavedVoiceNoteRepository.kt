package com.praveen.ridernotes.recording

import android.content.Context
import com.praveen.ridernotes.media.YouTubeSourceLink
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SavedVoiceNote(
    val audioFile: File,
    val recordedAtEpochMs: Long,
    val title: String,
    val sourceApp: String,
    val module: String?,
    val chapter: String?,
    val author: String?,
    val playbackPositionMs: Long?,
    val sourceUrl: String?,
    val transcriptionStatus: String,
    val transcript: String?,
    val transcriptSegments: List<TranscriptSegment>,
    val topicOnly: Boolean = false
) {
    val recordedAtLabel: String
        get() = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
            .format(Date(recordedAtEpochMs))

    val playbackPositionLabel: String?
        get() = playbackPositionMs?.takeIf { it >= 0L }?.let { value ->
            val totalSeconds = value / 1_000L
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

    val sourceUrlAtPlaybackPosition: String?
        get() = YouTubeSourceLink.atPosition(sourceUrl, playbackPositionMs)

    val sourceSearchUrl: String?
        get() = YouTubeSourceLink.searchUrl(sourceApp, title)
}

object SavedVoiceNoteRepository {
    fun load(context: Context, includeTopicOnly: Boolean = false): List<SavedVoiceNote> {
        val folder = File(context.filesDir, "voice_notes")
        if (!folder.exists()) return emptyList()

        return folder.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .map { audioFile -> loadOne(audioFile) }
            .filter { includeTopicOnly || !it.topicOnly }
            .sortedByDescending { it.recordedAtEpochMs }
    }

    fun delete(note: SavedVoiceNote): Boolean {
        val parent = note.audioFile.parentFile ?: return false
        val baseName = note.audioFile.nameWithoutExtension
        val companionExtensions = listOf("json", "txt", "vtt", "srt")

        companionExtensions.forEach { extension ->
            File(parent, "$baseName.$extension").delete()
        }
        return note.audioFile.delete() || !note.audioFile.exists()
    }

    /** Find a previously verified link for the same YouTube title and channel. */
    fun findReusableSourceUrl(
        context: Context,
        sourceApp: String,
        title: String?,
        author: String?
    ): String? {
        if (!YouTubeSourceLink.isSupportedSourceApp(sourceApp) || title.isNullOrBlank()) {
            return null
        }
        return load(context)
            .asSequence()
            .filter { sameMediaIdentity(it, sourceApp, title, author) }
            .mapNotNull { note ->
                note.sourceUrl?.let { YouTubeSourceLink.canonicalUrl(sourceApp, it) }
            }
            .firstOrNull()
    }

    /**
     * Apply one validated base URL to every recording with the same service,
     * title and channel. Each note retains its own playback position.
     */
    fun applySourceUrlToMatchingNotes(
        context: Context,
        sourceApp: String,
        title: String?,
        author: String?,
        sourceUrl: String
    ): Int {
        if (title.isNullOrBlank()) return 0
        val canonical = YouTubeSourceLink.canonicalUrl(sourceApp, sourceUrl) ?: return 0
        var updated = 0
        load(context)
            .filter { sameMediaIdentity(it, sourceApp, title, author) }
            .forEach { note ->
                if (note.sourceUrl != canonical && writeSourceUrl(note, canonical)) {
                    updated += 1
                }
            }
        return updated
    }

    /** Backfill imported and existing note groups whenever one member has a link. */
    fun synchronizeYouTubeLinks(context: Context): Int {
        val notes = load(context).filter {
            YouTubeSourceLink.isSupportedSourceApp(it.sourceApp)
        }
        val knownLinks = linkedMapOf<String, String>()
        notes.forEach { note ->
            val canonical = note.sourceUrl?.let {
                YouTubeSourceLink.canonicalUrl(note.sourceApp, it)
            } ?: return@forEach
            val key = mediaIdentityKey(note.sourceApp, note.title, note.author)
            if (key !in knownLinks) knownLinks[key] = canonical
        }

        var updated = 0
        notes.forEach { note ->
            val canonical = knownLinks[
                mediaIdentityKey(note.sourceApp, note.title, note.author)
            ] ?: return@forEach
            if (note.sourceUrl != canonical && writeSourceUrl(note, canonical)) {
                updated += 1
            }
        }
        return updated
    }

    private fun sameMediaIdentity(
        note: SavedVoiceNote,
        sourceApp: String,
        title: String,
        author: String?
    ): Boolean =
        mediaIdentityKey(note.sourceApp, note.title, note.author) ==
            mediaIdentityKey(sourceApp, title, author)

    private fun mediaIdentityKey(sourceApp: String, title: String, author: String?): String =
        listOf(sourceApp, title, author.orEmpty())
            .joinToString("\u001F") { value ->
                value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            }

    private fun writeSourceUrl(note: SavedVoiceNote, canonicalUrl: String): Boolean =
        runCatching {
            val metadataFile = File(
                note.audioFile.parentFile,
                note.audioFile.nameWithoutExtension + ".json"
            )
            if (!metadataFile.exists()) return@runCatching false
            val json = JSONObject(metadataFile.readText())
            json.put("sourceUrl", canonicalUrl)
            metadataFile.writeText(json.toString(2))
            true
        }.getOrDefault(false)

    private fun loadOne(audioFile: File): SavedVoiceNote {
        val metadataFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".json")
        val fallbackTime = audioFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

        if (!metadataFile.exists()) {
            return SavedVoiceNote(
                audioFile = audioFile,
                recordedAtEpochMs = fallbackTime,
                title = audioFile.nameWithoutExtension,
                sourceApp = "Unknown source",
                module = null,
                chapter = null,
                author = null,
                playbackPositionMs = null,
                sourceUrl = null,
                transcriptionStatus = "NOT_STARTED",
                transcript = null,
                transcriptSegments = emptyList()
            )
        }

        return try {
            val json = JSONObject(metadataFile.readText())
            val metadataVersion = json.optInt("metadataVersion", 1)
            val sourceApp = json.optNullableString("sourceApp") ?: "Standalone"
            var title = json.optNullableString("title") ?: audioFile.nameWithoutExtension
            var chapter = json.optNullableString("subtitleOrChapter")

            // Correct recordings made before app-specific metadata mapping was added.
            if (metadataVersion < 2 && sourceApp == "Google Play Books") {
                val oldTitle = title
                title = chapter ?: oldTitle
                chapter = oldTitle.takeUnless { it.equals(title, ignoreCase = true) }
            } else if (metadataVersion < 2 && sourceApp == "Audible") {
                // The old generic mapper stored Audible's author as the chapter.
                chapter = null
            }

            SavedVoiceNote(
                audioFile = audioFile,
                recordedAtEpochMs = json.optLong("recordedAtEpochMs", fallbackTime),
                title = title,
                sourceApp = sourceApp,
                module = json.optNullableString("module"),
                chapter = chapter,
                author = json.optNullableString("author"),
                playbackPositionMs = json.optNullableLong("playbackPositionMs"),
                sourceUrl = json.optNullableString("sourceUrl"),
                transcriptionStatus = json.optNullableString("transcriptionStatus") ?: "NOT_STARTED",
                transcript = json.optNullableString("transcript"),
                transcriptSegments = json.optJSONArray("transcriptSegments")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val text = item.optString("text").trim()
                            val start = item.optLong("startMs", -1L)
                            val end = item.optLong("endMs", -1L)
                            if (text.isNotBlank() && start >= 0L && end > start) {
                                add(TranscriptSegment(start, end, text))
                            }
                        }
                    }
                }.orEmpty(),
                topicOnly = json.optBoolean("topicOnly", false)
            )
        } catch (_: Exception) {
            SavedVoiceNote(
                audioFile = audioFile,
                recordedAtEpochMs = fallbackTime,
                title = audioFile.nameWithoutExtension,
                sourceApp = "Metadata unavailable",
                module = null,
                chapter = null,
                author = null,
                playbackPositionMs = null,
                sourceUrl = null,
                transcriptionStatus = "UNKNOWN",
                transcript = null,
                transcriptSegments = emptyList()
            )
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null
}
