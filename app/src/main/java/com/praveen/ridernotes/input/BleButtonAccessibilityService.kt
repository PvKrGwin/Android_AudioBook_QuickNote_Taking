package com.praveen.ridernotes.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
                Intent.ACTION_SCREEN_OFF -> screenOffObserved = true
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
                val message = RecordingController.start(this)
                ButtonEventStore.record(
                    ButtonEvent.recordingResult(
                        message = message,
                        successful = message.endsWith("recording start requested")
                    )
                )
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
        val count = when (gesture) {
            RemotePressPatternDetector.Gesture.SINGLE -> 1
            RemotePressPatternDetector.Gesture.DOUBLE -> 2
            RemotePressPatternDetector.Gesture.TRIPLE -> 3
            RemotePressPatternDetector.Gesture.QUADRUPLE -> 4
        }
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        repeat(count) { index ->
            handler.postDelayed({
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                if (index == count - 1) handler.postDelayed({ tone.release() }, 180L)
            }, index * 150L)
        }
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
        ToneGenerator(AudioManager.STREAM_MUSIC, SCREEN_READY_VOLUME).apply {
            startTone(ToneGenerator.TONE_PROP_ACK, SCREEN_READY_BEEP_MS)
            handler.postDelayed({ release() }, SCREEN_READY_BEEP_MS + 50L)
        }
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
        super.onDestroy()
    }

    companion object {
        private const val WARNING_DOUBLE_PRESS_MS = 800L
        private const val SCREEN_READY_BEEP_MS = 180
        private const val SCREEN_READY_VOLUME = 85
    }
}
