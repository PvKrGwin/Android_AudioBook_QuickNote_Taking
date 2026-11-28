package com.praveen.ridernotes.desktop

import java.net.InetAddress
import java.util.UUID
import java.util.prefs.Preferences

object DesktopDeviceIdentity {
    private val preferences = Preferences.userRoot().node("com/praveen/ridernotes/desktop")

    fun id(): String {
        val existing = preferences.get(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        return UUID.randomUUID().toString().also {
            preferences.put(KEY_DEVICE_ID, it)
            preferences.flush()
        }
    }

    fun name(): String = preferences.get(KEY_DEVICE_NAME, null)
        ?.takeIf(String::isNotBlank)
        ?: runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        ?: "Windows PC"

    fun lastBackupPath(): String? = preferences.get(KEY_BACKUP_PATH, null)

    fun rememberBackupPath(path: String) {
        preferences.put(KEY_BACKUP_PATH, path)
        preferences.flush()
    }

    fun secondaryBackupPath(): String? = preferences.get(KEY_SECONDARY_BACKUP_PATH, null)

    fun rememberSecondaryBackupPath(path: String?) {
        if (path.isNullOrBlank()) {
            preferences.remove(KEY_SECONDARY_BACKUP_PATH)
        } else {
            preferences.put(KEY_SECONDARY_BACKUP_PATH, path)
        }
        preferences.flush()
    }

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_BACKUP_PATH = "backup_path"
    private const val KEY_SECONDARY_BACKUP_PATH = "secondary_backup_path"
}
