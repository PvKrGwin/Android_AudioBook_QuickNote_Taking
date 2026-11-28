package com.praveen.ridernotes.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/** Delta overlay stored beside the portable recovery ZIP in the Drive-synchronized folder. */
object DesktopDeltaCloudTransport {
    const val INDEX_FILE = "RiderNotes_Delta_Index.json"
    const val OBJECT_DIRECTORY = "RiderNotes_Delta_Objects"

    data class Result(
        val uploadedObjects: Int,
        val unchangedObjects: Int,
        val secondaryMirrored: Boolean,
        val secondaryError: String? = null
    )

    fun publish(
        snapshot: BackupSnapshot,
        entries: List<DesktopDeltaJournal.Entry>,
        manifest: SyncManifest,
        secondaryZip: File?
    ): Result {
        val canonical = entries.map { it.copy(pendingUpload = false) }
        val primaryRoot = snapshot.sourceZip.absoluteFile.parentFile
            ?: error("Cloud 1 folder is unavailable")
        val initializingStore = !File(primaryRoot, INDEX_FILE).isFile
        val primaryStats = writeStore(
            primaryRoot,
            snapshot,
            entries,
            manifest,
            initializingStore
        )
        val secondaryResult = secondaryZip?.absoluteFile?.let { targetZip ->
            runCatching {
                val secondaryRoot = targetZip.parentFile
                    ?: error("Cloud 2 folder is unavailable")
                secondaryRoot.mkdirs()
                if (!targetZip.exists() || !File(secondaryRoot, INDEX_FILE).isFile) {
                    // A newly added/legacy secondary needs the same checkpoint baseline
                    // before the shared delta index can safely reference checkpoint files.
                    atomicCopy(snapshot.sourceZip, targetZip)
                }
                mirrorStore(primaryRoot, secondaryRoot, canonical, manifest)
            }
        }
        return Result(
            uploadedObjects = primaryStats.first,
            unchangedObjects = primaryStats.second,
            secondaryMirrored = secondaryResult?.isSuccess == true,
            secondaryError = secondaryResult?.exceptionOrNull()?.message
        )
    }

    fun overlay(zipFile: File, extractedRoot: File) {
        val cloudRoot = zipFile.absoluteFile.parentFile ?: return
        val indexFile = File(cloudRoot, INDEX_FILE)
        if (!indexFile.isFile) return
        val entries = DesktopDeltaJournal.read(indexFile)
        if (entries.isEmpty()) return
        val revisions = entries.map(DesktopDeltaJournal.Entry::revision)
        applyTopicPayloads(extractedRoot, revisions)
        applyFileObjects(cloudRoot, extractedRoot, revisions)
        DesktopDeltaJournal.write(
            File(extractedRoot, DesktopDeltaJournal.FILE_NAME),
            entries.map { it.copy(pendingUpload = false) }
        )
        val state = File(cloudRoot, SyncManifestCodec.SIBLING_FILE_NAME)
        if (state.isFile) {
            state.copyTo(File(extractedRoot, SyncManifestCodec.FILE_NAME), overwrite = true)
        }
    }

    private fun writeStore(
        cloudRoot: File,
        snapshot: BackupSnapshot,
        entries: List<DesktopDeltaJournal.Entry>,
        manifest: SyncManifest,
        initializingStore: Boolean
    ): Pair<Int, Int> {
        cloudRoot.mkdirs()
        val objects = File(cloudRoot, OBJECT_DIRECTORY).apply { mkdirs() }
        val checkpointRevisionIds = if (initializingStore) {
            checkpointRevisionIds(snapshot.sourceZip, entries)
        } else emptySet()
        var uploaded = 0
        var unchanged = 0
        entries.asSequence()
            .filter(DesktopDeltaJournal.Entry::pendingUpload)
            .map(DesktopDeltaJournal.Entry::revision)
            .filter {
                it.operation == DeltaOperation.UPSERT &&
                    it.sha256 != null && it.relativePath != null
            }
            .forEach { revision ->
                val target = File(objects, objectName(revision))
                if (target.isFile && target.length() == revision.sizeBytes) {
                    unchanged += 1
                } else if (revision.revisionId in checkpointRevisionIds) {
                    unchanged += 1
                } else {
                    val source = localFile(snapshot, revision)
                        ?: error("Local delta file is missing: ${revision.relativePath}")
                    atomicCopy(source, target)
                    uploaded += 1
                }
            }
        DesktopDeltaJournal.write(
            File(cloudRoot, INDEX_FILE),
            entries.map { it.copy(pendingUpload = false) }
        )
        SyncManifestCodec.write(File(cloudRoot, SyncManifestCodec.SIBLING_FILE_NAME), manifest)
        return uploaded to unchanged
    }

    private fun mirrorStore(
        primaryRoot: File,
        secondaryRoot: File,
        entries: List<DesktopDeltaJournal.Entry>,
        manifest: SyncManifest
    ) {
        val primaryObjects = File(primaryRoot, OBJECT_DIRECTORY)
        val secondaryObjects = File(secondaryRoot, OBJECT_DIRECTORY).apply { mkdirs() }
        entries.map(DesktopDeltaJournal.Entry::revision)
            .filter {
                it.operation == DeltaOperation.UPSERT &&
                    it.sha256 != null && it.relativePath != null
            }
            .forEach { revision ->
                val name = objectName(revision)
                val target = File(secondaryObjects, name)
                val source = File(primaryObjects, name)
                if (!target.exists() && source.isFile) atomicCopy(source, target)
            }
        DesktopDeltaJournal.write(File(secondaryRoot, INDEX_FILE), entries)
        SyncManifestCodec.write(File(secondaryRoot, SyncManifestCodec.SIBLING_FILE_NAME), manifest)
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
        File(root, "library.json").writeText(
            JSONObject()
                .put("version", 2)
                .put("groups", JSONArray(groups))
                .put("items", JSONArray(items))
                .put("placements", JSONArray(placements))
                .put("colorLegend", JSONObject().apply {
                    legends.forEach { put(it.optString("color"), it.optString("meaning")) }
                })
                .toString(2)
        )
    }

    private fun applyFileObjects(
        cloudRoot: File,
        extractedRoot: File,
        revisions: List<DeltaEntityRevision>
    ) {
        val objects = File(cloudRoot, OBJECT_DIRECTORY)
        revisions.filter { it.relativePath != null }.forEach { revision ->
            val target = File(extractedRoot, archivePath(revision.relativePath.orEmpty())).canonicalFile
            require(target.path.startsWith(extractedRoot.canonicalPath + File.separator))
            if (revision.operation == DeltaOperation.DELETE) {
                target.delete()
            } else {
                val source = File(objects, objectName(revision))
                if (!source.isFile && target.isFile &&
                    target.length() == revision.sizeBytes &&
                    DeltaContentHash.sha256(target) == revision.sha256
                ) {
                    return@forEach
                }
                require(source.isFile) { "Cloud delta object is missing for ${revision.entityId}" }
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private fun localFile(snapshot: BackupSnapshot, revision: DeltaEntityRevision): File? {
        val relative = revision.relativePath ?: return null
        return File(snapshot.extractedRoot, archivePath(relative)).takeIf(File::isFile)
    }

    private fun checkpointRevisionIds(
        zip: File,
        entries: List<DesktopDeltaJournal.Entry>
    ): Set<String> {
        if (!zip.isFile) return emptySet()
        return ZipFile(zip).use { archive ->
            entries.asSequence()
                .filter(DesktopDeltaJournal.Entry::pendingUpload)
                .map(DesktopDeltaJournal.Entry::revision)
                .filter { revision ->
                    val relativePath = revision.relativePath ?: return@filter false
                    val expectedHash = revision.sha256 ?: return@filter false
                    val entry = archive.getEntry(archivePath(relativePath)) ?: return@filter false
                    !entry.isDirectory && entry.size == revision.sizeBytes &&
                        archive.getInputStream(entry).buffered().use { input ->
                            DeltaContentHash.sha256(input) == expectedHash
                        }
                }
                .mapTo(mutableSetOf(), DeltaEntityRevision::revisionId)
            }
    }

    private fun archivePath(relativePath: String): String =
        relativePath.removePrefix("topic_library/")

    private fun objectName(revision: DeltaEntityRevision): String {
        val extension = revision.relativePath?.substringAfterLast('.', "")
            ?.takeIf(String::isNotBlank)
        return revision.sha256.orEmpty() + extension?.let { ".$it" }.orEmpty()
    }

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.rider-delta.tmp")
        Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
