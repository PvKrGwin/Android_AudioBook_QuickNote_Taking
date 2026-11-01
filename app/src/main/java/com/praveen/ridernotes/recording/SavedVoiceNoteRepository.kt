package com.praveen.ridernotes.recording

import android.content.Context
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
    val chapter: String?,
    val author: String?,
    val playbackPositionMs: Long?,
    val transcriptionStatus: String,
    val transcript: String?,
    val transcriptSegments: List<TranscriptSegment>
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
}

object SavedVoiceNoteRepository {
    fun load(context: Context): List<SavedVoiceNote> {
        val folder = File(context.filesDir, "voice_notes")
        if (!folder.exists()) return emptyList()

        return folder.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .map { audioFile -> loadOne(audioFile) }
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

    private fun loadOne(audioFile: File): SavedVoiceNote {
        val metadataFile = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".json")
        val fallbackTime = audioFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

        if (!metadataFile.exists()) {
            return SavedVoiceNote(
                audioFile = audioFile,
                recordedAtEpochMs = fallbackTime,
                title = audioFile.nameWithoutExtension,
                sourceApp = "Unknown source",
                chapter = null,
                author = null,
                playbackPositionMs = null,
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
                chapter = chapter,
                author = json.optNullableString("author"),
                playbackPositionMs = json.optNullableLong("playbackPositionMs"),
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
                }.orEmpty()
            )
        } catch (_: Exception) {
            SavedVoiceNote(
                audioFile = audioFile,
                recordedAtEpochMs = fallbackTime,
                title = audioFile.nameWithoutExtension,
                sourceApp = "Metadata unavailable",
                chapter = null,
                author = null,
                playbackPositionMs = null,
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
