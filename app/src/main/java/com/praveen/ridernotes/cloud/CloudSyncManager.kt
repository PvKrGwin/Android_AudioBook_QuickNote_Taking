package com.praveen.ridernotes.cloud

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

object CloudSyncManager {
    enum class Mode(val label: String) {
        OFF("Off"),
        WIFI_ONLY("Wi-Fi only"),
        ANY_NETWORK("Wi-Fi or mobile data")
    }

    enum class SyncState {
        DISABLED,
        UP_TO_DATE,
        RECOMMENDED,
        OFFLINE_PENDING,
        QUEUED,
        SYNCING,
        FAILED,
        COMPLETED,
        CLOUD_UPDATE_AVAILABLE
    }

    data class Status(
        val destinationName: String?,
        val mode: Mode,
        val message: String,
        val destinationPath: String? = null,
        val destinationWebUrl: String? = null,
        val syncRecommended: Boolean = false,
        val pendingRemote: CloudRevision? = null,
        val localRevision: CloudRevision? = null,
        val cloudRevision: CloudRevision? = null,
        val syncState: SyncState = SyncState.DISABLED
    )

    fun destinationUri(context: Context): Uri? = preferences(context)
        .getString(KEY_TREE_URI, null)
        ?.let(Uri::parse)

    fun status(context: Context): Status {
        val uri = destinationUri(context)
        val name = uri?.let {
            runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull()
        }
        val selectedMode = mode(context)
        val dirty = SyncRevisionStore.syncRecommended(context)
        val prefs = preferences(context)
        val storedState = if (!prefs.contains(KEY_SYNC_STATE)) {
            if (dirty) SyncState.RECOMMENDED else SyncState.UP_TO_DATE
        } else runCatching {
            SyncState.valueOf(prefs.getString(KEY_SYNC_STATE, SyncState.DISABLED.name)!!)
        }.getOrDefault(SyncState.DISABLED)
        val effectiveState = when {
            uri == null || selectedMode == Mode.OFF -> SyncState.DISABLED
            SyncRevisionStore.pendingRemote(context) != null -> SyncState.CLOUD_UPDATE_AVAILABLE
            storedState == SyncState.FAILED -> SyncState.FAILED
            dirty && storedState !in setOf(SyncState.QUEUED, SyncState.SYNCING) ->
                if (isNetworkReady(context, selectedMode)) SyncState.RECOMMENDED
                else SyncState.OFFLINE_PENDING
            !dirty && storedState in setOf(
                SyncState.RECOMMENDED,
                SyncState.OFFLINE_PENDING,
                SyncState.QUEUED,
                SyncState.SYNCING,
                SyncState.CLOUD_UPDATE_AVAILABLE
            ) -> SyncState.UP_TO_DATE
            else -> storedState
        }
        return Status(
            destinationName = name ?: uri?.lastPathSegment,
            mode = selectedMode,
            message = preferences(context).getString(KEY_STATUS, "").orEmpty(),
            destinationPath = name,
            destinationWebUrl = preferences(context)
                .getString(KEY_WEB_URL, null)
                ?.takeIf(String::isNotBlank),
            syncRecommended = dirty,
            pendingRemote = SyncRevisionStore.pendingRemote(context),
            localRevision = SyncRevisionStore.localRevisionForDisplay(context),
            cloudRevision = SyncRevisionStore.cloudRevisionForDisplay(context),
            syncState = effectiveState
        )
    }

    fun setDestinationWebUrl(context: Context, value: String): Boolean {
        val normalized = value.trim()
        val valid = runCatching {
            val uri = Uri.parse(normalized)
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        if (!valid) return false
        preferences(context).edit().putString(KEY_WEB_URL, normalized).apply()
        return true
    }

    fun setDestination(context: Context, uri: Uri) {
        val previous = destinationUri(context)
        preferences(context).edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putString(KEY_STATUS, "Cloud folder selected")
            .putString(KEY_SYNC_STATE, SyncState.UP_TO_DATE.name)
            .apply()
        if (previous != uri) {
            preferences(context).edit().remove(KEY_WEB_URL).apply()
            clearUploadMarkers(context)
        }
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
        preferences(context).edit()
            .putString(KEY_MODE, mode.name)
            .putString(
                KEY_SYNC_STATE,
                if (mode == Mode.OFF) SyncState.DISABLED.name
                else if (SyncRevisionStore.isDirty(context)) SyncState.RECOMMENDED.name
                else SyncState.UP_TO_DATE.name
            )
            .apply()
        schedule(context)
    }

    fun enqueueNow(context: Context) {
        val selectedMode = mode(context)
        if (destinationUri(context) == null || selectedMode == Mode.OFF) return
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(constraints(selectedMode))
            .setInputData(workDataOf(CloudSyncWorker.KEY_MANUAL_UPLOAD to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        if (isNetworkReady(context, selectedMode)) {
            updateSyncState(context, SyncState.QUEUED, "Sync queued")
        } else {
            updateSyncState(
                context,
                SyncState.OFFLINE_PENDING,
                "Offline — sync will start automatically when the required connection returns"
            )
        }
    }

    fun noteFiles(context: Context): List<File> {
        val folder = File(context.filesDir, "voice_notes")
        val voiceFiles = folder.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in SYNCED_EXTENSIONS }
        val topicBackup = File(context.filesDir, "topic_library/RiderNotes_Topics_Backup.zip")
        val fullBackup = File(context.filesDir, "topic_library/RiderNotes_Full_Backup.zip")
        return (voiceFiles + listOfNotNull(
            topicBackup.takeIf(File::exists),
            fullBackup.takeIf(File::exists)
        ))
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

    internal fun updateSyncState(context: Context, state: SyncState, message: String) {
        preferences(context).edit()
            .putString(KEY_SYNC_STATE, state.name)
            .putLong(KEY_SYNC_STATE_AT, System.currentTimeMillis())
            .putString(KEY_STATUS, message)
            .apply()
    }

    /**
     * Keeps the green completion acknowledgement visible briefly, then returns the idle
     * control to the ordinary up-to-date state. A new local edit always wins and prevents
     * this transition from hiding the recommended/offline state.
     *
     * @return milliseconds remaining before the completion state can settle, or 0 when
     * no delayed refresh is needed.
     */
    fun settleCompletedIfReady(context: Context): Long {
        val prefs = preferences(context)
        val state = runCatching {
            SyncState.valueOf(prefs.getString(KEY_SYNC_STATE, SyncState.DISABLED.name)!!)
        }.getOrDefault(SyncState.DISABLED)
        if (state != SyncState.COMPLETED || SyncRevisionStore.isDirty(context)) return 0L
        val elapsed = System.currentTimeMillis() - prefs.getLong(
            KEY_SYNC_STATE_AT,
            System.currentTimeMillis()
        )
        val remaining = COMPLETION_CONFIRMATION_MS - elapsed
        if (remaining > 0L) return remaining
        updateSyncState(context, SyncState.UP_TO_DATE, "Up to date with cloud folder")
        return 0L
    }

    internal fun prepareCompleteRetry(context: Context) {
        clearUploadMarkers(context)
    }

    fun markLocalChange(context: Context) {
        val selectedMode = mode(context)
        if (destinationUri(context) == null || selectedMode == Mode.OFF) return
        updateSyncState(
            context,
            if (isNetworkReady(context, selectedMode)) SyncState.RECOMMENDED
            else SyncState.OFFLINE_PENDING,
            if (isNetworkReady(context, selectedMode)) {
                "Local changes are ready — upload is recommended"
            } else {
                "Local changes are waiting — upload will be available when internet returns"
            }
        )
    }

    fun registerStatusListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = preferences(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterStatusListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = preferences(context).unregisterOnSharedPreferenceChangeListener(listener)

    fun isNetworkReady(context: Context, selectedMode: Mode = mode(context)): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return selectedMode != Mode.WIFI_ONLY ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun remoteRevision(context: Context): CloudRevision? {
        val treeUri = destinationUri(context) ?: return null
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val state = folder.findFile(SyncRevisionStore.REMOTE_STATE_FILE) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(state.uri)?.bufferedReader()?.use { reader ->
                SyncRevisionStore.parse(reader.readText())
            }
        }.getOrNull()?.also { SyncRevisionStore.rememberCloudRevision(context, it) }
    }

    fun inspectRemote(context: Context): CloudRevision? {
        val remote = remoteRevision(context) ?: return null
        val lastSynced = SyncRevisionStore.lastSyncedRevision(context)
        return if (remote.revisionId != lastSynced &&
            remote.deviceId != SyncRevisionStore.deviceId(context)
        ) {
            SyncRevisionStore.setPendingRemote(context, remote)
            remote
        } else {
            SyncRevisionStore.setPendingRemote(context, null)
            null
        }
    }

    fun downloadRemoteFullBackup(context: Context): File? {
        val treeUri = destinationUri(context) ?: return null
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val remote = folder.findFile(FULL_BACKUP_NAME) ?: return null
        return runCatching {
            File(context.cacheDir, "remote_${System.currentTimeMillis()}_$FULL_BACKUP_NAME").also { target ->
                context.contentResolver.openInputStream(remote.uri).use { input ->
                    requireNotNull(input)
                    target.outputStream().use(input::copyTo)
                }
            }
        }.getOrNull()
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
            .setInputData(workDataOf(CloudSyncWorker.KEY_CHECK_ONLY to true))
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
    private const val KEY_SYNC_STATE = "sync_state"
    private const val KEY_SYNC_STATE_AT = "sync_state_at"
    private const val KEY_WEB_URL = "web_url"
    private const val MARKER_PREFIX = "uploaded:"
    private const val PERIODIC_WORK = "rider-notes-cloud-periodic"
    private const val IMMEDIATE_WORK = "rider-notes-cloud-now"
    private const val FULL_BACKUP_NAME = "RiderNotes_Full_Backup.zip"
    private const val COMPLETION_CONFIRMATION_MS = 5_000L
    private val SYNCED_EXTENSIONS = setOf("m4a", "json", "txt", "vtt", "srt")
}
