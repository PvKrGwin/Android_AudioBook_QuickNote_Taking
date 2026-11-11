package com.praveen.ridernotes.topics

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.praveen.ridernotes.cloud.CloudSyncManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val createdAt: Long
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
        val siblings = current.groups.filter { it.parentId == parentId }
        val group = TopicGroup(UUID.randomUUID().toString(), name.trim(), parentId, siblings.size)
        save(context, current.copy(groups = current.groups + group))
        return group
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

    @Synchronized fun addText(context: Context, groupId: String, title: String, text: String, index: Int? = null) =
        addNewItem(context, groupId, TopicItem(id(), TYPE_TEXT, title.ifBlank { "Text note" }, text, null, now()), index)

    @Synchronized fun addLink(context: Context, groupId: String, title: String, url: String, index: Int? = null) =
        addNewItem(context, groupId, TopicItem(id(), TYPE_LINK, title.ifBlank { url }, null, url, now()), index)

    @Synchronized fun addRecording(context: Context, groupId: String, fileName: String, title: String, index: Int? = null) {
        val current = load(context)
        val existing = current.items.firstOrNull { it.type == TYPE_RECORDING && it.target == fileName }
        if (existing != null) linkItem(context, existing.id, groupId, index)
        else addNewItem(context, groupId, TopicItem(id(), TYPE_RECORDING, title, null, fileName, now()), index)
    }

    @Synchronized fun addAttachment(context: Context, groupId: String, uri: Uri, index: Int? = null): Boolean {
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
            addNewItem(context, groupId, TopicItem(itemId, TYPE_ATTACHMENT, displayName, null, relative, now()), index)
            true
        }.getOrDefault(false)
    }

    @Synchronized fun linkItem(context: Context, itemId: String, groupId: String, index: Int? = null) {
        val current = load(context)
        if (current.placements.any { it.itemId == itemId && it.groupId == groupId }) return
        save(context, current.copy(placements = insertPlacement(current.placements, groupId, itemId, index)))
    }

    @Synchronized fun removePlacement(context: Context, placementId: String) {
        val current = load(context)
        save(context, pruneOrphans(context, current.copy(placements = current.placements.filterNot { it.id == placementId })))
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
        val root = JSONObject().put("version", 2)
            .put("groups", JSONArray(library.groups.map { JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL).put("order", it.order) }))
            .put("items", JSONArray(library.items.map { JSONObject().put("id", it.id).put("type", it.type).put("title", it.title).put("text", it.text ?: JSONObject.NULL).put("target", it.target ?: JSONObject.NULL).put("createdAt", it.createdAt) }))
            .put("placements", JSONArray(library.placements.map { JSONObject().put("id", it.id).put("groupId", it.groupId).put("itemId", it.itemId).put("order", it.order).put("important", it.important).put("color", it.color) }))
            .put("colorLegend", JSONObject(library.colorLegend))
        manifest(context).apply { parentFile?.mkdirs(); writeText(root.toString(2)) }
        rebuildBackup(context, library)
        CloudSyncManager.enqueueNow(context)
    }

    private fun rebuildBackup(context: Context, library: TopicLibrary = load(context)) {
        val base = folder(context)
        val zipFile = backup(context)
        val temp = File(base, "topics.tmp")
        ZipOutputStream(temp.outputStream()).use { zip ->
            base.walkTopDown().filter { it.isFile && it != zipFile && it != temp }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(base).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val voiceFolder = File(context.filesDir, "voice_notes")
            library.items.filter { it.type == TYPE_RECORDING }.mapNotNull { it.target }.distinct().forEach { audioName ->
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
        temp.copyTo(zipFile, overwrite = true)
        temp.delete()
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
        tempRoot.walkTopDown().filter { it.isFile && it != cloudManifest && !it.relativeTo(tempRoot).invariantSeparatorsPath.startsWith("recordings/") }.forEach { source ->
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

    private fun readLibrary(file: File): TopicLibrary {
        val root = JSONObject(file.readText())
        return TopicLibrary(
            groups = root.optJSONArray("groups").objects().map { TopicGroup(it.getString("id"), it.getString("name"), it.nullable("parentId"), it.optInt("order")) },
            items = root.optJSONArray("items").objects().map { TopicItem(it.getString("id"), it.getString("type"), it.optString("title", "Untitled"), it.nullable("text"), it.nullable("target"), it.optLong("createdAt")) },
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
    private fun id() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.nullable(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    private const val PREFS = "rider_notes_topics"
    private const val PENDING_GROUP = "pending_recording_group"
    private const val PENDING_INDEX = "pending_recording_index"
}
