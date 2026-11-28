package com.praveen.ridernotes.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupPublisher {
    data class PublishResult(
        val snapshot: BackupSnapshot,
        val secondaryMirrored: Boolean,
        val secondaryError: String? = null
    )

    fun publish(
        snapshot: BackupSnapshot,
        forceAgainstRemote: SyncManifest? = null
    ): BackupSnapshot = publishDetailed(snapshot, forceAgainstRemote).snapshot

    fun publishDetailed(
        snapshot: BackupSnapshot,
        forceAgainstRemote: SyncManifest? = null
    ): PublishResult {
        val remote = remoteManifestFor(snapshot.sourceZip)
        val openedRevision = snapshot.syncManifest?.revisionId
        val expectedRevision = forceAgainstRemote?.revisionId ?: openedRevision
        require(remote == null || remote.revisionId == expectedRevision) {
            "The Drive backup changed after it was opened (${remote?.deviceName ?: "another device"}). " +
                "Reopen it before publishing so those changes are not overwritten."
        }
        val nextManifest = SyncManifestCodec.next(forceAgainstRemote ?: snapshot.syncManifest)
        SyncManifestCodec.write(
            File(snapshot.extractedRoot, SyncManifestCodec.FILE_NAME),
            nextManifest
        )
        // Delta metadata must never make the established portable ZIP path unusable.
        // A later publish retries reconciliation if the journal could not be written.
        runCatching { DesktopDeltaJournal.reconcile(snapshot) }

        val source = snapshot.sourceZip.absoluteFile
        source.parentFile?.mkdirs()
        val temp = File(source.parentFile, ".${source.name}.rider-notes.tmp")
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            snapshot.extractedRoot.walkTopDown()
                .filter(File::isFile)
                .sortedBy { it.relativeTo(snapshot.extractedRoot).invariantSeparatorsPath }
                .forEach { file ->
                    val name = file.relativeTo(snapshot.extractedRoot).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        runCatching {
            Files.move(
                temp.toPath(),
                source.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(temp.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        SyncManifestCodec.write(
            File(source.parentFile, SyncManifestCodec.SIBLING_FILE_NAME),
            nextManifest
        )
        DesktopDeviceIdentity.rememberBackupPath(source.absolutePath)
        val secondary = DesktopDeviceIdentity.secondaryBackupPath()
            ?.let(::File)
            ?.absoluteFile
            ?.takeUnless { target ->
                runCatching { target.canonicalFile == source.canonicalFile }.getOrDefault(false)
            }
        val mirrorResult = secondary?.let { target ->
            runCatching {
                atomicCopy(source, target)
                atomicCopy(
                    File(source.parentFile, SyncManifestCodec.SIBLING_FILE_NAME),
                    File(target.parentFile, SyncManifestCodec.SIBLING_FILE_NAME)
                )
            }
        }
        return PublishResult(
            snapshot = BackupImporter.import(source),
            secondaryMirrored = mirrorResult?.isSuccess == true,
            secondaryError = mirrorResult?.exceptionOrNull()?.message
        )
    }

    fun remoteManifestFor(zipFile: File): SyncManifest? =
        SyncManifestCodec.read(File(zipFile.parentFile, SyncManifestCodec.SIBLING_FILE_NAME))

    private fun atomicCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.rider-notes-mirror.tmp")
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
