package com.praveen.ridernotes.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.praveen.ridernotes.call.CallControlGateway
import com.praveen.ridernotes.media.MediaControlGateway
import com.praveen.ridernotes.recording.RecordingController
import kotlin.math.PI
import kotlin.math.sin

/**
 * Observes hardware keys globally and classifies Beauty-R1's usable camera
 * button as a single, double, triple or four-press gesture.
 */
class BleButtonAccessibilityService : AccessibilityService() {
    private val detector = RemotePressPatternDetector()
    private val handler = Handler(Looper.getMainLooper())

    private var lastDeviceId = -1
    private var lastDeviceName = "Beauty-R1"
    private var screenReceiverRegistered = false
    private var lastScreenOffBeepAt = 0L
    private var screenOffObserved = false
    private var lastScreenWakeAt = 0L
    private var warningPressCount = 0
    private var screenTransitionTrack: AudioTrack? = null

    private val warningSinglePress = Runnable {
        val shouldStop = warningPressCount == 1 &&
            RecordingController.isRecordingOrStarting
        warningPressCount = 0
        if (shouldStop) {
            val message = RecordingController.stop(this)
            ButtonEventStore.record(ButtonEvent.recordingResult(message, true))
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffObserved = true
                    if (isConfiguredRemoteConnected()) playScreenOffTone()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOffObserved = false
                    lastScreenWakeAt = SystemClock.elapsedRealtime()
                    if (isConfiguredRemoteConnected()) playScreenReadyTone()
                }
            }
        }
    }

    private val resolvePendingGesture = Runnable {
        detector.resolve(SystemClock.uptimeMillis())?.let(::recordGesture)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        screenOffObserved = !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        registerScreenOnReceiver()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Accessibility service"))

        if (RemoteButtonConfig.isTraining(this)) return false

        if (!RemoteButtonConfig.matches(this, event)) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            acknowledgeScreenOffPress()
        }

        if (RecordingController.isRecordingOrStarting &&
            !CallControlGateway.hasOngoingCall(this)
        ) {
            if (event.action == KeyEvent.ACTION_UP) return true
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true

            if (!RecordingController.isExtensionWindowOpen) {
                handler.removeCallbacks(resolvePendingGesture)
                detector.cancel()
                val message = RecordingController.stop(this)
                ButtonEventStore.record(ButtonEvent.recordingResult(message, true))
                return true
            }

            // Handle warning-window taps separately. The second tap extends
            // immediately instead of waiting for the normal gesture timeout.
            RecordingController.holdAutoStopForGesture(this)
            lastDeviceId = event.deviceId
            lastDeviceName = event.device?.name ?: "Beauty-R1"
            if (warningPressCount == 0) {
                warningPressCount = 1
                handler.removeCallbacks(warningSinglePress)
                handler.postDelayed(warningSinglePress, WARNING_DOUBLE_PRESS_MS)
            } else {
                warningPressCount = 0
                handler.removeCallbacks(warningSinglePress)
                val message = RecordingController.extend(this)
                ButtonEventStore.record(
                    ButtonEvent.recordingResult(
                        message,
                        message.startsWith("Recording extended")
                    )
                )
            }
            return true
        }

        // Consume both halves of Beauty-R1's volume-shaped click so the gesture
        // does not also change the phone's volume.
        if (event.action == KeyEvent.ACTION_UP) {
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return true
        }

        lastDeviceId = event.deviceId
        lastDeviceName = event.device?.name ?: "Beauty-R1"

        // Use receipt time rather than KeyEvent.eventTime. Beauty-R1 can report
        // an event timestamp behind Android's clock, shortening the real window.
        val receivedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(resolvePendingGesture)
        detector.onPress(receivedAt)?.let(::recordGesture)

        detector.resolutionDeadlineMillis()?.let { deadline ->
            handler.postAtTime(resolvePendingGesture, deadline)
        }

        return true
    }

    private fun recordGesture(gesture: RemotePressPatternDetector.Gesture) {
        ButtonEventStore.record(
            ButtonEvent.gesture(
                gesture = gesture,
                deviceId = lastDeviceId,
                deviceName = lastDeviceName
            )
        )

        if (RecordingController.isRecordingOrStarting) {
            val message = when (gesture) {
                RemotePressPatternDetector.Gesture.SINGLE ->
                    RecordingController.stop(this)
                RemotePressPatternDetector.Gesture.DOUBLE ->
                    RecordingController.extend(this)
                else -> "Recording warning: use single to stop or double to extend"
            }
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    message,
                    gesture == RemotePressPatternDetector.Gesture.SINGLE ||
                        gesture == RemotePressPatternDetector.Gesture.DOUBLE
                )
            )
            return
        }

        CallControlGateway.handleGesture(this, gesture)?.let { callResult ->
            ButtonEventStore.record(
                ButtonEvent.mediaResult(
                    message = callResult.message,
                    successful = callResult.successful
                )
            )
            return
        }

        acknowledgeGesture(gesture)

        val result = when (GestureActionConfig.actionFor(this, gesture)) {
            GestureActionConfig.Action.PLAY_PAUSE ->
                MediaControlGateway.togglePlayPause(this)
            GestureActionConfig.Action.REWIND ->
                MediaControlGateway.rewindTenSeconds(this)
            GestureActionConfig.Action.FORWARD ->
                MediaControlGateway.forwardTenSeconds(this)
            GestureActionConfig.Action.RECORD_NOTE -> {
                // Let the two acknowledgement beeps finish before the spoken
                // recording-mode announcement begins.
                handler.postDelayed({
                    val message = RecordingController.start(this)
                    ButtonEventStore.record(
                        ButtonEvent.recordingResult(
                            message = message,
                            successful = message.endsWith("recording start requested")
                        )
                    )
                }, gesturePressCount(gesture) * ACK_BEEP_INTERVAL_MS)
                null
            }
            GestureActionConfig.Action.NONE -> null
        }

        result?.let {
            ButtonEventStore.record(
                ButtonEvent.mediaResult(
                    message = it.message,
                    successful = it.successful
                )
            )
        }
    }

    private fun acknowledgeGesture(gesture: RemotePressPatternDetector.Gesture) {
        val count = gesturePressCount(gesture)
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        repeat(count) { index ->
            handler.postDelayed({
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                if (index == count - 1) handler.postDelayed({ tone.release() }, 180L)
            }, index * ACK_BEEP_INTERVAL_MS)
        }
    }

    private fun gesturePressCount(gesture: RemotePressPatternDetector.Gesture): Int =
        when (gesture) {
            RemotePressPatternDetector.Gesture.SINGLE -> 1
            RemotePressPatternDetector.Gesture.DOUBLE -> 2
            RemotePressPatternDetector.Gesture.TRIPLE -> 3
            RemotePressPatternDetector.Gesture.QUADRUPLE -> 4
        }

    private fun registerScreenOnReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOnReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOnReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun acknowledgeScreenOffPress() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val now = SystemClock.elapsedRealtime()
        // Remote keys can wake the phone slightly before the accessibility callback.
        val screenWasOff = !power.isInteractive || screenOffObserved ||
            (lastScreenWakeAt > 0L && now - lastScreenWakeAt in 0L..350L)
        if (!screenWasOff || now - lastScreenOffBeepAt < 1200L) return

        lastScreenOffBeepAt = now
        val outcome = runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            handler.post {
                val firstStarted = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
                handler.postDelayed({
                    val secondStarted = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
                    ButtonEventStore.record(
                        ButtonEvent.recordingResult(
                            "SCREEN_OFF_BUTTON_BEEP: two short beeps requested (first=$firstStarted, second=$secondStarted)",
                            firstStarted && secondStarted
                        )
                    )
                    handler.postDelayed({ tone.release() }, 220L)
                }, 320L)
            }
        }
        if (outcome.isFailure) {
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "SCREEN_OFF_BUTTON_BEEP failed: ${outcome.exceptionOrNull()?.javaClass?.simpleName}",
                    false
                )
            )
        }
    }

    private fun isConfiguredRemoteConnected(): Boolean {
        val configuredName = RemoteButtonConfig.load(this).deviceName
        return InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { device ->
                device.isEnabled &&
                    device.name.equals(configuredName, ignoreCase = true)
            } == true
        }
    }

    private fun playScreenReadyTone() {
        playScreenTransitionTone(
            frequencies = doubleArrayOf(
                SA_HZ,
                SA_HZ,
                PA_HZ,
                PA_HZ,
                UPPER_SA_HZ,
                UPPER_SA_HZ
            ),
            failureLabel = "SCREEN_ON_ASCENDING_TONE"
        )
    }

    private fun playScreenOffTone() {
        playScreenTransitionTone(
            frequencies = doubleArrayOf(
                UPPER_SA_HZ,
                UPPER_SA_HZ,
                PA_HZ,
                PA_HZ,
                SA_HZ,
                SA_HZ
            ),
            failureLabel = "SCREEN_OFF_DESCENDING_TONE"
        )
    }

    private fun playScreenTransitionTone(
        frequencies: DoubleArray,
        failureLabel: String
    ) {
        val track = runCatching {
            val samples = createMusicalCue(frequencies)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SCREEN_TONE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { audioTrack ->
                    check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                        "AudioTrack initialization failed"
                    }
                    check(audioTrack.write(samples, 0, samples.size) == samples.size) {
                        "Musical cue was not fully written"
                    }
                }
        }.getOrElse { error ->
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "$failureLabel failed: ${error.javaClass.simpleName}",
                    false
                )
            )
            return
        }

        screenTransitionTrack?.let(::releaseScreenTransitionTrack)
        screenTransitionTrack = track
        runCatching { track.play() }.onFailure { error ->
            releaseScreenTransitionTrack(track)
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "$failureLabel failed: ${error.javaClass.simpleName}",
                    false
                )
            )
            return
        }
        handler.postDelayed(
            { releaseScreenTransitionTrack(track) },
            musicalCueDurationMs(frequencies.size) + SCREEN_TONE_RELEASE_MARGIN_MS
        )
    }

    private fun createMusicalCue(frequencies: DoubleArray): ShortArray {
        val noteSamples = millisecondsToSamples(SCREEN_TRANSITION_NOTE_MS)
        val shortGapSamples = millisecondsToSamples(SCREEN_TRANSITION_REPEAT_GAP_MS)
        val phraseGapSamples = millisecondsToSamples(SCREEN_TRANSITION_PHRASE_GAP_MS)
        val gapSamples = frequencies.indices.dropLast(1).sumOf { index ->
            if (index % 2 == 1) phraseGapSamples else shortGapSamples
        }
        val output = ShortArray((noteSamples * frequencies.size) + gapSamples)
        val fadeSamples = millisecondsToSamples(SCREEN_TRANSITION_FADE_MS).coerceAtLeast(1)
        var outputIndex = 0

        frequencies.forEachIndexed { noteIndex, frequency ->
            repeat(noteSamples) { sampleIndex ->
                val fadeIn = sampleIndex.toDouble() / fadeSamples
                val fadeOut = (noteSamples - 1 - sampleIndex).toDouble() / fadeSamples
                val envelope = minOf(1.0, fadeIn, fadeOut).coerceAtLeast(0.0)
                val timeSeconds = sampleIndex.toDouble() / SCREEN_TONE_SAMPLE_RATE
                // A quiet second harmonic makes the cue warmer than a plain beep.
                val wave =
                    (0.84 * sin(2.0 * PI * frequency * timeSeconds)) +
                        (0.16 * sin(4.0 * PI * frequency * timeSeconds))
                output[outputIndex++] =
                    (wave * envelope * SCREEN_TONE_AMPLITUDE * Short.MAX_VALUE)
                        .toInt()
                        .toShort()
            }

            if (noteIndex < frequencies.lastIndex) {
                val gap = if (noteIndex % 2 == 1) phraseGapSamples else shortGapSamples
                outputIndex += gap
            }
        }
        return output
    }

    private fun musicalCueDurationMs(noteCount: Int): Long {
        val gaps = (0 until (noteCount - 1)).sumOf { index ->
            if (index % 2 == 1) {
                SCREEN_TRANSITION_PHRASE_GAP_MS
            } else {
                SCREEN_TRANSITION_REPEAT_GAP_MS
            }
        }
        return ((noteCount * SCREEN_TRANSITION_NOTE_MS) + gaps).toLong()
    }

    private fun millisecondsToSamples(milliseconds: Int): Int =
        (SCREEN_TONE_SAMPLE_RATE * milliseconds) / 1_000

    private fun releaseScreenTransitionTrack(track: AudioTrack) {
        if (screenTransitionTrack === track) screenTransitionTrack = null
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        }
        runCatching { track.release() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen content is deliberately not inspected.
    }

    override fun onInterrupt() {
        handler.removeCallbacks(resolvePendingGesture)
        handler.removeCallbacks(warningSinglePress)
        warningPressCount = 0
        detector.cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacks(resolvePendingGesture)
        handler.removeCallbacks(warningSinglePress)
        warningPressCount = 0
        detector.cancel()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenOnReceiver)
            screenReceiverRegistered = false
        }
        screenTransitionTrack?.let(::releaseScreenTransitionTrack)
        super.onDestroy()
    }

    companion object {
        private const val ACK_BEEP_INTERVAL_MS = 150L
        private const val WARNING_DOUBLE_PRESS_MS = 800L
        private const val SA_HZ = 329.63
        private const val PA_HZ = 493.88
        private const val UPPER_SA_HZ = 659.25
        private const val SCREEN_TONE_SAMPLE_RATE = 44_100
        private const val SCREEN_TONE_AMPLITUDE = 0.48
        private const val SCREEN_TRANSITION_NOTE_MS = 135
        private const val SCREEN_TRANSITION_REPEAT_GAP_MS = 45
        private const val SCREEN_TRANSITION_PHRASE_GAP_MS = 85
        private const val SCREEN_TRANSITION_FADE_MS = 12
        private const val SCREEN_TONE_RELEASE_MARGIN_MS = 120L
    }
}
