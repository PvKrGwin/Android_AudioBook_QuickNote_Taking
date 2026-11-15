package com.praveen.ridernotes.desktop

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

object BackupImporter {
    fun import(zipFile: File): BackupSnapshot {
        require(zipFile.isFile) { "Backup ZIP was not found" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = zipFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val root = File(
            File(System.getProperty("user.home"), "RiderNotesPC/imports"),
            "${safeName}_${timestamp}_${UUID.randomUUID().toString().take(8)}"
        ).apply { mkdirs() }
        extractSafely(zipFile, root)

        return readExtracted(zipFile, root)
    }

    fun reload(snapshot: BackupSnapshot): BackupSnapshot =
        readExtracted(snapshot.sourceZip, snapshot.extractedRoot)

    private fun readExtracted(zipFile: File, root: File): BackupSnapshot {

        val libraryFile = root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "library.json" }
            ?: error("This ZIP does not contain the Rider Notes Topics library")
        val libraryRoot = libraryFile.parentFile
        val library = JSONObject(libraryFile.readText())

        val allFiles = root.walkTopDown().filter(File::isFile).toList()
        val filesByName = allFiles.groupBy { it.name }
        val notes = allFiles
            .asSequence()
            .filter { it.extension.equals("json", true) && it != libraryFile }
            .mapNotNull { parseNote(it, filesByName) }
            .distinctBy { it.id }
            .sortedByDescending { it.recordedAtEpochMs }
            .toList()

        return BackupSnapshot(
            sourceZip = zipFile,
            extractedRoot = root,
            libraryRoot = libraryRoot,
            notes = notes,
            groups = library.optJSONArray("groups").objects().map {
                DesktopTopicGroup(
                    id = it.getString("id"),
                    name = it.optString("name", "Untitled group"),
                    parentId = it.nullable("parentId"),
                    order = it.optInt("order")
                )
            },
            items = library.optJSONArray("items").objects().map {
                DesktopTopicItem(
                    id = it.getString("id"),
                    type = it.optString("type", "TEXT"),
                    title = it.optString("title", "Untitled"),
                    text = it.nullable("text"),
                    target = it.nullable("target"),
                    createdAt = it.optLong("createdAt")
                )
            },
            placements = library.optJSONArray("placements").objects().map {
                DesktopTopicPlacement(
                    id = it.getString("id"),
                    groupId = it.getString("groupId"),
                    itemId = it.getString("itemId"),
                    order = it.optInt("order"),
                    important = it.optBoolean("important"),
                    color = it.optString("color", "WHITE")
                )
            },
            colorLegend = library.optJSONObject("colorLegend")?.let { legend ->
                legend.keys().asSequence().associateWith { legend.optString(it) }
                    .filterValues(String::isNotBlank)
            }.orEmpty(),
            syncManifest = root.walkTopDown()
                .firstOrNull { it.isFile && it.name == SyncManifestCodec.FILE_NAME }
                ?.let(SyncManifestCodec::read),
            sourceZipLastModified = zipFile.lastModified(),
            sourceZipLength = zipFile.length()
        )
    }

    private fun parseNote(
        metadataFile: File,
        filesByName: Map<String, List<File>>
    ): DesktopNote? = runCatching {
        val json = JSONObject(metadataFile.readText())
        if (!json.has("recordedAtEpochMs") && !json.has("sourceApp")) return null
        val base = metadataFile.nameWithoutExtension
        val transcriptFile = filesByName["$base.txt"]?.firstOrNull()
        val transcriptText = transcriptFile?.readText()
        DesktopNote(
            id = base,
            title = json.nullable("title") ?: base,
            sourceApp = json.nullable("sourceApp") ?: "Standalone",
            module = json.nullable("module"),
            chapter = json.nullable("subtitleOrChapter"),
            author = json.nullable("author"),
            recordedAtEpochMs = json.optLong(
                "recordedAtEpochMs",
                metadataFile.lastModified()
            ),
            playbackPositionMs = json.nullableLong("playbackPositionMs"),
            sourceUrl = json.nullable("sourceUrl"),
            transcriptionStatus = json.nullable("transcriptionStatus") ?: "NOT_STARTED",
            transcript = json.nullable("transcript")
                ?: transcriptText?.substringAfter("\nTRANSCRIPT\n", transcriptText),
            transcriptSegments = json.optJSONArray("transcriptSegments")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val segment = array.optJSONObject(index) ?: continue
                        val text = segment.optString("text").trim()
                        val start = segment.optLong("startMs", -1L)
                        val end = segment.optLong("endMs", -1L)
                        if (text.isNotBlank() && start >= 0L && end > start) {
                            add(DesktopTranscriptSegment(start, end, text))
                        }
                    }
                }
            }.orEmpty(),
            audioFile = filesByName["$base.m4a"]?.firstOrNull(),
            topicOnly = json.optBoolean("topicOnly", false),
            textOnly = json.optBoolean("textOnly", false) ||
                json.optString("noteKind").equals("TEXT", ignoreCase = true),
            typedNote = json.nullable("typedNote"),
            originDeviceId = json.nullable("originDeviceId"),
            originDeviceName = json.nullable("originDeviceName"),
            originDeviceType = json.nullable("originDeviceType")
        )
    }.getOrNull()

    private fun extractSafely(zipFile: File, root: File) {
        val canonicalRoot = root.canonicalFile
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Unsafe ZIP entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun org.json.JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList()
        else (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONObject.nullable(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key).takeIf { it >= 0L } else null
}
