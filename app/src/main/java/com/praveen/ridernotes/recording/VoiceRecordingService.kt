package com.praveen.ridernotes.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.praveen.ridernotes.MainActivity
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore
import com.praveen.ridernotes.media.MediaControlGateway
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceRecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStarted = false

    private val autoStop = Runnable { stopAndSave("40-second auto-stop") }
    private val beginAfterBeep = Runnable { beginRecording() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAndSave("remote press")
            ACTION_START -> startRecordingFlow()
        }
        return START_NOT_STICKY
    }

    private fun startRecordingFlow() {
        if (recordingStarted || recorder != null) return
        startForeground(NOTIFICATION_ID, buildNotification("Preparing microphone…"))
        playBeep()
        handler.postDelayed(beginAfterBeep, START_BEEP_GAP_MS)
    }

    @Suppress("DEPRECATION")
    private fun beginRecording() {
        try {
            val folder = File(filesDir, "voice_notes").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val mediaContext = MediaControlGateway.currentVoiceNoteContext()
            val sourceName = if (mediaContext?.isStandalone == false) {
                safeFilePart(mediaContext.appName ?: "Media")
            } else {
                "Standalone"
            }
            val file = File(folder, "RiderNote_${sourceName}_${timestamp}.m4a")

            val mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            outputFile = file
            recorder = mediaRecorder
            recordingStarted = true
            updateNotification("Recording voice note…")
            ButtonEventStore.record(ButtonEvent.recordingResult("Recording started", true))
            handler.postDelayed(autoStop, MAX_RECORDING_MS)
        } catch (error: Exception) {
            outputFile?.delete()
            outputFile = null
            recorder?.release()
            recorder = null
            recordingStarted = false
            RecordingController.isRecordingOrStarting = false
            MediaControlGateway.finishVoiceNote(this)
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "Recording failed: ${error.javaClass.simpleName}",
                    false
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAndSave(reason: String) {
        handler.removeCallbacks(beginAfterBeep)
        handler.removeCallbacks(autoStop)

        val file = outputFile
        var saved = false
        var size = 0L

        if (recordingStarted) {
            try {
                recorder?.stop()
                saved = file?.exists() == true && file.length() > 0L
                size = file?.length() ?: 0L
            } catch (_: RuntimeException) {
                file?.delete()
            }
        }

        recorder?.release()
        recorder = null
        recordingStarted = false
        outputFile = null
        RecordingController.isRecordingOrStarting = false

        val mediaContext = MediaControlGateway.currentVoiceNoteContext()
        if (saved && file != null) {
            saveMetadata(file, mediaContext)
        }

        playBeep()
        val source = if (mediaContext?.isStandalone == false) {
            " linked to ${mediaContext.appName}"
        } else {
            " as standalone thought"
        }
        val message = if (saved) {
            "Saved ${file?.name} (${size / 1024} KB)$source; stopped by $reason"
        } else {
            "Recording stopped before audio was saved"
        }
        ButtonEventStore.record(ButtonEvent.recordingResult(message, saved))

        MediaControlGateway.finishVoiceNote(this, delayMs = RESUME_DELAY_MS)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveMetadata(
        audioFile: File,
        mediaContext: MediaControlGateway.VoiceNoteMediaContext?
    ) {
        try {
            val recordedAt = System.currentTimeMillis()
            val standaloneTitle = SimpleDateFormat(
                "dd MMM yyyy, h:mm a",
                Locale.getDefault()
            ).format(Date(recordedAt))

            val json = JSONObject()
                .put("audioFile", audioFile.name)
                .put("recordedAtEpochMs", recordedAt)
                .put("type", if (mediaContext?.isStandalone == false) "MEDIA_LINKED" else "STANDALONE")
                .put("title", mediaContext?.title ?: standaloneTitle)
                .put("sourcePackage", mediaContext?.packageName ?: JSONObject.NULL)
                .put("sourceApp", mediaContext?.appName ?: JSONObject.NULL)
                .put("subtitleOrChapter", mediaContext?.subtitle ?: JSONObject.NULL)
                .put("playbackPositionMs", mediaContext?.playbackPositionMs ?: JSONObject.NULL)
                .put("transcriptionStatus", "PENDING")

            File(audioFile.parentFile, audioFile.nameWithoutExtension + ".json")
                .writeText(json.toString(2))
        } catch (error: Exception) {
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "Audio saved, but metadata failed: ${error.javaClass.simpleName}",
                    false
                )
            )
        }
    }

    private fun safeFilePart(value: String): String {
        val cleaned = value
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .take(40)
        return cleaned.ifBlank { "Media" }
    }

    private fun playBeep() {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            handler.postDelayed({ release() }, BEEP_MS + 50L)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice-note recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Rider Notes is using the microphone"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rider Notes")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        handler.removeCallbacks(beginAfterBeep)
        handler.removeCallbacks(autoStop)
        recorder?.release()
        recorder = null
        RecordingController.isRecordingOrStarting = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.praveen.ridernotes.action.START_RECORDING"
        const val ACTION_STOP = "com.praveen.ridernotes.action.STOP_RECORDING"

        private const val CHANNEL_ID = "rider_notes_recording"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RECORDING_MS = 40_000L
        private const val START_BEEP_GAP_MS = 250L
        private const val RESUME_DELAY_MS = 1_500L
        private const val BEEP_MS = 300
    }
}
