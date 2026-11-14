package com.praveen.ridernotes.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupPublisher {
    fun publish(
        snapshot: BackupSnapshot,
        forceAgainstRemote: SyncManifest? = null
    ): BackupSnapshot {
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
        return BackupImporter.import(source)
    }

    fun remoteManifestFor(zipFile: File): SyncManifest? =
        SyncManifestCodec.read(File(zipFile.parentFile, SyncManifestCodec.SIBLING_FILE_NAME))
}
