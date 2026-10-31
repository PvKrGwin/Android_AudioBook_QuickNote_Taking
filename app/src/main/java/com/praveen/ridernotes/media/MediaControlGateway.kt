package com.praveen.ridernotes.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.math.max

object MediaControlGateway {
    @Volatile var notificationAccessConnected: Boolean = false

    private const val SEEK_STEP_MS = 10_000L

    private val supportedPackages = setOf(
        "com.audible.application",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.books"
    )

    data class Result(val successful: Boolean, val message: String)

    data class VoiceNoteMediaContext(
        val packageName: String?,
        val appName: String?,
        val title: String?,
        val subtitle: String?,
        val playbackPositionMs: Long?,
        val wasPlaying: Boolean
    ) {
        val isStandalone: Boolean get() = packageName == null
    }

    @Volatile
    private var voiceNoteContext: VoiceNoteMediaContext? = null

    fun prepareForVoiceNote(context: Context): VoiceNoteMediaContext {
        val controller = try {
            findBestSupportedController(context)
        } catch (_: SecurityException) {
            null
        }

        if (controller == null) {
            return VoiceNoteMediaContext(null, null, null, null, null, false)
                .also { voiceNoteContext = it }
        }

        val state = controller.playbackState
        val wasPlaying = state?.state in setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING
        )
        val metadata = controller.metadata
        val noteContext = VoiceNoteMediaContext(
            packageName = controller.packageName,
            appName = appLabel(context, controller.packageName),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            playbackPositionMs = state?.let(::estimatedPosition),
            wasPlaying = wasPlaying
        )
        voiceNoteContext = noteContext

        if (wasPlaying) {
            if (supports(controller, PlaybackState.ACTION_PAUSE)) {
                controller.transportControls.pause()
            } else {
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }
        return noteContext
    }

    fun currentVoiceNoteContext(): VoiceNoteMediaContext? = voiceNoteContext

    fun finishVoiceNote(context: Context, delayMs: Long = 1_500L) {
        val savedContext = voiceNoteContext
        voiceNoteContext = null
        if (savedContext?.wasPlaying != true || savedContext.packageName == null) return

        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val controller = findControllers(appContext)
                    .firstOrNull { it.packageName == savedContext.packageName }
                if (controller != null) {
                    if (supports(controller, PlaybackState.ACTION_PLAY)) {
                        controller.transportControls.play()
                    } else {
                        dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                }
            } catch (_: Exception) {
                // Audio is already safe; a failed resume must not delete the note.
            }
        }, delayMs)
    }

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
                    val targetPosition = max(0L, currentPosition - SEEK_STEP_MS)
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

    fun forwardTenSeconds(context: Context): Result {
        val controller = controllerOrResult(context)
        if (controller.second != null) return controller.second!!
        val mediaController = controller.first!!
        val packageLabel = appLabel(context, mediaController.packageName)
        val state = mediaController.playbackState
            ?: return Result(false, "$packageLabel has no playback position")

        return try {
            when {
                supports(mediaController, PlaybackState.ACTION_SEEK_TO) -> {
                    val targetPosition = estimatedPosition(state) + SEEK_STEP_MS
                    mediaController.transportControls.seekTo(targetPosition)
                    Result(true, "Forwarded $packageLabel by 10 seconds")
                }
                supports(mediaController, PlaybackState.ACTION_FAST_FORWARD) -> {
                    mediaController.transportControls.fastForward()
                    Result(true, "Used $packageLabel forward command")
                }
                else -> Result(false, "$packageLabel does not expose forward control")
            }
        } catch (error: RuntimeException) {
            Result(false, "Forward failed: ${error.javaClass.simpleName}")
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

    private fun findBestSupportedController(context: Context): MediaController? =
        findControllers(context)
            .asSequence()
            .filter { it.packageName in supportedPackages }
            .maxByOrNull(::priority)

    private fun findControllers(context: Context): List<MediaController> {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, RiderNotificationListenerService::class.java)
        return manager.getActiveSessions(listener)
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
