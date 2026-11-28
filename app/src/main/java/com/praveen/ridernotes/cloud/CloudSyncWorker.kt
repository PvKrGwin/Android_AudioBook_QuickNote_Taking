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
            if (remoteRevision != null && remoteRevision.revisionId != lastSynced) {
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
                "Uploading complete Rider Notes backup…"
            )
            if (!dirty && remoteRevision != null && remoteRevision.revisionId == lastSynced &&
                writableDestinations.all { (_, folder) ->
                    folder.findFile("RiderNotes_Full_Backup.zip") != null
                }
            ) {
                CloudSyncManager.updateSyncState(
                    applicationContext,
                    CloudSyncManager.SyncState.UP_TO_DATE,
                    "Up to date with cloud folder"
                )
                return Result.success()
            }

            val backup = TopicLibraryRepository.createFreshBackup(applicationContext).getOrThrow()
            // Use the revision embedded in the actual ZIP being uploaded. Asking for the
            // current revision again here could accidentally label an older ZIP with a newer
            // transcription revision that arrived while the backup was being built.
            val localRevision = SyncRevisionStore.revisionInside(backup)
                ?: error("Fresh backup has no sync revision")
            var uploaded = 0
            var unchanged = 0
            writableDestinations.forEach destinationLoop@{ (destination, folder) ->
                val existing = folder.listFiles().associateBy { it.name }
                CloudSyncManager.noteFiles(applicationContext).forEach fileLoop@{ local ->
                    val remote = existing[local.name]
                    val currentSignature = CloudSyncManager.signature(local)
                    if (remote != null &&
                        CloudSyncManager.uploadedSignature(
                            applicationContext,
                            destination,
                            local.name
                        ) == currentSignature
                    ) {
                        unchanged += 1
                        return@fileLoop
                    }

                    val target = remote ?: folder.createFile(mimeType(local.extension), local.name)
                        ?: error("Could not create ${local.name} in ${destination.label}")
                    applicationContext.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                        requireNotNull(output) { "Could not open ${local.name}" }
                        local.inputStream().use { input -> input.copyTo(output) }
                    }
                    CloudSyncManager.markUploaded(applicationContext, destination, local)
                    uploaded += 1
                }
                val stateTarget = existing[SyncRevisionStore.REMOTE_STATE_FILE]
                    ?: folder.createFile("application/json", SyncRevisionStore.REMOTE_STATE_FILE)
                    ?: error("Could not create sync state in ${destination.label}")
                applicationContext.contentResolver.openOutputStream(stateTarget.uri, "wt").use { output ->
                    requireNotNull(output)
                    output.write(SyncRevisionStore.toJson(localRevision).toString(2).toByteArray())
                }
            }
            val fullySynced = SyncRevisionStore.markSynced(applicationContext, localRevision)
            if (fullySynced) {
                CloudSyncManager.updateSyncState(
                    applicationContext,
                    CloudSyncManager.SyncState.COMPLETED,
                    "Sync complete across ${writableDestinations.size} cloud folder(s): " +
                        "$uploaded uploaded, $unchanged unchanged"
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

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "m4a" -> "audio/mp4"
        "json" -> "application/json"
        "vtt" -> "text/vtt"
        "srt", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        const val KEY_CHECK_ONLY = "check_only"
        const val KEY_MANUAL_UPLOAD = "manual_upload_authorized"
    }
}
