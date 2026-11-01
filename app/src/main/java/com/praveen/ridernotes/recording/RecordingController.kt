package com.praveen.ridernotes.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.praveen.ridernotes.media.MediaControlGateway

object RecordingController {
    @Volatile
    var isRecordingOrStarting: Boolean = false
        internal set

    @Volatile
    var isExtensionWindowOpen: Boolean = false
        internal set

    fun start(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Microphone permission required"
        }
        if (isRecordingOrStarting) return "Recording is already active"

        val mediaContext = MediaControlGateway.prepareForVoiceNote(context)
        isRecordingOrStarting = true
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
            MediaControlGateway.finishVoiceNote(context, delayMs = 0L)
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
        context.startService(
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_EXTEND)
        )
        return "Recording extension requested"
    }

    fun interruptForCall(context: Context) {
        if (!isRecordingOrStarting) return
        context.startService(
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_STOP_FOR_CALL)
        )
    }

    fun stop(context: Context): String {
        if (!isRecordingOrStarting) return "No recording is active"
        context.startService(
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_STOP)
        )
        return "Recording stop requested"
    }
}
