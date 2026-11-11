package com.praveen.ridernotes.cloud

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

object CloudSyncManager {
    enum class Mode(val label: String) {
        OFF("Off"),
        WIFI_ONLY("Wi-Fi only"),
        ANY_NETWORK("Wi-Fi or mobile data")
    }

    data class Status(
        val destinationName: String?,
        val mode: Mode,
        val message: String,
        val destinationPath: String? = null
    )

    fun destinationUri(context: Context): Uri? = preferences(context)
        .getString(KEY_TREE_URI, null)
        ?.let(Uri::parse)

    fun status(context: Context): Status {
        val uri = destinationUri(context)
        val name = uri?.let {
            runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull()
        }
        val path = uri?.let {
            runCatching {
                val treeId = DocumentsContract.getTreeDocumentId(it)
                val readableTree = Uri.decode(treeId).replace(':', '/')
                val provider = it.authority
                    ?.substringBefore(".documents")
                    ?.substringAfterLast('.')
                    ?.replaceFirstChar(Char::uppercase)
                listOfNotNull(provider, readableTree.takeIf(String::isNotBlank))
                    .joinToString(" › ")
            }.getOrNull() ?: it.toString()
        }
        return Status(
            destinationName = name ?: uri?.lastPathSegment,
            mode = mode(context),
            message = preferences(context).getString(KEY_STATUS, "").orEmpty(),
            destinationPath = path
        )
    }

    fun setDestination(context: Context, uri: Uri) {
        val previous = destinationUri(context)
        preferences(context).edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putString(KEY_STATUS, "Cloud folder selected")
            .apply()
        if (previous != uri) clearUploadMarkers(context)
        schedule(context)
    }

    fun removeDestination(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(IMMEDIATE_WORK)
        }
        preferences(context).edit().clear().apply()
    }

    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(preferences(context).getString(KEY_MODE, Mode.OFF.name)!!)
    }.getOrDefault(Mode.OFF)

    fun setMode(context: Context, mode: Mode) {
        preferences(context).edit().putString(KEY_MODE, mode.name).apply()
        schedule(context)
        if (mode != Mode.OFF) enqueueNow(context)
    }

    fun enqueueNow(context: Context) {
        val selectedMode = mode(context)
        if (destinationUri(context) == null || selectedMode == Mode.OFF) return
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints(selectedMode))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        updateStatus(context, "Sync queued")
    }

    fun noteFiles(context: Context): List<File> {
        val folder = File(context.filesDir, "voice_notes")
        val voiceFiles = folder.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in SYNCED_EXTENSIONS }
        val topicBackup = File(context.filesDir, "topic_library/RiderNotes_Topics_Backup.zip")
        return (voiceFiles + listOfNotNull(topicBackup.takeIf(File::exists)))
            .sortedBy { it.name.lowercase() }
    }

    internal fun signature(file: File): String = "${file.length()}:${file.lastModified()}"

    internal fun uploadedSignature(context: Context, fileName: String): String? =
        preferences(context).getString(markerKey(fileName), null)

    internal fun markUploaded(context: Context, file: File) {
        preferences(context).edit()
            .putString(markerKey(file.name), signature(file))
            .apply()
    }

    internal fun updateStatus(context: Context, message: String) {
        preferences(context).edit().putString(KEY_STATUS, message).apply()
    }

    private fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val selectedMode = mode(context)
        if (destinationUri(context) == null || selectedMode == Mode.OFF) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(selectedMode))
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun constraints(mode: Mode): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (mode == Mode.WIFI_ONLY) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    private fun clearUploadMarkers(context: Context) {
        val prefs = preferences(context)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(MARKER_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    private fun markerKey(fileName: String): String = MARKER_PREFIX + fileName

    private fun preferences(context: Context) = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )

    private const val PREFS = "rider_notes_cloud_sync"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_MODE = "mode"
    private const val KEY_STATUS = "status"
    private const val MARKER_PREFIX = "uploaded:"
    private const val PERIODIC_WORK = "rider-notes-cloud-periodic"
    private const val IMMEDIATE_WORK = "rider-notes-cloud-now"
    private val SYNCED_EXTENSIONS = setOf("m4a", "json", "txt", "vtt", "srt")
}
