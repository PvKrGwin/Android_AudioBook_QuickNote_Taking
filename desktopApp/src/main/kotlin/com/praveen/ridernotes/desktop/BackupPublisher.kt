package com.praveen.ridernotes.desktop

import java.io.File

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
        val entries = DesktopDeltaJournal.reconcile(snapshot)
        val source = snapshot.sourceZip.absoluteFile
        val deltaResult = DesktopDeltaCloudTransport.publish(
            snapshot = snapshot,
            entries = entries,
            manifest = nextManifest,
            secondaryZip = DesktopDeviceIdentity.secondaryBackupPath()?.let(::File)
        )
        DesktopDeltaJournal.write(
            File(snapshot.extractedRoot, DesktopDeltaJournal.FILE_NAME),
            entries.map { it.copy(pendingUpload = false) }
        )
        DesktopDeviceIdentity.rememberBackupPath(source.absolutePath)
        val secondary = DesktopDeviceIdentity.secondaryBackupPath()
            ?.let(::File)
            ?.absoluteFile
            ?.takeUnless { target ->
                runCatching { target.canonicalFile == source.canonicalFile }.getOrDefault(false)
            }
        return PublishResult(
            snapshot = BackupImporter.reload(snapshot),
            secondaryMirrored = secondary != null && deltaResult.secondaryMirrored,
            secondaryError = deltaResult.secondaryError
        )
    }

    fun remoteManifestFor(zipFile: File): SyncManifest? =
        SyncManifestCodec.read(File(zipFile.parentFile, SyncManifestCodec.SIBLING_FILE_NAME))

}
