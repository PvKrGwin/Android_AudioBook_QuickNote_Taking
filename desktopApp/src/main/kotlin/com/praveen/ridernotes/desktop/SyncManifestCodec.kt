package com.praveen.ridernotes.desktop

import org.json.JSONObject
import java.io.File
import java.util.UUID

object SyncManifestCodec {
    const val FILE_NAME = "rider_notes_sync_manifest.json"
    const val SIBLING_FILE_NAME = "RiderNotes_Sync_State.json"

    fun read(file: File): SyncManifest? = runCatching {
        val json = JSONObject(file.readText())
        SyncManifest(
            schemaVersion = json.optInt("schemaVersion", 1),
            revisionId = json.getString("revisionId"),
            parentRevisionId = json.nullable("parentRevisionId"),
            modifiedAtEpochMs = json.getLong("modifiedAtEpochMs"),
            deviceId = json.getString("deviceId"),
            deviceName = json.optString("deviceName", "Unknown device"),
            deviceType = json.optString("deviceType", "UNKNOWN")
        )
    }.getOrNull()

    fun next(parent: SyncManifest?): SyncManifest = SyncManifest(
        revisionId = UUID.randomUUID().toString(),
        parentRevisionId = parent?.revisionId,
        modifiedAtEpochMs = System.currentTimeMillis(),
        deviceId = DesktopDeviceIdentity.id(),
        deviceName = DesktopDeviceIdentity.name(),
        deviceType = "WINDOWS_PC"
    )

    fun write(file: File, manifest: SyncManifest) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(manifest).toString(2))
    }

    fun toJson(manifest: SyncManifest): JSONObject = JSONObject()
        .put("schemaVersion", manifest.schemaVersion)
        .put("revisionId", manifest.revisionId)
        .put("parentRevisionId", manifest.parentRevisionId ?: JSONObject.NULL)
        .put("modifiedAtEpochMs", manifest.modifiedAtEpochMs)
        .put("deviceId", manifest.deviceId)
        .put("deviceName", manifest.deviceName)
        .put("deviceType", manifest.deviceType)

    private fun JSONObject.nullable(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null
}
