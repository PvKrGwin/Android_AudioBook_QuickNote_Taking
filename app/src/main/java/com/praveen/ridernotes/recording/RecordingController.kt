package com.praveen.ridernotes.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.praveen.ridernotes.media.MediaControlGateway
import com.praveen.ridernotes.cloud.CloudSyncManager
import com.praveen.ridernotes.cloud.SyncRevisionStore

object RecordingController {
    @Volatile
    var isRecordingOrStarting: Boolean = false
        internal set

    @Volatile
    var isExtensionWindowOpen: Boolean = false
        internal set

    @Volatile
    var topicRecordingStartedAtElapsedMs: Long? = null
        internal set

    @Volatile
    private var recordingRequestedAtElapsedMs: Long = 0L

    fun start(context: Context): String {
        pendingCloudRevision(context)?.let {
            return "Cloud update from ${it.deviceName} needs review in Rider Notes"
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Microphone permission required"
        }
        if (isRecordingOrStarting) return "Recording is already active"

        val mediaContext = MediaControlGateway.prepareForVoiceNote(context)
        isRecordingOrStarting = true
        recordingRequestedAtElapsedMs = SystemClock.elapsedRealtime()
        RiderRecordTileService.refresh(context)
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceRecordingService::class.java)
                    .setAction(VoiceRecordingService.ACTION_START)
            )
            if (mediaContext.isStandalone) {
                "Standalone recording start requested"
            } else {
                "Paused ${mediaContext.appName}; linked recording start requested"
            }
        } catch (error: RuntimeException) {
            isRecordingOrStarting = false
            recordingRequestedAtElapsedMs = 0L
            MediaControlGateway.finishVoiceNote(context, delayMs = 0L)
            "Recording could not start: ${error.javaClass.simpleName}"
        }
    }

    fun startTopic(context: Context): String {
        pendingCloudRevision(context)?.let {
            return "Cloud update from ${it.deviceName} needs review in Rider Notes"
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return "Microphone permission required"
        if (isRecordingOrStarting) return "Recording is already active"

        topicRecordingStartedAtElapsedMs = null
        isRecordingOrStarting = true
        recordingRequestedAtElapsedMs = SystemClock.elapsedRealtime()
        RiderRecordTileService.refresh(context)
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceRecordingService::class.java)
                    .setAction(VoiceRecordingService.ACTION_START)
                    .putExtra(VoiceRecordingService.EXTRA_TOPIC_ONLY, true)
            )
            "Topic recording start requested"
        } catch (error: RuntimeException) {
            isRecordingOrStarting = false
            recordingRequestedAtElapsedMs = 0L
            "Recording could not start: ${error.javaClass.simpleName}"
        }
    }

    fun holdAutoStopForGesture(context: Context) {
        if (!isExtensionWindowOpen) return
        context.startService(
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_HOLD_AUTO_STOP)
        )
    }

    fun extend(context: Context): String {
        if (!isRecordingOrStarting || !isExtensionWindowOpen) {
            return "Recording extension is not available"
        }
        // Reserve this extension synchronously so a delayed gesture cannot
        // become a new recording if the old deadline expires first.
        isExtensionWindowOpen = false
        RiderRecordTileService.refresh(context)
        return try {
            context.startService(
                Intent(context, VoiceRecordingService::class.java)
                    .setAction(VoiceRecordingService.ACTION_EXTEND)
            )
            "Recording extended by another 40 seconds"
        } catch (error: RuntimeException) {
            isExtensionWindowOpen = true
            RiderRecordTileService.refresh(context)
            "Recording extension failed: ${error.javaClass.simpleName}"
        }
    }

    fun interruptForCall(context: Context) {
        if (!isRecordingOrStarting) return
        // Route any immediately following remote gesture to call control while
        // the recording service finishes saving and releases the microphone.
        isRecordingOrStarting = false
        isExtensionWindowOpen = false
        recordingRequestedAtElapsedMs = 0L
        RiderRecordTileService.refresh(context)
        context.startService(
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_STOP_FOR_CALL)
        )
    }

    fun stop(context: Context): String {
        if (!isRecordingOrStarting) return "No recording is active"
        val sinceRequest = SystemClock.elapsedRealtime() - recordingRequestedAtElapsedMs
        if (recordingRequestedAtElapsedMs != 0L && sinceRequest < STARTUP_STOP_GUARD_MS) {
            return "Recording is still starting; accidental stop ignored"
        }
        RiderRecordTileService.refresh(context)
        context.startService(
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_STOP)
        )
        return "Recording stop requested"
    }

    internal fun markStopped() {
        recordingRequestedAtElapsedMs = 0L
    }

    private fun pendingCloudRevision(context: Context) =
        SyncRevisionStore.pendingRemote(context)
            ?: runCatching { CloudSyncManager.inspectRemote(context) }.getOrNull()

    private const val STARTUP_STOP_GUARD_MS = 2_000L
}
