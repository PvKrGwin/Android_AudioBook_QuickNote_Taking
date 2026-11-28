package com.praveen.ridernotes.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Reconciles the complete editable PC snapshot at publish time. This catches every edit,
 * including future UI operations which have not been given a dedicated change callback.
 */
object DesktopDeltaJournal {
    const val FILE_NAME = "rider_notes_delta_journal.json"
    private const val SCHEMA_VERSION = 1

    data class Entry(val revision: DeltaEntityRevision, val pendingUpload: Boolean)

    fun reconcile(snapshot: BackupSnapshot): List<Entry> {
        val file = File(snapshot.extractedRoot, FILE_NAME)
        val previous = read(file).associateBy { it.revision.entityId }
        val desired = desiredEntities(snapshot)
        val updated = linkedMapOf<String, Entry>()

        (previous.keys + desired.keys).sorted().forEach { entityId ->
            val old = previous[entityId]
            val next = desired[entityId]
            when {
                next == null && old?.revision?.operation == DeltaOperation.UPSERT -> {
                    updated[entityId] = Entry(
                        revision = createRevision(
                            next = old.revision.copy(
                                revisionId = "",
                                parentRevisionId = old.revision.revisionId,
                                ancestorRevisionIds = listOf(old.revision.revisionId) +
                                    old.revision.ancestorRevisionIds,
                                operation = DeltaOperation.DELETE,
                                sizeBytes = null,
                                sha256 = null,
                                payloadJson = null
                            )
                        ),
                        pendingUpload = true
                    )
                }
                next == null && old != null -> updated[entityId] = old
                next != null && old?.revision?.sameContent(next) == true ->
                    updated[entityId] = old
                next != null -> updated[entityId] = Entry(
                    revision = createRevision(
                        next.copy(
                            parentRevisionId = old?.revision?.revisionId,
                            ancestorRevisionIds = old?.revision?.let {
                                listOf(it.revisionId) + it.ancestorRevisionIds
                            }.orEmpty().distinct()
                        )
                    ),
                    pendingUpload = true
                )
            }
        }
        write(file, updated.values.toList())
        return updated.values.toList()
    }

    fun read(file: File): List<Entry> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val array = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val wrapper = array.optJSONObject(index) ?: continue
                val revision = fromJson(wrapper.optJSONObject("revision") ?: continue) ?: continue
                add(Entry(revision, wrapper.optBoolean("pendingUpload", false)))
            }
        }
    }.getOrDefault(emptyList())

    private fun desiredEntities(snapshot: BackupSnapshot): Map<String, DeltaEntityRevision> {
        val values = linkedMapOf<String, DeltaEntityRevision>()
        snapshot.groups.forEach { group ->
            addPayload(values, "topic-group:${group.id}", DeltaEntityKind.TOPIC_GROUP, groupPayload(group))
        }
        snapshot.items.forEach { item ->
            addPayload(values, "topic-item:${item.id}", DeltaEntityKind.TOPIC_ITEM, itemPayload(item))
            if (item.type == "ATTACHMENT") {
                item.target?.let { relative ->
                    addFile(
                        values,
                        "attachment:${item.id}",
                        DeltaEntityKind.ATTACHMENT,
                        File(snapshot.libraryRoot, relative),
                        "topic_library/$relative"
                    )
                }
            }
        }
        snapshot.placements.forEach { placement ->
            addPayload(
                values,
                "topic-placement:${placement.id}",
                DeltaEntityKind.TOPIC_PLACEMENT,
                placementPayload(placement)
            )
        }
        snapshot.colorLegend.forEach { (color, meaning) ->
            addPayload(
                values,
                "topic-legend:${color.uppercase()}",
                DeltaEntityKind.TOPIC_LEGEND,
                JSONObject().put("color", color.uppercase()).put("meaning", meaning).toString()
            )
        }

        val files = snapshot.extractedRoot.walkTopDown().filter(File::isFile)
            .groupBy(File::getName)
        snapshot.notes.forEach { note ->
            val base = note.id
            files["$base.json"]?.firstOrNull()?.let {
                addFile(
                    values,
                    "recording-metadata:$base",
                    DeltaEntityKind.RECORDING_METADATA,
                    it,
                    "recordings/${it.name}"
                )
            }
            note.audioFile?.let {
                addFile(
                    values,
                    "recording-audio:$base",
                    DeltaEntityKind.RECORDING_AUDIO,
                    it,
                    "recordings/${it.name}"
                )
            }
            files["$base.txt"]?.firstOrNull()?.let {
                addFile(
                    values,
                    "transcript:$base",
                    DeltaEntityKind.TRANSCRIPT,
                    it,
                    "recordings/${it.name}"
                )
            }
        }
        return values
    }

    private fun addPayload(
        target: MutableMap<String, DeltaEntityRevision>,
        entityId: String,
        kind: DeltaEntityKind,
        payload: String
    ) {
        target[entityId] = template(
            entityId = entityId,
            kind = kind,
            sizeBytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
            sha256 = DeltaContentHash.sha256(payload),
            payloadJson = payload
        )
    }

    private fun addFile(
        target: MutableMap<String, DeltaEntityRevision>,
        entityId: String,
        kind: DeltaEntityKind,
        file: File,
        relativePath: String
    ) {
        if (!file.isFile) return
        target[entityId] = template(
            entityId = entityId,
            kind = kind,
            relativePath = relativePath,
            sizeBytes = file.length(),
            sha256 = DeltaContentHash.sha256(file)
        )
    }

    private fun template(
        entityId: String,
        kind: DeltaEntityKind,
        relativePath: String? = null,
        sizeBytes: Long? = null,
        sha256: String? = null,
        payloadJson: String? = null
    ) = DeltaEntityRevision(
        entityId = entityId,
        kind = kind,
        revisionId = "",
        parentRevisionId = null,
        modifiedAtEpochMs = 0L,
        deviceId = "",
        deviceName = "",
        deviceType = "WINDOWS_PC",
        operation = DeltaOperation.UPSERT,
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        payloadJson = payloadJson
    )

    private fun createRevision(next: DeltaEntityRevision) = next.copy(
        revisionId = UUID.randomUUID().toString(),
        modifiedAtEpochMs = System.currentTimeMillis(),
        deviceId = DesktopDeviceIdentity.id(),
        deviceName = DesktopDeviceIdentity.name(),
        deviceType = "WINDOWS_PC"
    )

    private fun DeltaEntityRevision.sameContent(other: DeltaEntityRevision): Boolean =
        kind == other.kind && operation == other.operation && relativePath == other.relativePath &&
            sizeBytes == other.sizeBytes && sha256 == other.sha256 && payloadJson == other.payloadJson

    fun write(file: File, entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(encode(entries))
        temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    fun encode(entries: List<Entry>): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("entries", JSONArray(entries.map { entry ->
            JSONObject().put("pendingUpload", entry.pendingUpload)
                .put("revision", toJson(entry.revision))
        }))
        .toString(2)

    private fun toJson(value: DeltaEntityRevision): JSONObject = JSONObject()
        .put("entityId", value.entityId).put("kind", value.kind.name)
        .put("revisionId", value.revisionId)
        .put("parentRevisionId", value.parentRevisionId ?: JSONObject.NULL)
        .put("ancestorRevisionIds", JSONArray(value.ancestorRevisionIds))
        .put("modifiedAtEpochMs", value.modifiedAtEpochMs)
        .put("deviceId", value.deviceId).put("deviceName", value.deviceName)
        .put("deviceType", value.deviceType).put("operation", value.operation.name)
        .put("relativePath", value.relativePath ?: JSONObject.NULL)
        .put("sizeBytes", value.sizeBytes ?: JSONObject.NULL)
        .put("sha256", value.sha256 ?: JSONObject.NULL)
        .put("payloadJson", value.payloadJson ?: JSONObject.NULL)

    private fun fromJson(json: JSONObject): DeltaEntityRevision? = runCatching {
        DeltaEntityRevision(
            entityId = json.getString("entityId"),
            kind = DeltaEntityKind.valueOf(json.getString("kind")),
            revisionId = json.getString("revisionId"),
            parentRevisionId = json.nullable("parentRevisionId"),
            ancestorRevisionIds = json.optJSONArray("ancestorRevisionIds")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf(String::isNotBlank)
                }
            }.orEmpty(),
            modifiedAtEpochMs = json.getLong("modifiedAtEpochMs"),
            deviceId = json.getString("deviceId"),
            deviceName = json.optString("deviceName", "Unknown device"),
            deviceType = json.optString("deviceType", "UNKNOWN"),
            operation = DeltaOperation.valueOf(json.getString("operation")),
            relativePath = json.nullable("relativePath"),
            sizeBytes = if (json.isNull("sizeBytes")) null else json.optLong("sizeBytes"),
            sha256 = json.nullable("sha256"),
            payloadJson = json.nullable("payloadJson")
        )
    }.getOrNull()

    private fun JSONObject.nullable(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private fun groupPayload(value: DesktopTopicGroup) = JSONObject()
        .put("id", value.id).put("name", value.name)
        .put("parentId", value.parentId ?: JSONObject.NULL).put("order", value.order).toString()

    private fun itemPayload(value: DesktopTopicItem) = JSONObject()
        .put("id", value.id).put("type", value.type).put("title", value.title)
        .put("text", value.text ?: JSONObject.NULL).put("target", value.target ?: JSONObject.NULL)
        .put("createdAt", value.createdAt)
        .put("additionalNotes", value.additionalNotes ?: JSONObject.NULL)
        .put("richText", value.richText ?: JSONObject.NULL).toString()

    private fun placementPayload(value: DesktopTopicPlacement) = JSONObject()
        .put("id", value.id).put("groupId", value.groupId).put("itemId", value.itemId)
        .put("order", value.order).put("important", value.important).put("color", value.color)
        .toString()
}
