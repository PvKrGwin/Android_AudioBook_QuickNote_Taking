package com.praveen.ridernotes.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CapturedMedia(
    val sourceApp: String,
    val sourcePackage: String?,
    val title: String,
    val author: String?,
    val playbackPositionMs: Long?,
    val module: String? = null,
    val chapter: String? = null,
    val sourceUrl: String? = null
)

object BackupEditor {
    fun deleteNotes(snapshot: BackupSnapshot, noteIds: Set<String>): BackupSnapshot {
        val existingIds = snapshot.notes.mapTo(mutableSetOf()) { it.id }
        val targets = noteIds.intersect(existingIds)
        if (targets.isEmpty()) return snapshot
        DesktopUndoStore.capture(snapshot)
        val companionExtensions = setOf("m4a", "json", "txt", "vtt", "srt")
        snapshot.extractedRoot.walkTopDown()
            .filter { it.isFile }
            .filter {
                it.nameWithoutExtension in targets &&
                    it.extension.lowercase() in companionExtensions
            }
            .toList()
            .forEach(File::delete)
        return BackupImporter.reload(snapshot)
    }

    fun addTypedMediaNote(
        snapshot: BackupSnapshot,
        media: CapturedMedia,
        noteText: String
    ): BackupSnapshot {
        require(noteText.isNotBlank()) { "The note is empty" }
        DesktopUndoStore.capture(snapshot)
        val inheritedUrl = DesktopYouTubeLinks.canonicalUrl(media.sourceApp, media.sourceUrl)
            ?: snapshot.notes.asSequence()
                .filter { DesktopYouTubeLinks.sameIdentity(it, media) }
                .mapNotNull { DesktopYouTubeLinks.canonicalUrl(it.sourceApp, it.sourceUrl) }
                .firstOrNull()
        val recordedAt = System.currentTimeMillis()
        val base = "RiderTextNote_${recordedAt}_${UUID.randomUUID().toString().take(8)}"
        val recordings = File(snapshot.extractedRoot, "recordings").apply { mkdirs() }
        val metadata = JSONObject()
            .put("metadataVersion", 5)
            .put("recordedAtEpochMs", recordedAt)
            .put("type", "MEDIA_LINKED_TEXT")
            .put("noteKind", "TEXT")
            .put("textOnly", true)
            .put("topicOnly", false)
            .put("title", media.title.ifBlank { "Typed media note" })
            .put("sourcePackage", media.sourcePackage ?: JSONObject.NULL)
            .put("sourceApp", media.sourceApp.ifBlank { "Windows media" })
            .put("module", media.module ?: JSONObject.NULL)
            .put("subtitleOrChapter", media.chapter ?: JSONObject.NULL)
            .put("author", media.author ?: JSONObject.NULL)
            .put("playbackPositionMs", media.playbackPositionMs ?: JSONObject.NULL)
            .put("sourceUrl", inheritedUrl ?: media.sourceUrl ?: JSONObject.NULL)
            .put("transcriptionStatus", "NOT_REQUESTED")
            .put("typedNote", noteText.trim())
            .put("originDeviceId", DesktopDeviceIdentity.id())
            .put("originDeviceName", DesktopDeviceIdentity.name())
            .put("originDeviceType", "WINDOWS_PC")
        File(recordings, "$base.json").writeText(metadata.toString(2))
        // Text notes have no real audio, but the marker lets Android and PC reuse the
        // existing recording-linked Topic format without presenting a playback action.
        File(recordings, "$base.m4a").writeBytes(byteArrayOf())
        File(recordings, "$base.txt").writeText(
            buildString {
                appendLine("TITLE: ${media.title}")
                appendLine("SOURCE: ${media.sourceApp}")
                media.module?.let { appendLine("MODULE: $it") }
                media.chapter?.let { appendLine("CHAPTER / EPISODE: $it") }
                media.author?.let { appendLine("AUTHOR / CHANNEL: $it") }
                media.playbackPositionMs?.let { appendLine("MEDIA POSITION MS: $it") }
                appendLine()
                appendLine("NOTE")
                appendLine(noteText.trim())
            }
        )
        val reloaded = BackupImporter.reload(snapshot)
        return inheritedUrl?.let { applyYouTubeLinkToMatchingNotes(reloaded, media, it) }
            ?: reloaded
    }

    /**
     * Backfill a verified YouTube/YouTube Music URL across notes representing
     * the same service, title and channel. Playback positions remain per-note.
     */
    internal fun applyYouTubeLinkToMatchingNotes(
        snapshot: BackupSnapshot,
        media: CapturedMedia,
        sourceUrl: String
    ): BackupSnapshot {
        val canonical = DesktopYouTubeLinks.canonicalUrl(media.sourceApp, sourceUrl)
            ?: return snapshot
        val matchingIds = snapshot.notes.asSequence()
            .filter { DesktopYouTubeLinks.sameIdentity(it, media) }
            .mapTo(mutableSetOf()) { it.id }
        if (matchingIds.isEmpty()) return snapshot

        var changed = false
        snapshot.extractedRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .filter { it.nameWithoutExtension in matchingIds }
            .forEach { metadataFile ->
                runCatching {
                    val json = JSONObject(metadataFile.readText())
                    if (json.optString("sourceUrl") != canonical) {
                        json.put("sourceUrl", canonical)
                        metadataFile.writeText(json.toString(2))
                        changed = true
                    }
                }
            }
        return if (changed) BackupImporter.reload(snapshot) else snapshot
    }

    fun createGroup(snapshot: BackupSnapshot, name: String, parentId: String?): BackupSnapshot {
        require(name.isNotBlank()) { "Folder name is empty" }
        DesktopUndoStore.capture(snapshot)
        val siblings = snapshot.groups.filter { it.parentId == parentId }
        val updated = snapshot.groups + DesktopTopicGroup(
            id = id(),
            name = name.trim(),
            parentId = parentId,
            order = siblings.size
        )
        return saveLibrary(snapshot, groups = updated)
    }

    fun deleteGroup(snapshot: BackupSnapshot, groupId: String): BackupSnapshot {
        DesktopUndoStore.capture(snapshot)
        val ids = mutableSetOf(groupId)
        do {
            val before = ids.size
            snapshot.groups.filter { it.parentId in ids }.forEach { ids += it.id }
        } while (ids.size != before)
        val placements = snapshot.placements.filterNot { it.groupId in ids }
        val usedItemIds = placements.mapTo(mutableSetOf()) { it.itemId }
        deleteOrphanFiles(snapshot, snapshot.items.filter { it.id !in usedItemIds })
        return saveLibrary(
            snapshot,
            groups = snapshot.groups.filterNot { it.id in ids },
            items = snapshot.items.filter { it.id in usedItemIds },
            placements = placements
        )
    }

    fun addTextPoint(
        snapshot: BackupSnapshot,
        groupId: String,
        title: String,
        text: String
    ): BackupSnapshot {
        DesktopUndoStore.capture(snapshot)
        return addPoint(
            snapshot,
            groupId,
            DesktopTopicItem(id(), "TEXT", title.ifBlank { "Text note" }, text, null, now())
        )
    }

    fun addLinkPoint(
        snapshot: BackupSnapshot,
        groupId: String,
        title: String,
        url: String,
        notes: String? = null
    ): BackupSnapshot {
        DesktopUndoStore.capture(snapshot)
        return addPoint(
            snapshot,
            groupId,
            DesktopTopicItem(
                id(), "LINK", title.ifBlank { url },
                notes?.trim()?.takeIf { it.isNotBlank() }, url, now()
            )
        )
    }

    fun addAttachment(
        snapshot: BackupSnapshot,
        groupId: String,
        source: File,
        notes: String? = null
    ): BackupSnapshot {
        require(source.isFile) { "Attachment was not found" }
        DesktopUndoStore.capture(snapshot)
        val itemId = id()
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val relative = "assets/${itemId}_$safeName"
        val target = File(snapshot.libraryRoot, relative).apply { parentFile?.mkdirs() }
        source.copyTo(target, overwrite = false)
        return addPoint(
            snapshot,
            groupId,
            DesktopTopicItem(
                itemId, "ATTACHMENT", source.name,
                notes?.trim()?.takeIf { it.isNotBlank() }, relative, now()
            )
        )
    }

    fun addRecordingPoint(
        snapshot: BackupSnapshot,
        groupId: String,
        note: DesktopNote
    ): BackupSnapshot {
        DesktopUndoStore.capture(snapshot)
        val audioName = note.audioFile?.name ?: "${note.id}.m4a".also { name ->
            File(snapshot.extractedRoot, "recordings/$name").apply {
                parentFile?.mkdirs()
                if (!exists()) writeBytes(byteArrayOf())
            }
        }
        return addPoint(
            snapshot,
            groupId,
            DesktopTopicItem(id(), "RECORDING", note.title, null, audioName, now())
        )
    }

    fun removePlacement(snapshot: BackupSnapshot, placementId: String): BackupSnapshot {
        return removePlacements(snapshot, setOf(placementId))
    }

    fun linkPlacementToGroup(
        snapshot: BackupSnapshot,
        placementId: String,
        targetGroupId: String
    ): BackupSnapshot {
        val source = snapshot.placements.firstOrNull { it.id == placementId } ?: return snapshot
        require(snapshot.groups.any { it.id == targetGroupId }) { "Topic folder no longer exists" }
        if (snapshot.placements.any { it.itemId == source.itemId && it.groupId == targetGroupId }) {
            return snapshot
        }
        DesktopUndoStore.capture(snapshot)
        val linked = DesktopTopicPlacement(
            id = id(),
            groupId = targetGroupId,
            itemId = source.itemId,
            order = snapshot.placements.count { it.groupId == targetGroupId },
            important = source.important,
            color = source.color
        )
        return saveLibrary(snapshot, placements = snapshot.placements + linked)
    }

    fun removePlacements(snapshot: BackupSnapshot, placementIds: Set<String>): BackupSnapshot {
        val existingIds = snapshot.placements.mapTo(mutableSetOf()) { it.id }
        val targets = placementIds.intersect(existingIds)
        if (targets.isEmpty()) return snapshot
        DesktopUndoStore.capture(snapshot)
        val placements = snapshot.placements.filterNot { it.id in targets }
        val usedIds = placements.mapTo(mutableSetOf()) { it.itemId }
        deleteOrphanFiles(snapshot, snapshot.items.filter { it.id !in usedIds })
        return saveLibrary(
            snapshot,
            items = snapshot.items.filter { it.id in usedIds },
            placements = normalizePlacements(placements)
        )
    }

    fun movePlacement(snapshot: BackupSnapshot, placementId: String, delta: Int): BackupSnapshot {
        DesktopUndoStore.capture(snapshot)
        val selected = snapshot.placements.firstOrNull { it.id == placementId } ?: return snapshot
        val siblings = snapshot.placements.filter { it.groupId == selected.groupId }
            .sortedBy { it.order }
            .toMutableList()
        val from = siblings.indexOfFirst { it.id == placementId }
        val to = (from + delta).coerceIn(0, siblings.lastIndex)
        if (from < 0 || from == to) return snapshot
        siblings.add(to, siblings.removeAt(from))
        val updated = snapshot.placements.filterNot { it.groupId == selected.groupId } +
            siblings.mapIndexed { index, value -> value.copy(order = index) }
        return saveLibrary(snapshot, placements = updated)
    }

    fun updatePlacement(
        snapshot: BackupSnapshot,
        placementId: String,
        important: Boolean? = null,
        color: String? = null
    ): BackupSnapshot {
        return updatePlacements(snapshot, setOf(placementId), important, color)
    }

    fun updatePlacements(
        snapshot: BackupSnapshot,
        placementIds: Set<String>,
        important: Boolean? = null,
        color: String? = null
    ): BackupSnapshot {
        val existingIds = snapshot.placements.mapTo(mutableSetOf()) { it.id }
        val targets = placementIds.intersect(existingIds)
        if (targets.isEmpty()) return snapshot
        DesktopUndoStore.capture(snapshot)
        return saveLibrary(
            snapshot,
            placements = snapshot.placements.map { placement ->
                if (placement.id in targets) placement.copy(
                    important = important ?: placement.important,
                    color = color ?: placement.color
                ) else placement
            }
        )
    }

    fun updateLegend(snapshot: BackupSnapshot, color: String, meaning: String): BackupSnapshot {
        DesktopUndoStore.capture(snapshot)
        val legend = snapshot.colorLegend.toMutableMap()
        if (meaning.isBlank()) legend.remove(color) else legend[color] = meaning.trim()
        return saveLibrary(snapshot, legend = legend)
    }

    private fun addPoint(
        snapshot: BackupSnapshot,
        groupId: String,
        item: DesktopTopicItem
    ): BackupSnapshot {
        require(snapshot.groups.any { it.id == groupId }) { "Topic folder no longer exists" }
        val order = snapshot.placements.count { it.groupId == groupId }
        val placement = DesktopTopicPlacement(id(), groupId, item.id, order, false, "WHITE")
        return saveLibrary(
            snapshot,
            items = snapshot.items + item,
            placements = snapshot.placements + placement
        )
    }

    internal fun saveLibrary(
        snapshot: BackupSnapshot,
        groups: List<DesktopTopicGroup> = snapshot.groups,
        items: List<DesktopTopicItem> = snapshot.items,
        placements: List<DesktopTopicPlacement> = snapshot.placements,
        legend: Map<String, String> = snapshot.colorLegend
    ): BackupSnapshot {
        val root = JSONObject()
            .put("version", 2)
            .put("groups", JSONArray(groups.map { group ->
                JSONObject().put("id", group.id).put("name", group.name)
                    .put("parentId", group.parentId ?: JSONObject.NULL).put("order", group.order)
            }))
            .put("items", JSONArray(items.map { item ->
                JSONObject().put("id", item.id).put("type", item.type).put("title", item.title)
                    .put("text", item.text ?: JSONObject.NULL)
                    .put("target", item.target ?: JSONObject.NULL).put("createdAt", item.createdAt)
            }))
            .put("placements", JSONArray(placements.map { placement ->
                JSONObject().put("id", placement.id).put("groupId", placement.groupId)
                    .put("itemId", placement.itemId).put("order", placement.order)
                    .put("important", placement.important).put("color", placement.color)
            }))
            .put("colorLegend", JSONObject(legend))
        File(snapshot.libraryRoot, "library.json").writeText(root.toString(2))
        return BackupImporter.reload(snapshot)
    }

    private fun normalizePlacements(values: List<DesktopTopicPlacement>): List<DesktopTopicPlacement> =
        values.groupBy { it.groupId }.values.flatMap { siblings ->
            siblings.sortedBy { it.order }.mapIndexed { index, value -> value.copy(order = index) }
        }

    private fun deleteOrphanFiles(snapshot: BackupSnapshot, removed: List<DesktopTopicItem>) {
        removed.filter { it.type == "ATTACHMENT" }.forEach { item ->
            item.target?.let { File(snapshot.libraryRoot, it).delete() }
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()
}
