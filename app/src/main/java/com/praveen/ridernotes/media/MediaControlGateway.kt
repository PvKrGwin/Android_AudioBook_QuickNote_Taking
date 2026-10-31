package com.praveen.ridernotes.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.math.max

object MediaControlGateway {
    @Volatile var notificationAccessConnected: Boolean = false

    private const val REWIND_MS = 10_000L

    private val supportedPackages = setOf(
        "com.audible.application",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.books"
    )

    data class Result(val successful: Boolean, val message: String)

    fun togglePlayPause(context: Context): Result {
        val controller = controllerOrResult(context)
        if (controller.second != null) return controller.second!!
        val mediaController = controller.first!!
        val packageLabel = appLabel(context, mediaController.packageName)

        return try {
            when (mediaController.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING -> {
                    if (supports(mediaController, PlaybackState.ACTION_PAUSE)) {
                        mediaController.transportControls.pause()
                    } else {
                        dispatchMediaKey(mediaController, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    Result(true, "Paused $packageLabel")
                }
                else -> {
                    if (supports(mediaController, PlaybackState.ACTION_PLAY)) {
                        mediaController.transportControls.play()
                    } else {
                        dispatchMediaKey(mediaController, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    Result(true, "Playing $packageLabel")
                }
            }
        } catch (error: RuntimeException) {
            Result(false, "Media control failed: ${error.javaClass.simpleName}")
        }
    }

    fun rewindTenSeconds(context: Context): Result {
        val controller = controllerOrResult(context)
        if (controller.second != null) return controller.second!!
        val mediaController = controller.first!!
        val packageLabel = appLabel(context, mediaController.packageName)
        val state = mediaController.playbackState
            ?: return Result(false, "$packageLabel has no playback position")

        return try {
            when {
                supports(mediaController, PlaybackState.ACTION_SEEK_TO) -> {
                    val currentPosition = estimatedPosition(state)
                    val targetPosition = max(0L, currentPosition - REWIND_MS)
                    mediaController.transportControls.seekTo(targetPosition)
                    Result(true, "Rewound $packageLabel by 10 seconds")
                }
                supports(mediaController, PlaybackState.ACTION_REWIND) -> {
                    mediaController.transportControls.rewind()
                    Result(true, "Used $packageLabel rewind command")
                }
                else -> Result(false, "$packageLabel does not expose rewind control")
            }
        } catch (error: RuntimeException) {
            Result(false, "Rewind failed: ${error.javaClass.simpleName}")
        }
    }

    private fun controllerOrResult(context: Context): Pair<MediaController?, Result?> {
        val controller = try {
            findBestSupportedController(context)
        } catch (_: SecurityException) {
            return null to Result(false, "Notification access required for media control")
        }

        if (controller != null) return controller to null
        val message = if (notificationAccessConnected) {
            "No active or paused supported media session"
        } else {
            "Enable Rider Notes notification access"
        }
        return null to Result(false, message)
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

    private fun estimatedPosition(state: PlaybackState): Long {
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return max(0L, position)
    }

    private fun supports(controller: MediaController, action: Long): Boolean {
        val actions = controller.playbackState?.actions ?: 0L
        return actions and action != 0L
    }

    private fun dispatchMediaKey(controller: MediaController, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        controller.dispatchMediaButtonEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        controller.dispatchMediaButtonEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    private fun appLabel(context: Context, packageName: String): String = try {
        val application = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(application).toString()
    } catch (_: Exception) {
        packageName
    }
}
