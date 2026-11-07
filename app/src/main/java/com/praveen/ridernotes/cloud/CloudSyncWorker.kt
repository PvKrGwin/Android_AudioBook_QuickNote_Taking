package com.praveen.ridernotes.cloud

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (CloudSyncManager.mode(applicationContext) == CloudSyncManager.Mode.OFF) {
            return Result.success()
        }
        val treeUri = CloudSyncManager.destinationUri(applicationContext)
            ?: return Result.success()
        val folder = DocumentFile.fromTreeUri(applicationContext, treeUri)
        if (folder == null || !folder.canWrite()) {
            CloudSyncManager.updateStatus(applicationContext, "Cloud folder is unavailable")
            return Result.failure()
        }

        return try {
            var uploaded = 0
            var unchanged = 0
            val existing = folder.listFiles().associateBy { it.name }
            CloudSyncManager.noteFiles(applicationContext).forEach { local ->
                val remote = existing[local.name]
                val currentSignature = CloudSyncManager.signature(local)
                if (remote != null &&
                    CloudSyncManager.uploadedSignature(applicationContext, local.name) == currentSignature
                ) {
                    unchanged += 1
                    return@forEach
                }

                val target = remote ?: folder.createFile(mimeType(local.extension), local.name)
                    ?: error("Could not create ${local.name}")
                applicationContext.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                    requireNotNull(output) { "Could not open ${local.name}" }
                    local.inputStream().use { input -> input.copyTo(output) }
                }
                CloudSyncManager.markUploaded(applicationContext, local)
                uploaded += 1
            }
            CloudSyncManager.updateStatus(
                applicationContext,
                "Sync complete: $uploaded uploaded, $unchanged unchanged"
            )
            Result.success()
        } catch (error: Exception) {
            CloudSyncManager.updateStatus(
                applicationContext,
                "Sync paused: ${error.javaClass.simpleName}"
            )
            Result.retry()
        }
    }

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "m4a" -> "audio/mp4"
        "json" -> "application/json"
        "vtt" -> "text/vtt"
        "srt", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
