package com.praveen.ridernotes.cloud

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class CloudRevision(
    val schemaVersion: Int = 1,
    val revisionId: String,
    val parentRevisionId: String?,
    val modifiedAtEpochMs: Long,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String
)

object SyncRevisionStore {
    const val MANIFEST_FILE = "rider_notes_sync_manifest.json"
    const val REMOTE_STATE_FILE = "RiderNotes_Sync_State.json"

    fun deviceId(context: Context): String {
        val prefs = preferences(context)
        val current = prefs.getString(KEY_DEVICE_ID, null)
        if (!current.isNullOrBlank()) return current
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
        .ifBlank { "Android phone" }

    fun markLocalModified(context: Context) {
        val prefs = preferences(context)
        prefs.edit()
            .putLong(KEY_DIRTY_AT, System.currentTimeMillis())
            .putBoolean(KEY_DIRTY, true)
            // Any already-prepared manifest predates this edit and must not be reused.
            .remove(KEY_PENDING_LOCAL)
            .apply()
        CloudSyncManager.markLocalChange(context)
    }

    fun isDirty(context: Context): Boolean = preferences(context).getBoolean(KEY_DIRTY, false)

    fun syncRecommended(context: Context): Boolean = isDirty(context)

    fun lastSyncedRevision(context: Context): String? =
        preferences(context).getString(KEY_LAST_SYNCED_REVISION, null)

    /** Most recent local/cloud revision known by this phone, for sync-status display. */
    fun revisionForDisplay(context: Context): CloudRevision? {
        val prefs = preferences(context)
        return prefs.getString(KEY_PENDING_LOCAL, null)?.let(::parse)
            ?: prefs.getString(KEY_LAST_KNOWN_REVISION, null)?.let(::parse)
    }

    fun localRevisionForDisplay(context: Context): CloudRevision? {
        val prefs = preferences(context)
        return prefs.getString(KEY_PENDING_LOCAL, null)?.let(::parse)
            ?: if (prefs.getBoolean(KEY_DIRTY, false)) {
                CloudRevision(
                    revisionId = "pending-local",
                    parentRevisionId = lastSyncedRevision(context),
                    modifiedAtEpochMs = prefs.getLong(KEY_DIRTY_AT, System.currentTimeMillis()),
                    deviceId = deviceId(context),
                    deviceName = deviceName(),
                    deviceType = "ANDROID"
                )
            } else {
                prefs.getString(KEY_LAST_KNOWN_REVISION, null)?.let(::parse)
            }
    }

    fun cloudRevisionForDisplay(context: Context): CloudRevision? {
        val prefs = preferences(context)
        return pendingRemote(context)
            ?: prefs.getString(KEY_LAST_SEEN_CLOUD, null)?.let(::parse)
            ?: prefs.getString(KEY_LAST_KNOWN_REVISION, null)?.let(::parse)
    }

    fun rememberCloudRevision(context: Context, revision: CloudRevision) {
        preferences(context).edit()
            .putString(KEY_LAST_SEEN_CLOUD, toJson(revision).toString())
            .apply()
    }

    fun pendingRemote(context: Context): CloudRevision? =
        preferences(context).getString(KEY_PENDING_REMOTE, null)
            ?.let(::parse)

    fun setPendingRemote(context: Context, revision: CloudRevision?) {
        preferences(context).edit().apply {
            if (revision == null) remove(KEY_PENDING_REMOTE)
            else {
                putString(KEY_PENDING_REMOTE, toJson(revision).toString())
                putString(KEY_LAST_SEEN_CLOUD, toJson(revision).toString())
            }
        }.apply()
    }

    fun currentOrNextRevision(context: Context, forceNew: Boolean = false): CloudRevision {
        val prefs = preferences(context)
        val existing = prefs.getString(KEY_PENDING_LOCAL, null)?.let(::parse)
        if (existing != null && !forceNew) return existing
        val pendingRemote = pendingRemote(context)
        val next = CloudRevision(
            revisionId = UUID.randomUUID().toString(),
            parentRevisionId = pendingRemote?.revisionId ?: lastSyncedRevision(context),
            modifiedAtEpochMs = prefs.getLong(KEY_DIRTY_AT, System.currentTimeMillis()),
            deviceId = deviceId(context),
            deviceName = deviceName(),
            deviceType = "ANDROID"
        )
        prefs.edit().putString(KEY_PENDING_LOCAL, toJson(next).toString()).apply()
        return next
    }

    fun writeManifest(context: Context, folder: File): CloudRevision {
        val revision = currentOrNextRevision(context)
        File(folder, MANIFEST_FILE).writeText(toJson(revision).toString(2))
        return revision
    }

    /**
     * Records the revision that reached cloud storage, but only clears the dirty flag when
     * that exact prepared revision is still current. A transcription (or any other edit)
     * can finish while the worker is uploading; markLocalModified then removes the prepared
     * revision, and the newer change must remain pending for the next upload.
     *
     * @return true when no newer local change was observed and the phone is fully synced.
     */
    fun markSynced(context: Context, revision: CloudRevision): Boolean {
        val prefs = preferences(context)
        val pendingRevisionId = prefs.getString(KEY_PENDING_LOCAL, null)
            ?.let(::parse)
            ?.revisionId
        val fullySynced = shouldClearDirtyAfterUpload(
            dirty = prefs.getBoolean(KEY_DIRTY, false),
            pendingRevisionId = pendingRevisionId,
            uploadedRevisionId = revision.revisionId
        )
        prefs.edit()
            .putString(KEY_LAST_SYNCED_REVISION, revision.revisionId)
            .putString(KEY_LAST_KNOWN_REVISION, toJson(revision).toString())
            .putString(KEY_LAST_SEEN_CLOUD, toJson(revision).toString())
            .apply {
                if (fullySynced) {
                    putBoolean(KEY_DIRTY, false)
                    remove(KEY_DIRTY_AT)
                    remove(KEY_PENDING_LOCAL)
                    remove(KEY_PENDING_REMOTE)
                } else {
                    // A newer prepared revision may still point to the pre-upload parent.
                    // Recreate it on the next upload so it correctly descends from the
                    // revision that just reached the cloud.
                    remove(KEY_PENDING_LOCAL)
                }
            }
            .apply()
        return fullySynced
    }

    internal fun shouldClearDirtyAfterUpload(
        dirty: Boolean,
        pendingRevisionId: String?,
        uploadedRevisionId: String
    ): Boolean = !dirty || pendingRevisionId == uploadedRevisionId

    fun acceptRemote(context: Context, revision: CloudRevision) {
        preferences(context).edit()
            .putString(KEY_LAST_SYNCED_REVISION, revision.revisionId)
            .putString(KEY_LAST_KNOWN_REVISION, toJson(revision).toString())
            .putString(KEY_LAST_SEEN_CLOUD, toJson(revision).toString())
            .putBoolean(KEY_DIRTY, false)
            .remove(KEY_DIRTY_AT)
            .remove(KEY_PENDING_LOCAL)
            .remove(KEY_PENDING_REMOTE)
            .apply()
        CloudSyncManager.updateSyncState(
            context,
            CloudSyncManager.SyncState.UP_TO_DATE,
            "Up to date with cloud folder"
        )
    }

    fun keepLocalOver(context: Context, remote: CloudRevision) {
        setPendingRemote(context, remote)
        markLocalModified(context)
        currentOrNextRevision(context, forceNew = true)
    }

    fun parse(text: String): CloudRevision? = runCatching {
        val json = JSONObject(text)
        CloudRevision(
            schemaVersion = json.optInt("schemaVersion", 1),
            revisionId = json.getString("revisionId"),
            parentRevisionId = json.nullable("parentRevisionId"),
            modifiedAtEpochMs = json.getLong("modifiedAtEpochMs"),
            deviceId = json.getString("deviceId"),
            deviceName = json.optString("deviceName", "Unknown device"),
            deviceType = json.optString("deviceType", "UNKNOWN")
        )
    }.getOrNull()

    fun revisionInside(zipFile: File): CloudRevision? = runCatching {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_FILE) ?: return@use null
            zip.getInputStream(entry).bufferedReader().use { parse(it.readText()) }
        }
    }.getOrNull()

    fun toJson(revision: CloudRevision): JSONObject = JSONObject()
        .put("schemaVersion", revision.schemaVersion)
        .put("revisionId", revision.revisionId)
        .put("parentRevisionId", revision.parentRevisionId ?: JSONObject.NULL)
        .put("modifiedAtEpochMs", revision.modifiedAtEpochMs)
        .put("deviceId", revision.deviceId)
        .put("deviceName", revision.deviceName)
        .put("deviceType", revision.deviceType)

    private fun preferences(context: Context) = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )

    private fun JSONObject.nullable(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

    private const val PREFS = "rider_notes_sync_revision"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DIRTY = "dirty"
    private const val KEY_DIRTY_AT = "dirty_at"
    private const val KEY_LAST_SYNCED_REVISION = "last_synced_revision"
    private const val KEY_LAST_KNOWN_REVISION = "last_known_revision"
    private const val KEY_PENDING_LOCAL = "pending_local_revision"
    private const val KEY_PENDING_REMOTE = "pending_remote_revision"
    private const val KEY_LAST_SEEN_CLOUD = "last_seen_cloud_revision"
}
