package com.praveen.ridernotes.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android grants media-session discovery through notification-listener access.
 * For supported media apps only, Rider Notes keeps the current notification
 * labels in memory so app-specific chapter/episode text can be captured.
 * Notification text is never written to disk unless it becomes voice-note metadata.
 */
class RiderNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(MediaControlGateway::updateNotificationMetadata)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let(MediaControlGateway::removeNotificationMetadata)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = Unit

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaControlGateway.notificationAccessConnected = true
        activeNotifications.orEmpty().forEach(MediaControlGateway::updateNotificationMetadata)
    }

    override fun onListenerDisconnected() {
        MediaControlGateway.notificationAccessConnected = false
        MediaControlGateway.clearNotificationMetadata()
        super.onListenerDisconnected()
    }
}
