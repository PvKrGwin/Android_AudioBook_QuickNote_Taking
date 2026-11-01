package com.praveen.ridernotes.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.praveen.ridernotes.MainActivity
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore
import com.praveen.ridernotes.media.MediaControlGateway
import com.praveen.ridernotes.transcription.ScribTranscriber
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
    private var pauseCheckAttempts = 0
    private var recordingInputLabel = "Phone microphone"
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var communicationRouteRequested = false
    private var recorderStage = "not started"
    private var stopping = false
    private var textToSpeech: TextToSpeech? = null
    private var warningBeepCount = 0
    private var autoStopAtElapsedMs = 0L
    private var startCueCompleted = false

    private val autoStop = Runnable { stopAndSave("40-second auto-stop") }
    private val beginAfterBeep = Runnable { beginRecording() }
    private val startWarning = Runnable {
        if (!recordingStarted || stopping) return@Runnable
        RecordingController.isExtensionWindowOpen = true
        warningBeepCount = 0
        updateNotification("Recording ends in 4 seconds — double press to extend")
        playNextWarningBeep()
    }
    private val warningBeep = Runnable { playNextWarningBeep() }
    private val callSafetyCheck = object : Runnable {
        override fun run() {
            if (!recordingStarted || stopping) return
            val audio = getSystemService(AudioManager::class.java)
            if (audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_RINGTONE
            ) {
                stopAndSave("incoming cellular call", deferMediaResume = true)
            } else {
                handler.postDelayed(this, CALL_SAFETY_CHECK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAndSave("remote press")
            ACTION_STOP_FOR_CALL ->
                stopAndSave("incoming app call", deferMediaResume = true)
            ACTION_START -> startRecordingFlow()
            ACTION_EXTEND -> extendRecording()
            ACTION_HOLD_AUTO_STOP -> holdAutoStopForGesture()
        }
        return START_NOT_STICKY
    }

    private fun startRecordingFlow() {
        if (recordingStarted || recorder != null || stopping) return
        pauseCheckAttempts = 0
        acquireRecordingWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing microphone…"))
        if (MediaControlGateway.currentVoiceNoteContext()?.isStandalone != false) {
            announceIndividualRecordingMode()
        } else {
            playStartBeepAndSchedule()
        }
    }

    @Suppress("DEPRECATION")
    private fun beginRecording() {
        if (!MediaControlGateway.ensureVoiceNoteMediaPaused(this)) {
            pauseCheckAttempts += 1
            if (pauseCheckAttempts <= MAX_PAUSE_ATTEMPTS) {
                updateNotification("Waiting for media to pause…")
                handler.postDelayed(beginAfterBeep, PAUSE_RETRY_MS)
                return
            }

            RecordingController.isRecordingOrStarting = false
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "Recording cancelled because the media app did not pause",
                    false
                )
            )
            MediaControlGateway.finishVoiceNote(this, delayMs = 0L)
            releaseBluetoothRoute()
            releaseRecordingWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            recorderStage = "create output file"
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
                // Speech-optimized capture lets the phone apply its built-in
                // voice processing/noise suppression where the device supports it.
                recorderStage = "setAudioSource"
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                val bluetoothInput = findBluetoothMicrophone()
                val audioManager = getSystemService(AudioManager::class.java)
                // On Android 12+, request the Bluetooth communication route
                // before choosing the recorder input. A preferred device alone
                // does not establish a headset microphone connection.
                recorderStage = "setCommunicationDevice"
                val routeReady = if (bluetoothInput != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // setCommunicationDevice accepts communication OUTPUT devices,
                    // not microphone input port IDs (which cause invalid portID).
                    val communicationOutput = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (communicationOutput != null) {
                        runCatching { audioManager.setCommunicationDevice(communicationOutput) }
                            .getOrDefault(false)
                    } else false
                } else false
                communicationRouteRequested = routeReady
                // Do not call setPreferredDevice(null): some Android recorder
                // implementations reject a null device with IllegalArgumentException.
                recorderStage = "setPreferredDevice"
                val preferred = routeReady && bluetoothInput != null &&
                    runCatching { setPreferredDevice(bluetoothInput) }.getOrDefault(false)
                recordingInputLabel = if (preferred) {
                    "Bluetooth communication route requested"
                } else {
                    "Phone microphone fallback (Bluetooth route unavailable)"
                }
                recorderStage = "configure MediaRecorder output"
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                recorderStage = "prepare"
                prepare()
                recorderStage = "start"
                start()
                val routed = routedDevice
                val bluetoothRouted = routed?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        routed?.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                recordingInputLabel = when {
                    bluetoothRouted -> "Bluetooth routed (audio not yet validated): ${routed?.productName}"
                    routed == null -> "Microphone route unknown (Bluetooth not confirmed)"
                    else -> "Non-Bluetooth microphone: ${routed.productName}"
                }
            }

            outputFile = file
            recorder = mediaRecorder
            recordingStarted = true
            updateNotification("Recording voice note…")
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "Recording started using $recordingInputLabel",
                    true
                )
            )
            scheduleRecordingDeadline()
            handler.post(callSafetyCheck)
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
                    "Recording failed at $recorderStage: ${error.javaClass.simpleName}: ${error.message?.take(160)}",
                    false
                )
            )
            releaseRecordingWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAndSave(reason: String, deferMediaResume: Boolean = false) {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(beginAfterBeep)
        handler.removeCallbacks(callSafetyCheck)
        cancelRecordingDeadline()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

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
        RecordingController.isExtensionWindowOpen = false

        val mediaContext = MediaControlGateway.currentVoiceNoteContext()
        if (saved && file != null) {
            saveMetadata(file, mediaContext)
            ScribTranscriber.enqueue(this, file)
        }

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

        MediaControlGateway.finishVoiceNote(this, delayMs = RESUME_DELAY_MS, deferForCall = deferMediaResume)
        // Return output routing from the Soundcore communication profile BEFORE
        // announcing that recording ended. Otherwise the confirmation tone may
        // be silent when the standalone note releases its microphone.
        releaseBluetoothRoute()
        playStopConfirmation(autoStopped = reason == "40-second auto-stop")
    }

    private fun announceIndividualRecordingMode() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                engine?.shutdown()
                textToSpeech = null
                handler.post(::playStartBeepAndSchedule)
            } else {
                textToSpeech = engine
                engine?.setLanguage(Locale.ENGLISH)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        engine?.shutdown()
                        if (textToSpeech === engine) textToSpeech = null
                        playStartBeepAndSchedule()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        engine?.shutdown()
                        if (textToSpeech === engine) textToSpeech = null
                        playStartBeepAndSchedule()
                    }
                }
                })
                val result = engine?.speak(
                    INDIVIDUAL_MODE_ANNOUNCEMENT,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    INDIVIDUAL_MODE_UTTERANCE_ID
                )
                if (result == TextToSpeech.ERROR) {
                    engine?.shutdown()
                    textToSpeech = null
                    handler.post(::playStartBeepAndSchedule)
                }
            }
        }
    }

    private fun playStartBeepAndSchedule() {
        if (startCueCompleted || stopping || recorder != null || recordingStarted) return
        startCueCompleted = true
        playBeep()
        handler.postDelayed(beginAfterBeep, START_BEEP_GAP_MS)
    }

    private fun scheduleRecordingDeadline() {
        cancelRecordingDeadline()
        RecordingController.isExtensionWindowOpen = false
        autoStopAtElapsedMs = SystemClock.elapsedRealtime() + MAX_RECORDING_MS
        handler.postDelayed(startWarning, MAX_RECORDING_MS - WARNING_WINDOW_MS)
        handler.postDelayed(autoStop, MAX_RECORDING_MS)
        refreshRecordingWakeLock()
    }

    private fun cancelRecordingDeadline() {
        handler.removeCallbacks(startWarning)
        handler.removeCallbacks(warningBeep)
        handler.removeCallbacks(autoStop)
        warningBeepCount = 0
        autoStopAtElapsedMs = 0L
        RecordingController.isExtensionWindowOpen = false
    }

    private fun playNextWarningBeep() {
        if (!RecordingController.isExtensionWindowOpen ||
            !recordingStarted ||
            stopping ||
            warningBeepCount >= WARNING_BEEP_COUNT
        ) return

        warningBeepCount += 1
        ToneGenerator(AudioManager.STREAM_MUSIC, 100).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, WARNING_BEEP_MS)
            handler.postDelayed({ release() }, WARNING_BEEP_MS + 50L)
        }
        ButtonEventStore.record(
            ButtonEvent.recordingResult(
                "RECORDING_END_WARNING: $warningBeepCount/$WARNING_BEEP_COUNT",
                true
            )
        )
        if (warningBeepCount < WARNING_BEEP_COUNT) {
            handler.postDelayed(warningBeep, WARNING_BEEP_STEP_MS)
        }
    }

    private fun extendRecording() {
        if (!recordingStarted || stopping || !RecordingController.isExtensionWindowOpen) {
            ButtonEventStore.record(
                ButtonEvent.recordingResult("Recording extension ignored outside warning window", false)
            )
            return
        }
        scheduleRecordingDeadline()
        updateNotification("Recording extended by 40 seconds")
        ButtonEventStore.record(
            ButtonEvent.recordingResult("Recording extended by another 40 seconds", true)
        )
    }

    private fun holdAutoStopForGesture() {
        if (!recordingStarted || stopping || !RecordingController.isExtensionWindowOpen) return
        val now = SystemClock.elapsedRealtime()
        val remaining = (autoStopAtElapsedMs - now).coerceAtLeast(0L)
        val delay = maxOf(remaining, GESTURE_RESOLUTION_GRACE_MS)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, delay)
        autoStopAtElapsedMs = now + delay
    }

    private fun playStopConfirmation(autoStopped: Boolean) {
        val count = if (autoStopped) 6 else 1
        // Keep the foreground service and wake lock alive until every beep
        // finishes, including when the screen is off.
        handler.postDelayed({
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }
                .getOrNull()
            if (tone == null) {
                ButtonEventStore.record(ButtonEvent.recordingResult("RECORDING_END_BEEP: tone unavailable", false))
                completeStop()
                return@postDelayed
            }
            repeat(count) { index ->
                handler.postDelayed({
                    val started = runCatching {
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, END_BEEP_MS)
                    }.getOrDefault(false)
                    ButtonEventStore.record(
                        ButtonEvent.recordingResult(
                            "RECORDING_END_BEEP: ${index + 1}/$count, autoStop=$autoStopped, started=$started",
                            started
                        )
                    )
                }, index * END_BEEP_STEP_MS)
            }
            handler.postDelayed({
                tone.release()
                completeStop()
            }, (count - 1) * END_BEEP_STEP_MS + END_BEEP_MS + 100L)
        }, ROUTE_SETTLE_MS)
    }

    private fun completeStop() {
        releaseRecordingWakeLock()
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
                .put("metadataVersion", 2)
                .put("recordedAtEpochMs", recordedAt)
                .put("type", if (mediaContext?.isStandalone == false) "MEDIA_LINKED" else "STANDALONE")
                .put("title", mediaContext?.title ?: standaloneTitle)
                .put("sourcePackage", mediaContext?.packageName ?: JSONObject.NULL)
                .put("sourceApp", mediaContext?.appName ?: JSONObject.NULL)
                .put("subtitleOrChapter", mediaContext?.subtitle ?: JSONObject.NULL)
                .put("author", mediaContext?.author ?: JSONObject.NULL)
                .put("playbackPositionMs", mediaContext?.playbackPositionMs ?: JSONObject.NULL)
                .put("recordingInput", recordingInputLabel)
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

    @Suppress("DEPRECATION")
    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        recordingWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "${packageName}:voice-note-recording"
            )
            .apply {
                setReferenceCounted(false)
                acquire(MAX_RECORDING_MS + WAKE_LOCK_SAFETY_MS)
            }
    }

    private fun refreshRecordingWakeLock() {
        recordingWakeLock?.takeIf { it.isHeld }?.release()
        recordingWakeLock = null
        acquireRecordingWakeLock()
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.takeIf { it.isHeld }?.release()
        recordingWakeLock = null
    }

    private fun releaseBluetoothRoute() {
        if (communicationRouteRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(AudioManager::class.java).clearCommunicationDevice()
        }
        communicationRouteRequested = false
    }

    private fun findBluetoothMicrophone(): AudioDeviceInfo? {
        val audioManager = getSystemService(AudioManager::class.java)
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { device ->
                device.isSource && (
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
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
        handler.removeCallbacks(callSafetyCheck)
        cancelRecordingDeadline()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        recorder?.release()
        recorder = null
        RecordingController.isRecordingOrStarting = false
        RecordingController.isExtensionWindowOpen = false
        releaseBluetoothRoute()
        releaseRecordingWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.praveen.ridernotes.action.START_RECORDING"
        const val ACTION_STOP = "com.praveen.ridernotes.action.STOP_RECORDING"
        const val ACTION_STOP_FOR_CALL =
            "com.praveen.ridernotes.action.STOP_RECORDING_FOR_CALL"
        const val ACTION_EXTEND = "com.praveen.ridernotes.action.EXTEND_RECORDING"
        const val ACTION_HOLD_AUTO_STOP =
            "com.praveen.ridernotes.action.HOLD_RECORDING_AUTO_STOP"

        private const val CHANNEL_ID = "rider_notes_recording"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RECORDING_MS = 40_000L
        private const val CALL_SAFETY_CHECK_MS = 500L
        private const val WARNING_WINDOW_MS = 4_000L
        private const val WARNING_BEEP_COUNT = 8
        private const val WARNING_BEEP_MS = 160
        private const val WARNING_BEEP_STEP_MS = 500L
        private const val GESTURE_RESOLUTION_GRACE_MS = 1_100L
        private const val WAKE_LOCK_SAFETY_MS = 10_000L
        private const val START_BEEP_GAP_MS = 400L
        private const val RESUME_DELAY_MS = 1_500L
        private const val BEEP_MS = 300
        private const val ROUTE_SETTLE_MS = 300L
        private const val END_BEEP_MS = 160
        private const val END_BEEP_STEP_MS = 320L
        private const val PAUSE_RETRY_MS = 300L
        private const val MAX_PAUSE_ATTEMPTS = 3
        private const val INDIVIDUAL_MODE_ANNOUNCEMENT = "Individual recording mode"
        private const val INDIVIDUAL_MODE_UTTERANCE_ID = "rider-notes-individual-mode"
    }
}
