package com.praveen.ridernotes.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android grants media-session discovery through notification-listener access.
 * Rider Notes does not inspect or store notification contents.
 */
class RiderNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = Unit

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaControlGateway.notificationAccessConnected = true
    }

    override fun onListenerDisconnected() {
        MediaControlGateway.notificationAccessConnected = false
        super.onListenerDisconnected()
    }
}
