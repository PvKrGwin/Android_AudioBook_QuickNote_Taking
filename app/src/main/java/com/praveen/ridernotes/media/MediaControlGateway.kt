package com.praveen.ridernotes.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

object MediaControlGateway {
    @Volatile var notificationAccessConnected: Boolean = false

    private val supportedPackages = setOf(
        "com.audible.application",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.books"
    )

    data class Result(val successful: Boolean, val message: String)

    fun togglePlayPause(context: Context): Result {
        val controller = try {
            findBestSupportedController(context)
        } catch (_: SecurityException) {
            return Result(false, "Notification access required for media control")
        }

        if (controller == null) {
            val message = if (notificationAccessConnected) {
                "No active or paused supported media session"
            } else {
                "Enable Rider Notes notification access"
            }
            return Result(false, message)
        }

        val packageLabel = appLabel(context, controller.packageName)
        return try {
            when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING -> {
                    if (supports(controller, PlaybackState.ACTION_PAUSE)) {
                        controller.transportControls.pause()
                    } else {
                        dispatchPlayPause(controller)
                    }
                    Result(true, "Paused $packageLabel")
                }
                else -> {
                    if (supports(controller, PlaybackState.ACTION_PLAY)) {
                        controller.transportControls.play()
                    } else {
                        dispatchPlayPause(controller)
                    }
                    Result(true, "Playing $packageLabel")
                }
            }
        } catch (error: RuntimeException) {
            Result(false, "Media control failed: ${error.javaClass.simpleName}")
        }
    }

    private fun findBestSupportedController(context: Context): MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, RiderNotificationListenerService::class.java)
        return manager.getActiveSessions(listener)
            .asSequence()
            .filter { it.packageName in supportedPackages }
            .maxByOrNull(::priority)
    }

    private fun priority(controller: MediaController): Int =
        when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 4
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 3
            PlaybackState.STATE_PAUSED -> 2
            else -> 1
        }

    private fun supports(controller: MediaController, action: Long): Boolean {
        val actions = controller.playbackState?.actions ?: 0L
        return actions and action != 0L
    }

    private fun dispatchPlayPause(controller: MediaController) {
        val now = android.os.SystemClock.uptimeMillis()
        controller.dispatchMediaButtonEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        )
        controller.dispatchMediaButtonEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        )
    }

    private fun appLabel(context: Context, packageName: String): String = try {
        val application = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(application).toString()
    } catch (_: Exception) {
        packageName
    }
}
