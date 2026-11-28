package com.praveen.ridernotes.cloud

import android.content.Context
import com.praveen.ridernotes.topics.TopicGroup
import com.praveen.ridernotes.topics.TopicItem
import com.praveen.ridernotes.topics.TopicLibrary
import com.praveen.ridernotes.topics.TopicLibraryRepository
import com.praveen.ridernotes.topics.TopicPlacement
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable per-entity history. Upload transport is deliberately introduced separately. */
object DeltaChangeJournal {
    const val FILE_NAME = "rider_notes_delta_journal.json"
    private const val SCHEMA_VERSION = 1

    data class Entry(
        val revision: DeltaEntityRevision,
        val pendingUpload: Boolean
    )

    @Synchronized
    fun recordPayload(
        context: Context,
        entityId: String,
        kind: DeltaEntityKind,
        payloadJson: String,
        relativePath: String? = null
    ): DeltaEntityRevision? = runCatching {
        record(
            context = context,
            entityId = entityId,
            kind = kind,
            operation = DeltaOperation.UPSERT,
            relativePath = relativePath,
            sizeBytes = payloadJson.toByteArray(Charsets.UTF_8).size.toLong(),
            sha256 = DeltaContentHash.sha256(payloadJson),
            payloadJson = payloadJson
        )
    }.getOrNull()

    @Synchronized
    fun recordFile(
        context: Context,
        entityId: String,
        kind: DeltaEntityKind,
        file: File,
        relativePath: String
    ): DeltaEntityRevision? {
        if (!file.isFile) return null
        return runCatching {
            record(
                context = context,
                entityId = entityId,
                kind = kind,
                operation = DeltaOperation.UPSERT,
                relativePath = relativePath,
                sizeBytes = file.length(),
                sha256 = DeltaContentHash.sha256(file),
                payloadJson = null
            )
        }.getOrNull()
    }

    @Synchronized
    fun recordDelete(
        context: Context,
        entityId: String,
        kind: DeltaEntityKind,
        relativePath: String? = null
    ): DeltaEntityRevision? = runCatching {
        record(
            context = context,
            entityId = entityId,
            kind = kind,
            operation = DeltaOperation.DELETE,
            relativePath = relativePath,
            sizeBytes = null,
            sha256 = null,
            payloadJson = null
        )
    }.getOrNull()

    @Synchronized
    fun entries(context: Context): List<Entry> = read(context)

    @Synchronized
    fun pending(context: Context): List<DeltaEntityRevision> = read(context)
        .filter(Entry::pendingUpload)
        .map(Entry::revision)

    @Synchronized
    fun acknowledge(context: Context, revisionIds: Set<String>) {
        if (revisionIds.isEmpty()) return
        val current = read(context)
        write(
            context,
            current.map { entry ->
                if (entry.revision.revisionId in revisionIds) entry.copy(pendingUpload = false)
                else entry
            }
        )
    }

    @Synchronized
    fun recordTopicLibraryDiff(
        context: Context,
        before: TopicLibrary,
        after: TopicLibrary
    ) {
        reconcile(
            context,
            before.groups.associateBy(TopicGroup::id),
            after.groups.associateBy(TopicGroup::id),
            DeltaEntityKind.TOPIC_GROUP,
            { "topic-group:$it" },
            ::groupPayload
        )
        reconcile(
            context,
            before.items.associateBy(TopicItem::id),
            after.items.associateBy(TopicItem::id),
            DeltaEntityKind.TOPIC_ITEM,
            { "topic-item:$it" },
            ::itemPayload
        )
        reconcile(
            context,
            before.placements.associateBy(TopicPlacement::id),
            after.placements.associateBy(TopicPlacement::id),
            DeltaEntityKind.TOPIC_PLACEMENT,
            { "topic-placement:$it" },
            ::placementPayload
        )

        val legendKeys = before.colorLegend.keys + after.colorLegend.keys
        legendKeys.forEach { color ->
            val old = before.colorLegend[color]
            val new = after.colorLegend[color]
            val entityId = "topic-legend:${color.uppercase()}"
            when {
                new == null && old != null -> recordDelete(
                    context,
                    entityId,
                    DeltaEntityKind.TOPIC_LEGEND
                )
                new != null && new != old -> recordPayload(
                    context,
                    entityId,
                    DeltaEntityKind.TOPIC_LEGEND,
                    JSONObject().put("color", color.uppercase()).put("meaning", new).toString()
                )
            }
        }

        val oldAttachments = before.items.filter { it.type == TopicLibraryRepository.TYPE_ATTACHMENT }
            .associateBy(TopicItem::id)
        val newAttachments = after.items.filter { it.type == TopicLibraryRepository.TYPE_ATTACHMENT }
            .associateBy(TopicItem::id)
        (oldAttachments.keys + newAttachments.keys).forEach { itemId ->
            val old = oldAttachments[itemId]
            val new = newAttachments[itemId]
            val entityId = "attachment:$itemId"
            if (new == null && old != null) {
                recordDelete(context, entityId, DeltaEntityKind.ATTACHMENT, old.target)
            } else if (new != null && (old == null || old.target != new.target)) {
                val path = new.target ?: return@forEach
                recordFile(
                    context,
                    entityId,
                    DeltaEntityKind.ATTACHMENT,
                    File(context.filesDir, "topic_library/$path"),
                    "topic_library/$path"
                )
            }
        }
    }

    /**
     * Creates a complete baseline for upgraded installations and catches file changes made
     * outside a normal repository mutation path. Safe to call repeatedly: unchanged hashes
     * do not create new revisions.
     */
    @Synchronized
    fun reconcileLocalSnapshot(context: Context, library: TopicLibrary) {
        recordTopicLibraryDiff(context, TopicLibrary(), library)

        val desiredFileIds = mutableSetOf<String>()
        val voiceFolder = File(context.filesDir, "voice_notes")
        voiceFolder.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            val base = file.nameWithoutExtension
            val (entityId, kind) = when (file.extension.lowercase()) {
                "m4a" -> "recording-audio:$base" to DeltaEntityKind.RECORDING_AUDIO
                "json" -> "recording-metadata:$base" to DeltaEntityKind.RECORDING_METADATA
                "txt" -> "transcript:$base" to DeltaEntityKind.TRANSCRIPT
                else -> return@forEach
            }
            desiredFileIds += entityId
            recordFile(context, entityId, kind, file, "recordings/${file.name}")
        }

        library.items.filter { it.type == TopicLibraryRepository.TYPE_ATTACHMENT }.forEach { item ->
            val relative = item.target ?: return@forEach
            val entityId = "attachment:${item.id}"
            desiredFileIds += entityId
            recordFile(
                context,
                entityId,
                DeltaEntityKind.ATTACHMENT,
                File(context.filesDir, "topic_library/$relative"),
                "topic_library/$relative"
            )
        }

        read(context).map(Entry::revision)
            .filter { revision ->
                revision.kind in setOf(
                    DeltaEntityKind.RECORDING_AUDIO,
                    DeltaEntityKind.RECORDING_METADATA,
                    DeltaEntityKind.TRANSCRIPT,
                    DeltaEntityKind.ATTACHMENT
                ) && revision.operation == DeltaOperation.UPSERT &&
                    revision.entityId !in desiredFileIds
            }
            .forEach { missing ->
                recordDelete(context, missing.entityId, missing.kind, missing.relativePath)
            }
    }

    private fun <T> reconcile(
        context: Context,
        before: Map<String, T>,
        after: Map<String, T>,
        kind: DeltaEntityKind,
        entityId: (String) -> String,
        payload: (T) -> String
    ) {
        (before.keys + after.keys).forEach { id ->
            val old = before[id]
            val new = after[id]
            when {
                new == null && old != null -> recordDelete(context, entityId(id), kind)
                new != null && (old == null || payload(old) != payload(new)) ->
                    recordPayload(context, entityId(id), kind, payload(new))
            }
        }
    }

    private fun record(
        context: Context,
        entityId: String,
        kind: DeltaEntityKind,
        operation: DeltaOperation,
        relativePath: String?,
        sizeBytes: Long?,
        sha256: String?,
        payloadJson: String?
    ): DeltaEntityRevision? {
        val current = read(context).toMutableList()
        val previousIndex = current.indexOfFirst { it.revision.entityId == entityId }
        val previous = current.getOrNull(previousIndex)?.revision
        if (previous != null &&
            previous.kind == kind &&
            previous.operation == operation &&
            previous.relativePath == relativePath &&
            previous.sizeBytes == sizeBytes &&
            previous.sha256 == sha256 &&
            previous.payloadJson == payloadJson
        ) return null

        val revision = DeltaEntityRevision(
            entityId = entityId,
            kind = kind,
            revisionId = UUID.randomUUID().toString(),
            parentRevisionId = previous?.revisionId,
            modifiedAtEpochMs = System.currentTimeMillis(),
            deviceId = SyncRevisionStore.deviceId(context),
            deviceName = SyncRevisionStore.deviceName(),
            deviceType = "ANDROID",
            operation = operation,
            relativePath = relativePath,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            payloadJson = payloadJson
        )
        val entry = Entry(revision, pendingUpload = true)
        if (previousIndex >= 0) current[previousIndex] = entry else current += entry
        write(context, current)
        return revision
    }

    private fun read(context: Context): List<Entry> = runCatching {
        val file = journalFile(context)
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val revision = revisionFromJson(json.optJSONObject("revision") ?: continue) ?: continue
                add(Entry(revision, json.optBoolean("pendingUpload", false)))
            }
        }
    }.getOrDefault(emptyList())

    private fun write(context: Context, entries: List<Entry>) {
        val file = journalFile(context)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("entries", JSONArray(entries.map(::entryToJson)))
                .toString(2)
        )
        temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    private fun journalFile(context: Context) = File(context.filesDir, "sync/$FILE_NAME")

    private fun entryToJson(entry: Entry): JSONObject = JSONObject()
        .put("pendingUpload", entry.pendingUpload)
        .put("revision", revisionToJson(entry.revision))

    private fun revisionToJson(value: DeltaEntityRevision): JSONObject = JSONObject()
        .put("entityId", value.entityId)
        .put("kind", value.kind.name)
        .put("revisionId", value.revisionId)
        .put("parentRevisionId", value.parentRevisionId ?: JSONObject.NULL)
        .put("modifiedAtEpochMs", value.modifiedAtEpochMs)
        .put("deviceId", value.deviceId)
        .put("deviceName", value.deviceName)
        .put("deviceType", value.deviceType)
        .put("operation", value.operation.name)
        .put("relativePath", value.relativePath ?: JSONObject.NULL)
        .put("sizeBytes", value.sizeBytes ?: JSONObject.NULL)
        .put("sha256", value.sha256 ?: JSONObject.NULL)
        .put("payloadJson", value.payloadJson ?: JSONObject.NULL)

    private fun revisionFromJson(json: JSONObject): DeltaEntityRevision? = runCatching {
        DeltaEntityRevision(
            entityId = json.getString("entityId"),
            kind = DeltaEntityKind.valueOf(json.getString("kind")),
            revisionId = json.getString("revisionId"),
            parentRevisionId = json.nullableString("parentRevisionId"),
            modifiedAtEpochMs = json.getLong("modifiedAtEpochMs"),
            deviceId = json.getString("deviceId"),
            deviceName = json.optString("deviceName", "Unknown device"),
            deviceType = json.optString("deviceType", "UNKNOWN"),
            operation = DeltaOperation.valueOf(json.getString("operation")),
            relativePath = json.nullableString("relativePath"),
            sizeBytes = if (json.isNull("sizeBytes")) null else json.optLong("sizeBytes"),
            sha256 = json.nullableString("sha256"),
            payloadJson = json.nullableString("payloadJson")
        )
    }.getOrNull()

    private fun JSONObject.nullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun groupPayload(value: TopicGroup): String = JSONObject()
        .put("id", value.id)
        .put("name", value.name)
        .put("parentId", value.parentId ?: JSONObject.NULL)
        .put("order", value.order)
        .toString()

    private fun itemPayload(value: TopicItem): String = JSONObject()
        .put("id", value.id)
        .put("type", value.type)
        .put("title", value.title)
        .put("text", value.text ?: JSONObject.NULL)
        .put("target", value.target ?: JSONObject.NULL)
        .put("createdAt", value.createdAt)
        .put("additionalNotes", value.additionalNotes ?: JSONObject.NULL)
        .put("richText", value.richText ?: JSONObject.NULL)
        .toString()

    private fun placementPayload(value: TopicPlacement): String = JSONObject()
        .put("id", value.id)
        .put("groupId", value.groupId)
        .put("itemId", value.itemId)
        .put("order", value.order)
        .put("important", value.important)
        .put("color", value.color)
        .toString()
}
