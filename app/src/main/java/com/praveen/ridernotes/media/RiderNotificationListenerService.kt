package com.praveen.ridernotes.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.praveen.ridernotes.call.CallControlGateway
import com.praveen.ridernotes.recording.RecordingController

/**
 * Android grants media-session discovery through notification-listener access.
 * For supported media apps only, Rider Notes keeps the current notification
 * labels in memory so app-specific chapter/episode text can be captured.
 * Notification text is never written to disk unless it becomes voice-note metadata.
 */
class RiderNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        var postedNotificationIsCall = false
        sbn?.let {
            MediaControlGateway.updateNotificationMetadata(it)
            postedNotificationIsCall = CallControlGateway.updateNotification(it)
        }
        // Do not let an unrelated Rider Notes/media notification retrigger a
        // stale call entry and stop a recording that has only just started.
        if (postedNotificationIsCall && CallControlGateway.hasNotificationCall()) {
            RecordingController.interruptForCall(this)
        }
        MediaControlGateway.observeActivePlayback(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let {
            MediaControlGateway.removeNotificationMetadata(it)
            CallControlGateway.removeNotification(it)
        }
        if (!CallControlGateway.hasNotificationCall()) {
            MediaControlGateway.resumeDeferredAfterCall(this)
        }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = Unit

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaControlGateway.notificationAccessConnected = true
        activeNotifications.orEmpty().forEach {
            MediaControlGateway.updateNotificationMetadata(it)
            CallControlGateway.updateNotification(it)
        }
        MediaControlGateway.observeActivePlayback(this)
    }

    override fun onListenerDisconnected() {
        MediaControlGateway.notificationAccessConnected = false
        MediaControlGateway.clearNotificationMetadata()
        CallControlGateway.clear()
        super.onListenerDisconnected()
    }
}
