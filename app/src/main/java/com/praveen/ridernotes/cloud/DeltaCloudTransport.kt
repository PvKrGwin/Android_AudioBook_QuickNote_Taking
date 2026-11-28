package com.praveen.ridernotes.cloud

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Provider-neutral delta transport layered on top of the portable full-backup checkpoint.
 * Ordinary sync changes only the canonical index and missing content-addressed objects.
 */
object DeltaCloudTransport {
    const val INDEX_FILE = "RiderNotes_Delta_Index.json"
    const val OBJECT_DIRECTORY = "RiderNotes_Delta_Objects"
    const val FULL_BACKUP_NAME = "RiderNotes_Full_Backup.zip"

    data class Stats(
        val uploadedObjects: Int,
        val unchangedObjects: Int,
        val checkpointUploads: Int
    )

    fun upload(
        context: Context,
        destinations: List<Pair<CloudSyncManager.Destination, DocumentFile>>,
        checkpoint: File?
    ): Stats {
        val entries = DeltaChangeJournal.entries(context)
        val canonical = entries.map { it.copy(pendingUpload = false) }
        val indexText = DeltaChangeJournal.encode(canonical)
        var uploaded = 0
        var unchanged = 0
        var checkpoints = 0

        destinations.forEach { (_, root) ->
            val existingCheckpoint = root.findFile(FULL_BACKUP_NAME)
            if (checkpoint != null || existingCheckpoint == null) {
                val source = checkpoint
                    ?: error("Cloud has no full checkpoint; create a fresh full backup first")
                val target = existingCheckpoint
                    ?: root.createFile("application/zip", FULL_BACKUP_NAME)
                    ?: error("Could not create $FULL_BACKUP_NAME")
                context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                    requireNotNull(output)
                    source.inputStream().use { it.copyTo(output) }
                }
                checkpoints += 1
            }
            val usingFreshCheckpoint = checkpoint != null
            val objects = root.findFile(OBJECT_DIRECTORY)
                ?: root.createDirectory(OBJECT_DIRECTORY)
                ?: error("Could not create $OBJECT_DIRECTORY")
            val writtenObjectNames = mutableSetOf<String>()
            entries.asSequence()
                .filter(DeltaChangeJournal.Entry::pendingUpload)
                .map(DeltaChangeJournal.Entry::revision)
                .filter {
                    it.operation == DeltaOperation.UPSERT &&
                        it.sha256 != null && it.relativePath != null
                }
                .forEach { revision ->
                    if (usingFreshCheckpoint) {
                        unchanged += 1
                        return@forEach
                    }
                    val objectName = objectName(revision)
                    if (!writtenObjectNames.add(objectName)) {
                        unchanged += 1
                        return@forEach
                    }
                    val existing = objects.findFile(objectName)
                    val local = localFile(context, revision)
                        ?: error("Local delta file is missing: ${revision.relativePath}")
                    val target = existing
                        ?: objects.createFile("application/octet-stream", objectName)
                        ?: error("Could not create delta object $objectName")
                    context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                        requireNotNull(output)
                        local.inputStream().use { it.copyTo(output) }
                    }
                    uploaded += 1
                }
            writeText(context, root, INDEX_FILE, indexText)
            if (usingFreshCheckpoint) {
                // Every active file revision is now present inside the freshly uploaded
                // checkpoint, so older immutable delta objects are safe to compact away.
                objects.listFiles().forEach { it.delete() }
            }
        }
        DeltaChangeJournal.acknowledge(
            context,
            canonical.mapTo(mutableSetOf()) { it.revision.revisionId }
        )
        return Stats(uploaded, unchanged, checkpoints)
    }

    fun overlayRemoteOnBackup(
        context: Context,
        destination: CloudSyncManager.Destination,
        backup: File,
        revision: CloudRevision?
    ): File {
        val root = CloudSyncManager.destinationDocument(context, destination) ?: return backup
        val indexDocument = root.findFile(INDEX_FILE) ?: return backup
        val indexText = context.contentResolver.openInputStream(indexDocument.uri)
            ?.bufferedReader()?.use { it.readText() }
            ?: return backup
        val entries = DeltaChangeJournal.decode(indexText)
        if (entries.isEmpty()) return backup
        val tempRoot = File(context.cacheDir, "delta_overlay_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            ZipInputStream(backup.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(tempRoot, entry.name).canonicalFile
                    require(target.path.startsWith(tempRoot.canonicalPath + File.separator))
                    if (!entry.isDirectory) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            applyTopicPayloads(tempRoot, entries.map(DeltaChangeJournal.Entry::revision))
            applyFileObjects(context, root, tempRoot, entries.map(DeltaChangeJournal.Entry::revision))
            File(tempRoot, DeltaChangeJournal.FILE_NAME).writeText(
                DeltaChangeJournal.encode(entries.map { it.copy(pendingUpload = false) })
            )
            revision?.let {
                File(tempRoot, SyncRevisionStore.MANIFEST_FILE)
                    .writeText(SyncRevisionStore.toJson(it).toString(2))
            }
            val rebuilt = File(backup.parentFile, ".${backup.name}.delta.tmp")
            ZipOutputStream(rebuilt.outputStream().buffered()).use { zip ->
                tempRoot.walkTopDown().filter(File::isFile).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(tempRoot).invariantSeparatorsPath))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            rebuilt.copyTo(backup, overwrite = true)
            rebuilt.delete()
            return backup
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun applyTopicPayloads(root: File, revisions: List<DeltaEntityRevision>) {
        val topicKinds = setOf(
            DeltaEntityKind.TOPIC_GROUP,
            DeltaEntityKind.TOPIC_ITEM,
            DeltaEntityKind.TOPIC_PLACEMENT,
            DeltaEntityKind.TOPIC_LEGEND
        )
        if (revisions.none { it.kind in topicKinds }) return
        val active = revisions.filter { it.operation == DeltaOperation.UPSERT }
        val groups = active.filter { it.kind == DeltaEntityKind.TOPIC_GROUP }
            .mapNotNull { it.payloadJson?.let(::JSONObject) }
            .sortedBy { it.optInt("order") }
        val items = active.filter { it.kind == DeltaEntityKind.TOPIC_ITEM }
            .mapNotNull { it.payloadJson?.let(::JSONObject) }
        val placements = active.filter { it.kind == DeltaEntityKind.TOPIC_PLACEMENT }
            .mapNotNull { it.payloadJson?.let(::JSONObject) }
            .sortedBy { it.optInt("order") }
        val legends = active.filter { it.kind == DeltaEntityKind.TOPIC_LEGEND }
            .mapNotNull { it.payloadJson?.let(::JSONObject) }
        val library = JSONObject()
            .put("version", 2)
            .put("groups", JSONArray(groups))
            .put("items", JSONArray(items))
            .put("placements", JSONArray(placements))
            .put("colorLegend", JSONObject().apply {
                legends.forEach { put(it.optString("color"), it.optString("meaning")) }
            })
        File(root, "library.json").writeText(library.toString(2))
    }

    private fun applyFileObjects(
        context: Context,
        cloudRoot: DocumentFile,
        extractedRoot: File,
        revisions: List<DeltaEntityRevision>
    ) {
        val objects = cloudRoot.findFile(OBJECT_DIRECTORY)
        revisions.filter { it.relativePath != null }.forEach { revision ->
            val relative = archivePath(revision.relativePath ?: return@forEach)
            val target = File(extractedRoot, relative).canonicalFile
            require(target.path.startsWith(extractedRoot.canonicalPath + File.separator))
            if (revision.operation == DeltaOperation.DELETE) {
                target.delete()
                return@forEach
            }
            val source = objects?.findFile(objectName(revision))
            if (source == null && target.isFile &&
                target.length() == revision.sizeBytes &&
                DeltaContentHash.sha256(target) == revision.sha256
            ) {
                return@forEach
            }
            val requiredSource = requireNotNull(source) {
                "Cloud delta object is missing for ${revision.entityId}"
            }
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(requiredSource.uri).use { input ->
                requireNotNull(input)
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun localFile(context: Context, revision: DeltaEntityRevision): File? {
        val relative = revision.relativePath ?: return null
        val file = when {
            relative.startsWith("recordings/") ->
                File(context.filesDir, "voice_notes/${relative.substringAfterLast('/')}")
            relative.startsWith("topic_library/") ->
                File(context.filesDir, relative)
            else -> File(context.filesDir, relative)
        }
        return file.takeIf(File::isFile)
    }

    private fun archivePath(relativePath: String): String =
        relativePath.removePrefix("topic_library/")

    private fun objectName(revision: DeltaEntityRevision): String {
        val extension = revision.relativePath?.substringAfterLast('.', "")
            ?.takeIf(String::isNotBlank)
        return revision.sha256.orEmpty() + extension?.let { ".$it" }.orEmpty()
    }

    private fun writeText(context: Context, root: DocumentFile, name: String, text: String) {
        val target = root.findFile(name)
            ?: root.createFile("application/json", name)
            ?: error("Could not create $name")
        context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
            requireNotNull(output)
            output.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
