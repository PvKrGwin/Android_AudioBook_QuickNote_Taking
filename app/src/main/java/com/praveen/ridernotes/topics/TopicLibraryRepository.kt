package com.praveen.ridernotes.topics

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.praveen.ridernotes.cloud.DeltaChangeJournal
import com.praveen.ridernotes.cloud.SyncRevisionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class TopicGroup(val id: String, val name: String, val parentId: String?, val order: Int)
data class TopicItem(
    val id: String,
    val type: String,
    val title: String,
    val text: String?,
    val target: String?,
    val createdAt: Long,
    val additionalNotes: String? = null,
    val richText: String? = null
)
data class TopicPlacement(
    val id: String,
    val groupId: String,
    val itemId: String,
    val order: Int,
    val important: Boolean = false,
    val color: String = "WHITE"
)
data class TopicLibrary(
    val groups: List<TopicGroup> = emptyList(),
    val items: List<TopicItem> = emptyList(),
    val placements: List<TopicPlacement> = emptyList(),
    val colorLegend: Map<String, String> = emptyMap()
)

object TopicLibraryRepository {
    const val TYPE_RECORDING = "RECORDING"
    const val TYPE_TEXT = "TEXT"
    const val TYPE_LINK = "LINK"
    const val TYPE_ATTACHMENT = "ATTACHMENT"

    @Synchronized fun load(context: Context): TopicLibrary {
        val file = manifest(context)
        if (!file.exists()) return TopicLibrary()
        return runCatching { readLibrary(file) }.getOrDefault(TopicLibrary())
    }

    @Synchronized fun createGroup(context: Context, name: String, parentId: String?): TopicGroup {
        val current = load(context)
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "Group name is empty" }
        require(isGroupNameAvailable(current, trimmedName, parentId)) {
            "A folder with this name already exists here"
        }
        val siblings = current.groups.filter { it.parentId == parentId }
        val group = TopicGroup(UUID.randomUUID().toString(), trimmedName, parentId, siblings.size)
        save(context, current.copy(groups = current.groups + group))
        return group
    }

    @Synchronized fun renameGroup(context: Context, groupId: String, name: String) {
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "Group name is empty" }
        val current = load(context)
        val existing = current.groups.firstOrNull { it.id == groupId } ?: return
        require(isGroupNameAvailable(current, trimmedName, existing.parentId, groupId)) {
            "A folder with this name already exists here"
        }
        save(
            context,
            current.copy(
                groups = current.groups.map { group ->
                    if (group.id == groupId) group.copy(name = trimmedName) else group
                }
            )
        )
    }

    fun isGroupNameAvailable(
        library: TopicLibrary,
        name: String,
        parentId: String?,
        excludingGroupId: String? = null
    ): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        return library.groups.none { group ->
            group.id != excludingGroupId &&
                group.parentId == parentId &&
                group.name.trim().equals(normalized, ignoreCase = true)
        }
    }

    @Synchronized fun deleteGroup(context: Context, groupId: String) {
        val current = load(context)
        val ids = mutableSetOf(groupId)
        do {
            val added = current.groups.filter { it.parentId in ids }.map { it.id }.filter(ids::add)
        } while (added.isNotEmpty())
        save(context, pruneOrphans(context, current.copy(
            groups = current.groups.filterNot { it.id in ids },
            placements = current.placements.filterNot { it.groupId in ids }
        )))
    }

    @Synchronized fun addText(
        context: Context,
        groupId: String,
        title: String,
        text: String,
        index: Int? = null,
        richText: String? = null
    ) = addNewItem(
        context,
        groupId,
        TopicItem(id(), TYPE_TEXT, title.ifBlank { "Text note" }, text, null, now(), richText = richText),
        index
    )

    @Synchronized fun addLink(
        context: Context,
        groupId: String,
        title: String,
        url: String,
        index: Int? = null,
        notes: String? = null
    ) = addNewItem(
        context,
        groupId,
        TopicItem(id(), TYPE_LINK, title.ifBlank { url }, null, url, now(), notes?.trim()?.takeIf { it.isNotBlank() }),
        index
    )

    @Synchronized fun addRecording(context: Context, groupId: String, fileName: String, title: String, index: Int? = null) {
        val current = load(context)
        val existing = current.items.firstOrNull { it.type == TYPE_RECORDING && it.target == fileName }
        if (existing != null) linkItem(context, existing.id, groupId, index)
        else addNewItem(context, groupId, TopicItem(id(), TYPE_RECORDING, title, null, fileName, now()), index)
    }

    @Synchronized fun addAttachment(
        context: Context,
        groupId: String,
        uri: Uri,
        index: Int? = null,
        notes: String? = null
    ): Boolean {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"
        val itemId = id()
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val relative = "assets/${itemId}_$safeName"
        val output = File(folder(context), relative).apply { parentFile?.mkdirs() }
        return runCatching {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                output.outputStream().use(input::copyTo)
            }
            addNewItem(
                context,
                groupId,
                TopicItem(itemId, TYPE_ATTACHMENT, displayName, null, relative, now(), notes?.trim()?.takeIf { it.isNotBlank() }),
                index
            )
            true
        }.getOrDefault(false)
    }

    @Synchronized fun linkItem(context: Context, itemId: String, groupId: String, index: Int? = null) {
        val current = load(context)
        if (current.placements.any { it.itemId == itemId && it.groupId == groupId }) return
        save(context, current.copy(placements = insertPlacement(current.placements, groupId, itemId, index)))
    }

    @Synchronized fun removePlacement(context: Context, placementId: String) {
        removePlacements(context, setOf(placementId))
    }

    @Synchronized fun removePlacements(context: Context, placementIds: Set<String>) {
        if (placementIds.isEmpty()) return
        val current = load(context)
        val remaining = current.placements.filterNot { it.id in placementIds }
        save(context, pruneOrphans(context, current.copy(placements = normalizePlacements(remaining))))
    }

    @Synchronized fun setAdditionalNotes(context: Context, itemId: String, notes: String) {
        val current = load(context)
        val normalized = notes.trim().takeIf(String::isNotBlank)
        save(context, current.copy(items = current.items.map { item ->
            if (item.id == itemId) item.copy(
                text = if (item.type == TYPE_TEXT) item.text else null,
                additionalNotes = normalized
            ) else item
        }))
    }

    @Synchronized fun updateEditablePoint(
        context: Context,
        itemId: String,
        title: String,
        body: String,
        linkUrl: String? = null,
        richText: String? = null
    ) {
        val current = load(context)
        save(context, current.copy(items = current.items.map { item ->
            if (item.id != itemId) item else item.copy(
                title = title.trim().ifBlank { item.title },
                text = if (item.type == TYPE_TEXT) body else null,
                additionalNotes = if (item.type == TYPE_TEXT) item.additionalNotes
                    else body.trim().takeIf(String::isNotBlank),
                target = if (item.type == TYPE_LINK) linkUrl?.trim()?.takeIf(String::isNotBlank)
                    ?: item.target else item.target,
                richText = richText
            )
        }))
    }

    @Synchronized fun replaceAttachment(context: Context, itemId: String, uri: Uri): Boolean {
        val current = load(context)
        val item = current.items.firstOrNull { it.id == itemId && it.type == TYPE_ATTACHMENT }
            ?: return false
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val relative = "assets/${item.id}_$safeName"
        val destination = File(folder(context), relative).apply { parentFile?.mkdirs() }
        return runCatching {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                destination.outputStream().use(input::copyTo)
            }
            item.target?.let { old ->
                if (old != relative) File(folder(context), old).delete()
            }
            save(context, current.copy(items = current.items.map {
                if (it.id == itemId) it.copy(target = relative) else it
            }))
            true
        }.getOrDefault(false)
    }

    fun willDeleteTopicRecording(context: Context, placementId: String): Boolean {
        val current = load(context)
        val placement = current.placements.firstOrNull { it.id == placementId } ?: return false
        if (current.placements.count { it.itemId == placement.itemId } > 1) return false
        val item = current.items.firstOrNull { it.id == placement.itemId } ?: return false
        return item.type == TYPE_RECORDING && isTopicOnlyRecording(context, item)
    }

    @Synchronized fun move(context: Context, placementId: String, delta: Int) {
        val current = load(context)
        val placement = current.placements.firstOrNull { it.id == placementId } ?: return
        val group = current.placements.filter { it.groupId == placement.groupId }.sortedBy { it.order }.toMutableList()
        val from = group.indexOfFirst { it.id == placementId }
        val to = (from + delta).coerceIn(0, group.lastIndex)
        if (from == to) return
        val moved = group.removeAt(from)
        group.add(to, moved)
        val normalized = group.mapIndexed { index, value -> value.copy(order = index) }
        save(context, current.copy(placements = current.placements.filterNot { it.groupId == placement.groupId } + normalized))
    }

    @Synchronized fun setImportant(context: Context, placementId: String, important: Boolean) {
        val current = load(context)
        save(context, current.copy(placements = current.placements.map { if (it.id == placementId) it.copy(important = important) else it }))
    }

    @Synchronized fun setColor(context: Context, placementId: String, color: String) {
        val current = load(context)
        save(context, current.copy(placements = current.placements.map { if (it.id == placementId) it.copy(color = color) else it }))
    }

    @Synchronized fun setLegendMeaning(context: Context, color: String, meaning: String) {
        val current = load(context)
        val updated = current.colorLegend.toMutableMap()
        if (meaning.isBlank()) updated.remove(color) else updated[color] = meaning.trim()
        save(context, current.copy(colorLegend = updated))
    }

    fun setPendingRecordingGroup(context: Context, groupId: String, index: Int? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING_GROUP, groupId)
            .putInt(PENDING_INDEX, index ?: -1)
            .apply()
    }

    fun clearPendingRecordingGroup(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_GROUP).remove(PENDING_INDEX).apply()
    }

    @Synchronized fun attachPendingRecording(context: Context, fileName: String, title: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val groupId = prefs.getString(PENDING_GROUP, null) ?: return
        val index = prefs.getInt(PENDING_INDEX, -1).takeIf { it >= 0 }
        prefs.edit().remove(PENDING_GROUP).remove(PENDING_INDEX).apply()
        if (load(context).groups.any { it.id == groupId }) addRecording(context, groupId, fileName, title, index)
    }

    private fun addNewItem(context: Context, groupId: String, item: TopicItem, index: Int?) {
        val current = load(context)
        save(context, current.copy(
            items = current.items + item,
            placements = insertPlacement(current.placements, groupId, item.id, index)
        ))
    }

    private fun insertPlacement(existing: List<TopicPlacement>, groupId: String, itemId: String, index: Int?): List<TopicPlacement> {
        val group = existing.filter { it.groupId == groupId }.sortedBy { it.order }.toMutableList()
        val at = (index ?: group.size).coerceIn(0, group.size)
        group.add(at, TopicPlacement(id(), groupId, itemId, at))
        val normalized = group.mapIndexed { position, value -> value.copy(order = position) }
        return existing.filterNot { it.groupId == groupId } + normalized
    }

    private fun normalizePlacements(placements: List<TopicPlacement>): List<TopicPlacement> =
        placements.groupBy { it.groupId }.values.flatMap { group ->
            group.sortedBy { it.order }.mapIndexed { index, placement -> placement.copy(order = index) }
        }

    private fun pruneOrphans(context: Context, library: TopicLibrary): TopicLibrary {
        val used = library.placements.mapTo(mutableSetOf()) { it.itemId }
        library.items.filter { it.id !in used }.forEach { item ->
            when {
                item.type == TYPE_ATTACHMENT -> attachmentFile(context, item)?.delete()
                item.type == TYPE_RECORDING && isTopicOnlyRecording(context, item) -> {
                    val audioName = item.target ?: return@forEach
                    val voiceFolder = File(context.filesDir, "voice_notes")
                    val baseName = audioName.substringBeforeLast('.')
                    listOf("m4a", "json", "txt", "vtt", "srt").forEach { extension ->
                        val tracked = when (extension) {
                            "m4a" -> "recording-audio" to com.praveen.ridernotes.cloud.DeltaEntityKind.RECORDING_AUDIO
                            "json" -> "recording-metadata" to com.praveen.ridernotes.cloud.DeltaEntityKind.RECORDING_METADATA
                            "txt" -> "transcript" to com.praveen.ridernotes.cloud.DeltaEntityKind.TRANSCRIPT
                            else -> null
                        }
                        tracked?.let { (prefix, kind) ->
                            DeltaChangeJournal.recordDelete(
                                context,
                                "$prefix:$baseName",
                                kind,
                                "recordings/$baseName.$extension"
                            )
                        }
                        File(voiceFolder, "$baseName.$extension").delete()
                    }
                }
            }
        }
        return library.copy(items = library.items.filter { it.id in used })
    }

    private fun isTopicOnlyRecording(context: Context, item: TopicItem): Boolean {
        val audioName = item.target ?: return false
        val metadata = File(
            File(context.filesDir, "voice_notes"),
            audioName.substringBeforeLast('.') + ".json"
        )
        return runCatching { JSONObject(metadata.readText()).optBoolean("topicOnly", false) }
            .getOrDefault(false)
    }

    private fun save(context: Context, library: TopicLibrary) {
        val previous = manifest(context).takeIf(File::exists)
            ?.let { runCatching { readLibrary(it) }.getOrNull() }
            ?: TopicLibrary()
        val root = JSONObject().put("version", 2)
            .put("groups", JSONArray(library.groups.map { JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL).put("order", it.order) }))
            .put("items", JSONArray(library.items.map { JSONObject().put("id", it.id).put("type", it.type).put("title", it.title).put("text", it.text ?: JSONObject.NULL).put("target", it.target ?: JSONObject.NULL).put("createdAt", it.createdAt).put("additionalNotes", it.additionalNotes ?: JSONObject.NULL).put("richText", it.richText ?: JSONObject.NULL) }))
            .put("placements", JSONArray(library.placements.map { JSONObject().put("id", it.id).put("groupId", it.groupId).put("itemId", it.itemId).put("order", it.order).put("important", it.important).put("color", it.color) }))
            .put("colorLegend", JSONObject(library.colorLegend))
        manifest(context).apply { parentFile?.mkdirs(); writeText(root.toString(2)) }
        DeltaChangeJournal.recordTopicLibraryDiff(context, previous, library)
        SyncRevisionStore.markLocalModified(context)
        rebuildBackup(context, library)
    }

    private fun rebuildBackup(
        context: Context,
        library: TopicLibrary = load(context),
        target: File = backup(context),
        includeAllRecordings: Boolean = false
    ) {
        val base = folder(context)
        val temp = File(base, "topics.tmp")
        val revision = if (includeAllRecordings) {
            SyncRevisionStore.writeManifest(context, base)
        } else null
        ZipOutputStream(temp.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("rider_notes_backup.json"))
            zip.write(
                JSONObject()
                    .put("formatVersion", 1)
                    .put("backupType", if (includeAllRecordings) "FULL" else "TOPICS")
                    .put("createdAtEpochMs", System.currentTimeMillis())
                    .toString(2)
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            if (revision != null) {
                val revisionFile = File(base, SyncRevisionStore.MANIFEST_FILE)
                zip.putNextEntry(ZipEntry(SyncRevisionStore.MANIFEST_FILE))
                revisionFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val deltaJournal = File(context.filesDir, "sync/${DeltaChangeJournal.FILE_NAME}")
            if (deltaJournal.isFile) {
                zip.putNextEntry(ZipEntry(DeltaChangeJournal.FILE_NAME))
                deltaJournal.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            base.walkTopDown().filter {
                it.isFile && it != backup(context) && it != fullBackup(context) && it != temp &&
                    it.name != SyncRevisionStore.MANIFEST_FILE
            }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(base).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val voiceFolder = File(context.filesDir, "voice_notes")
            val audioNames = if (includeAllRecordings) {
                voiceFolder.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
                    .map { it.name }
            } else {
                library.items.filter { it.type == TYPE_RECORDING }.mapNotNull { it.target }.distinct()
            }
            audioNames.forEach { audioName ->
                val baseName = audioName.substringBeforeLast('.')
                listOf("m4a", "json", "txt", "vtt", "srt").forEach { extension ->
                    val file = File(voiceFolder, "$baseName.$extension")
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("recordings/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }

    /** Rebuild the complete Topics archive from the phone's current local state. */
    @Synchronized
    fun createFreshBackup(context: Context): Result<File> = runCatching {
        val currentLibrary = load(context)
        DeltaChangeJournal.reconcileLocalSnapshot(context, currentLibrary)
        val output = fullBackup(context)
        rebuildBackup(
            context = context,
            library = currentLibrary,
            target = output,
            includeAllRecordings = true
        )
        output.also { require(it.exists() && it.length() > 0L) }
    }

    /** Merge a topic backup without replacing any file or entity already on this phone. */
    @Synchronized fun restoreBackup(context: Context, uri: Uri): String = runCatching {
        val tempRoot = File(context.cacheDir, "topic_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val safe = File(tempRoot, entry.name).canonicalFile
                    require(safe.path.startsWith(tempRoot.canonicalPath + File.separator))
                    if (!entry.isDirectory) {
                        safe.parentFile?.mkdirs()
                        safe.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        }
        val cloudManifest = File(tempRoot, "library.json")
        require(cloudManifest.exists()) { "Topic manifest is missing" }
        val cloudFolder = folder(context)
        tempRoot.walkTopDown().filter {
            it.isFile && it != cloudManifest &&
                it.name != DeltaChangeJournal.FILE_NAME &&
                !it.relativeTo(tempRoot).invariantSeparatorsPath.startsWith("recordings/")
        }.forEach { source ->
            val destination = File(cloudFolder, source.relativeTo(tempRoot).invariantSeparatorsPath)
            if (!destination.exists()) { destination.parentFile?.mkdirs(); source.copyTo(destination) }
        }
        val voiceFolder = File(context.filesDir, "voice_notes").apply { mkdirs() }
        File(tempRoot, "recordings").listFiles().orEmpty().filter(File::isFile).forEach { source ->
            val destination = File(voiceFolder, source.name)
            if (!destination.exists()) source.copyTo(destination)
        }
        val local = load(context)
        val cloud = readLibrary(cloudManifest)
        val merged = TopicLibrary(
            groups = mergeById(local.groups, cloud.groups) { it.id },
            items = mergeById(local.items, cloud.items) { it.id },
            placements = mergeById(local.placements, cloud.placements) { it.id },
            colorLegend = cloud.colorLegend + local.colorLegend
        )
        save(context, merged)
        tempRoot.deleteRecursively()
        "Topic backup merged: ${cloud.groups.size} cloud group(s), ${cloud.items.size} cloud point(s); phone-only content kept"
    }.getOrElse { "Topic restore failed: ${it.javaClass.simpleName}" }

    /**
     * Keep both sides of a full-backup conflict. Existing phone entities win only when the
     * same UUID was edited on both devices; cloud-only notes, files and Topic points are kept.
     */
    @Synchronized fun mergeFromFullBackup(context: Context, input: InputStream): String = runCatching {
        val tempRoot = File(context.cacheDir, "full_merge_${System.currentTimeMillis()}").apply { mkdirs() }
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val safe = File(tempRoot, entry.name).canonicalFile
                require(safe.path.startsWith(tempRoot.canonicalPath + File.separator))
                if (!entry.isDirectory) {
                    safe.parentFile?.mkdirs()
                    safe.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val cloudManifest = File(tempRoot, "library.json")
        require(cloudManifest.exists()) { "Topic manifest is missing" }
        require(File(tempRoot, "rider_notes_backup.json").exists()) { "Backup marker is missing" }

        val localFolder = folder(context)
        tempRoot.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(tempRoot).invariantSeparatorsPath
            when {
                relative.startsWith("recordings/") -> {
                    val target = File(File(context.filesDir, "voice_notes").apply { mkdirs() }, source.name)
                    if (!target.exists()) source.copyTo(target)
                }
                relative !in setOf(
                    "library.json",
                    "rider_notes_backup.json",
                    SyncRevisionStore.MANIFEST_FILE,
                    DeltaChangeJournal.FILE_NAME
                ) -> {
                    val target = File(localFolder, relative)
                    if (!target.exists()) {
                        target.parentFile?.mkdirs()
                        source.copyTo(target)
                    }
                }
            }
        }
        val local = load(context)
        val cloud = readLibrary(cloudManifest)
        val merged = TopicLibrary(
            groups = mergeById(local.groups, cloud.groups) { it.id },
            items = mergeById(local.items, cloud.items) { it.id },
            placements = mergeById(local.placements, cloud.placements) { it.id },
            colorLegend = cloud.colorLegend + local.colorLegend
        )
        save(context, merged)
        File(context.filesDir, "voice_notes").listFiles().orEmpty()
            .filter { it.extension.equals("json", true) }
            .forEach { metadata ->
                val json = runCatching { JSONObject(metadata.readText()) }.getOrNull() ?: return@forEach
                if (json.optBoolean("textOnly", false)) {
                    File(metadata.parentFile, metadata.nameWithoutExtension + ".m4a")
                        .takeUnless(File::exists)
                        ?.writeBytes(byteArrayOf())
                }
            }
        tempRoot.deleteRecursively()
        "Cloud and phone merged; phone-only and cloud-only content kept"
    }.getOrElse { "Cloud merge failed: ${it.javaClass.simpleName}" }

    /**
     * Replace the phone's managed Rider Notes content with one complete cloud snapshot.
     * A safety copy of the previous full backup is retained locally before replacement.
     */
    @Synchronized fun replaceFromFullBackup(context: Context, input: InputStream): String = runCatching {
        val tempRoot = File(context.cacheDir, "full_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val safe = File(tempRoot, entry.name).canonicalFile
                require(safe.path.startsWith(tempRoot.canonicalPath + File.separator))
                if (!entry.isDirectory) {
                    safe.parentFile?.mkdirs()
                    safe.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(File(tempRoot, "library.json").exists()) { "Topic manifest is missing" }
        require(File(tempRoot, "rider_notes_backup.json").exists()) { "Backup marker is missing" }

        val localFolder = folder(context)
        val safety = File(
            File(context.filesDir, "safety_backups").apply { mkdirs() },
            "RiderNotes_PreCloudRestore_${System.currentTimeMillis()}.zip"
        )
        rebuildBackup(
            context = context,
            library = load(context),
            target = safety,
            includeAllRecordings = true
        )
        localFolder.listFiles().orEmpty().filter { file ->
            file.name !in setOf(
                backup(context).name,
                fullBackup(context).name,
                SyncRevisionStore.MANIFEST_FILE
            )
        }.forEach { it.deleteRecursively() }
        val voiceFolder = File(context.filesDir, "voice_notes").apply { mkdirs() }
        voiceFolder.listFiles().orEmpty().forEach { it.deleteRecursively() }

        File(tempRoot, DeltaChangeJournal.FILE_NAME).takeIf(File::isFile)?.let { source ->
            val destination = File(context.filesDir, "sync/${DeltaChangeJournal.FILE_NAME}")
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }

        tempRoot.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(tempRoot).invariantSeparatorsPath
            when {
                relative.startsWith("recordings/") -> {
                    source.copyTo(File(voiceFolder, source.name), overwrite = true)
                }
                relative !in setOf(
                    "rider_notes_backup.json",
                    SyncRevisionStore.MANIFEST_FILE,
                    DeltaChangeJournal.FILE_NAME
                ) -> {
                    val target = File(localFolder, relative)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }
        }

        // Text-only PC notes intentionally have no audio. A zero-byte marker keeps them
        // visible to the existing Notes list while playback remains disabled.
        voiceFolder.listFiles().orEmpty()
            .filter { it.extension.equals("json", true) }
            .forEach { metadata ->
                val json = runCatching { JSONObject(metadata.readText()) }.getOrNull() ?: return@forEach
                if (json.optBoolean("textOnly", false)) {
                    File(voiceFolder, metadata.nameWithoutExtension + ".m4a")
                        .takeUnless(File::exists)
                        ?.writeBytes(byteArrayOf())
                }
            }
        // The next explicit/automatic sync rebuilds the archive from this restored state.
        // Do not generate a new local revision while the caller is still accepting the
        // cloud revision that was just downloaded.
        backup(context).delete()
        fullBackup(context).delete()
        tempRoot.deleteRecursively()
        "Cloud snapshot restored; ${safety.name} retained"
    }.getOrElse { "Cloud restore failed: ${it.javaClass.simpleName}" }

    private fun readLibrary(file: File): TopicLibrary {
        val root = JSONObject(file.readText())
        return TopicLibrary(
            groups = root.optJSONArray("groups").objects().map { TopicGroup(it.getString("id"), it.getString("name"), it.nullable("parentId"), it.optInt("order")) },
            items = root.optJSONArray("items").objects().map { TopicItem(it.getString("id"), it.getString("type"), it.optString("title", "Untitled"), it.nullable("text"), it.nullable("target"), it.optLong("createdAt"), it.nullable("additionalNotes"), it.nullable("richText")) },
            placements = root.optJSONArray("placements").objects().map { TopicPlacement(it.getString("id"), it.getString("groupId"), it.getString("itemId"), it.optInt("order"), it.optBoolean("important"), it.optString("color", "WHITE")) },
            colorLegend = root.optJSONObject("colorLegend")?.let { legend -> legend.keys().asSequence().associateWith { legend.optString(it) }.filterValues { it.isNotBlank() } }.orEmpty()
        )
    }

    private fun <T> mergeById(local: List<T>, cloud: List<T>, id: (T) -> String): List<T> {
        val localIds = local.mapTo(mutableSetOf(), id)
        return local + cloud.filter { id(it) !in localIds }
    }

    fun backupFile(context: Context): File = backup(context)
    fun attachmentFile(context: Context, item: TopicItem): File? = item.target?.let { File(folder(context), it) }
    private fun folder(context: Context) = File(context.filesDir, "topic_library").apply { mkdirs() }
    private fun manifest(context: Context) = File(folder(context), "library.json")
    private fun backup(context: Context) = File(folder(context), "RiderNotes_Topics_Backup.zip")
    private fun fullBackup(context: Context) = File(folder(context), "RiderNotes_Full_Backup.zip")
    private fun id() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.nullable(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    private const val PREFS = "rider_notes_topics"
    private const val PENDING_GROUP = "pending_recording_group"
    private const val PENDING_INDEX = "pending_recording_index"
}
