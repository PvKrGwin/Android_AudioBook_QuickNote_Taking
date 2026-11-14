package com.praveen.ridernotes.desktop

import java.net.NetworkInterface

enum class DesktopSyncHealth {
    CURRENT,
    NEW_CLOUD_VERSION,
    OFFLINE_UNVERIFIED,
    DRIVE_UNVERIFIED
}

data class DesktopSyncObservation(
    val health: DesktopSyncHealth,
    val remoteRevision: SyncManifest? = null,
    val message: String
)

object DesktopSyncMonitor {
    fun inspect(snapshot: BackupSnapshot): DesktopSyncObservation {
        val online = hasActiveNetwork()
        val remote = BackupPublisher.remoteManifestFor(snapshot.sourceZip)
        if (remote != null && remote.revisionId != snapshot.syncManifest?.revisionId) {
            return DesktopSyncObservation(
                DesktopSyncHealth.NEW_CLOUD_VERSION,
                remote,
                "New Drive revision from ${remote.deviceName}"
            )
        }
        val zipChanged = snapshot.sourceZip.lastModified() != snapshot.sourceZipLastModified ||
            snapshot.sourceZip.length() != snapshot.sourceZipLength
        if (zipChanged) {
            return DesktopSyncObservation(
                DesktopSyncHealth.NEW_CLOUD_VERSION,
                remote,
                "The Drive backup file changed after it was opened"
            )
        }
        if (!online) {
            return DesktopSyncObservation(
                DesktopSyncHealth.OFFLINE_UNVERIFIED,
                remote,
                "Offline — this local Drive copy cannot be verified"
            )
        }
        if (remote == null || snapshot.syncManifest == null) {
            return DesktopSyncObservation(
                DesktopSyncHealth.DRIVE_UNVERIFIED,
                remote,
                "Drive revision is not available yet"
            )
        }
        return DesktopSyncObservation(
            DesktopSyncHealth.CURRENT,
            remote,
            "Drive copy is current"
        )
    }

    private fun hasActiveNetwork(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { network ->
            network.isUp && !network.isLoopback && network.inetAddresses.toList().any {
                !it.isLoopbackAddress && !it.isLinkLocalAddress
            }
        }
    }.getOrDefault(false)
}
