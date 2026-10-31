package com.praveen.ridernotes.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object RecordingController {
    @Volatile
    var isRecordingOrStarting: Boolean = false
        internal set

    fun start(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Microphone permission required"
        }
        if (isRecordingOrStarting) return "Recording is already active"

        isRecordingOrStarting = true
        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceRecordingService::class.java)
                .setAction(VoiceRecordingService.ACTION_START)
        )
        return "Recording start requested"
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
