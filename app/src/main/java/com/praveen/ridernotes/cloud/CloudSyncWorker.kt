package com.praveen.ridernotes.cloud

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.praveen.ridernotes.topics.TopicLibraryRepository

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val manualUploadAuthorized = inputData.getBoolean(KEY_MANUAL_UPLOAD, false)
        val checkOnly = inputData.getBoolean(KEY_CHECK_ONLY, false) || !manualUploadAuthorized
        if (CloudSyncManager.mode(applicationContext) == CloudSyncManager.Mode.OFF) {
            return Result.success()
        }
        if (CloudSyncManager.destinationUri(
                applicationContext,
                CloudSyncManager.Destination.PRIMARY
            ) == null
        ) {
            return Result.success()
        }
        val destinations = CloudSyncManager.configuredDestinations(applicationContext)
            .map { (destination, uri) ->
                destination to DocumentFile.fromTreeUri(applicationContext, uri)
            }
        if (destinations.isEmpty()) return Result.success()
        val unavailable = destinations.firstOrNull { (_, folder) ->
            folder == null || !folder.canWrite()
        }
        if (unavailable != null) {
            CloudSyncManager.prepareCompleteRetry(applicationContext)
            CloudSyncManager.updateSyncState(
                applicationContext,
                CloudSyncManager.SyncState.FAILED,
                "Upload failed: ${unavailable.first.label} is unavailable. " +
                    "Press retry to mirror everything again."
            )
            return Result.failure()
        }
        val writableDestinations = destinations.map { (destination, folder) ->
            destination to requireNotNull(folder)
        }

        return try {
            val remoteRevision = CloudSyncManager.remoteRevision(applicationContext)
            val lastSynced = SyncRevisionStore.lastSyncedRevision(applicationContext)
            val dirty = SyncRevisionStore.isDirty(applicationContext)
            if (remoteRevision != null && SyncRevisionStore.shouldBlockForRemote(
                    remoteRevisionId = remoteRevision.revisionId,
                    lastSyncedRevisionId = lastSynced,
                    remoteDeviceId = remoteRevision.deviceId,
                    localDeviceId = SyncRevisionStore.deviceId(applicationContext),
                    localDirty = dirty,
                    localParentRevisionId = SyncRevisionStore.revisionForDisplay(applicationContext)
                        ?.parentRevisionId
                )
            ) {
                val local = if (dirty) SyncRevisionStore.currentOrNextRevision(applicationContext) else null
                if (!dirty || local?.parentRevisionId != remoteRevision.revisionId) {
                    SyncRevisionStore.setPendingRemote(applicationContext, remoteRevision)
                    CloudSyncManager.updateSyncState(
                        applicationContext,
                        CloudSyncManager.SyncState.CLOUD_UPDATE_AVAILABLE,
                        "Cloud update available from ${remoteRevision.deviceName}; open Rider Notes to choose"
                    )
                    return Result.success()
                }
            }
            if (checkOnly) {
                if (dirty) {
                    CloudSyncManager.markLocalChange(applicationContext)
                } else {
                    CloudSyncManager.updateSyncState(
                        applicationContext,
                        CloudSyncManager.SyncState.UP_TO_DATE,
                        "Up to date with cloud folder"
                    )
                }
                return Result.success()
            }
            CloudSyncManager.updateSyncState(
                applicationContext,
                CloudSyncManager.SyncState.SYNCING,
                "Uploading Rider Notes changes…"
            )
            val needsDeltaBaseline = writableDestinations.any { (_, folder) ->
                folder.findFile(DeltaCloudTransport.INDEX_FILE) == null
            }
            val needsCheckpoint = needsDeltaBaseline ||
                CloudSyncManager.fullCheckpointUploadPending(applicationContext) ||
                writableDestinations.any { (_, folder) ->
                    folder.findFile(DeltaCloudTransport.FULL_BACKUP_NAME) == null
                }
            TopicLibraryRepository.reconcileDeltaSnapshot(applicationContext)
            val hasPendingDeltas = DeltaChangeJournal.pending(applicationContext).isNotEmpty()
            if (hasPendingDeltas && !dirty) {
                // Defensive reconciliation catches any future file writer which forgets
                // to call markLocalModified after changing a transcript or attachment.
                SyncRevisionStore.markLocalModified(applicationContext)
            }
            if (!dirty && !hasPendingDeltas &&
                remoteRevision != null && remoteRevision.revisionId == lastSynced &&
                !needsDeltaBaseline && !needsCheckpoint &&
                writableDestinations.all { (_, folder) ->
                    folder.findFile(DeltaCloudTransport.INDEX_FILE) != null
                }
            ) {
                CloudSyncManager.updateSyncState(
                    applicationContext,
                    CloudSyncManager.SyncState.UP_TO_DATE,
                    "Up to date with cloud folder"
                )
                return Result.success()
            }

            if (!dirty && (needsDeltaBaseline || needsCheckpoint)) {
                SyncRevisionStore.markLocalModified(applicationContext)
            }
            val checkpoint = if (needsCheckpoint) {
                TopicLibraryRepository.createFreshBackup(applicationContext).getOrThrow()
            } else null
            val localRevision = checkpoint?.let(SyncRevisionStore::revisionInside)
                ?: SyncRevisionStore.currentOrNextRevision(applicationContext)
            val stats = DeltaCloudTransport.upload(
                applicationContext,
                writableDestinations,
                checkpoint
            )
            writableDestinations.forEach { (destination, folder) ->
                val stateTarget = folder.findFile(SyncRevisionStore.REMOTE_STATE_FILE)
                    ?: folder.createFile("application/json", SyncRevisionStore.REMOTE_STATE_FILE)
                    ?: error("Could not create sync state in ${destination.label}")
                applicationContext.contentResolver.openOutputStream(stateTarget.uri, "wt").use { output ->
                    requireNotNull(output)
                    output.write(SyncRevisionStore.toJson(localRevision).toString(2).toByteArray())
                }
            }
            if (checkpoint != null) CloudSyncManager.markFullCheckpointUploaded(applicationContext)
            val fullySynced = SyncRevisionStore.markSynced(applicationContext, localRevision)
            if (fullySynced) {
                CloudSyncManager.updateSyncState(
                    applicationContext,
                    CloudSyncManager.SyncState.COMPLETED,
                    "Delta sync complete across ${writableDestinations.size} cloud folder(s): " +
                        "${stats.uploadedObjects} object(s) uploaded, " +
                        "${stats.unchangedObjects} unchanged" +
                        if (stats.checkpointUploads > 0) {
                            ", ${stats.checkpointUploads} full checkpoint(s) updated"
                        } else ""
                )
            } else {
                CloudSyncManager.markLocalChange(applicationContext)
            }
            Result.success()
        } catch (error: Exception) {
            CloudSyncManager.prepareCompleteRetry(applicationContext)
            CloudSyncManager.updateSyncState(
                applicationContext,
                CloudSyncManager.SyncState.FAILED,
                "Upload failed: ${error.javaClass.simpleName}. Press retry to upload everything again."
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_CHECK_ONLY = "check_only"
        const val KEY_MANUAL_UPLOAD = "manual_upload_authorized"
    }
}
