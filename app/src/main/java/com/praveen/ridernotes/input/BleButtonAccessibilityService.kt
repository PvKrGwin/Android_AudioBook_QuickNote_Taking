package com.praveen.ridernotes.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.praveen.ridernotes.R
import com.praveen.ridernotes.call.CallControlGateway
import com.praveen.ridernotes.media.MediaControlGateway
import com.praveen.ridernotes.recording.RecordingController

/**
 * Observes hardware keys globally and classifies Beauty-R1's usable camera
 * button as a single, double, triple or four-press gesture.
 */
class BleButtonAccessibilityService : AccessibilityService(), InputManager.InputDeviceListener {
    private val detector = RemotePressPatternDetector()
    private val handler = Handler(Looper.getMainLooper())

    private var lastDeviceId = -1
    private var lastDeviceName = "Beauty-R1"
    private var screenReceiverRegistered = false
    private var lastScreenOffBeepAt = 0L
    private var screenOffObserved = false
    private var lastScreenWakeAt = 0L
    private var warningPressCount = 0
    private var configReceiverRegistered = false
    private var foregroundPackage: String? = null
    private var dismissedPackage: String? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private val floatingConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingRecordConfig.ACTION_CHANGED) refreshFloatingRecordButton()
        }
    }

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
        registerFloatingObservers()
        refreshFloatingRecordButton()
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

    private fun registerFloatingObservers() {
        if (!configReceiverRegistered) {
            val filter = IntentFilter(FloatingRecordConfig.ACTION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(floatingConfigReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(floatingConfigReceiver, filter)
            }
            configReceiverRegistered = true
        }
        (getSystemService(Context.INPUT_SERVICE) as InputManager)
            .registerInputDeviceListener(this, handler)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshFloatingRecordButton()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshFloatingRecordButton()
    override fun onInputDeviceChanged(deviceId: Int) = refreshFloatingRecordButton()

    private fun refreshFloatingRecordButton() {
        val enabled = FloatingRecordConfig.isEnabled(this)
        val supported = MediaControlGateway.isSupportedPackage(foregroundPackage)
        val remoteConnected = isConfiguredRemoteConnected()
        val shouldShow = enabled && supported &&
            foregroundPackage != dismissedPackage && !remoteConnected
        if (enabled && supported) {
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "FLOATING_RECORD_CHECK: app=$foregroundPackage, remoteConnected=$remoteConnected, dismissed=${foregroundPackage == dismissedPackage}",
                    true
                )
            )
        }
        if (shouldShow) showFloatingRecordButton() else removeFloatingRecordButton()
    }

    private fun showFloatingRecordButton() {
        if (floatingView != null) return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(
                this@BleButtonAccessibilityService,
                R.drawable.floating_record_background
            )
            elevation = 8f * density
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.rider_notes_logo)
            contentDescription = "Record Rider Note"
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Close floating record button"
            setOnClickListener {
                dismissedPackage = foregroundPackage
                removeFloatingRecordButton()
            }
        }
        container.addView(icon, FrameLayout.LayoutParams(dp(58), dp(58)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(5), dp(5), dp(5), dp(5))
        })
        container.addView(close, FrameLayout.LayoutParams(dp(26), dp(26)).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        val (savedX, savedY) = FloatingRecordConfig.position(this)
        val params = WindowManager.LayoutParams(
            dp(72), dp(72),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, (resources.displayMetrics.widthPixels - dp(72)).coerceAtLeast(0))
            y = savedY.coerceIn(0, (resources.displayMetrics.heightPixels - dp(72)).coerceAtLeast(0))
        }
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragged = false
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    downX = params.x; downY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragged = true
                    params.x = (downX + dx).coerceIn(
                        0,
                        (resources.displayMetrics.widthPixels - container.width).coerceAtLeast(0)
                    )
                    params.y = (downY + dy).coerceIn(
                        0,
                        (resources.displayMetrics.heightPixels - container.height).coerceAtLeast(0)
                    )
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        FloatingRecordConfig.savePosition(this, params.x, params.y)
                    } else if (event.x < container.width - dp(26) || event.y > dp(26)) {
                        toggleFloatingRecording()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(container, params) }.onSuccess {
            floatingView = container
            floatingParams = params
            ButtonEventStore.record(ButtonEvent.recordingResult("Floating record button shown for $foregroundPackage", true))
        }.onFailure { error ->
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "Floating record button failed: ${error.javaClass.simpleName}: ${error.message}",
                    false
                )
            )
        }
    }

    private fun toggleFloatingRecording() {
        val message = if (RecordingController.isRecordingOrStarting) {
            RecordingController.stop(this)
        } else {
            RecordingController.start(this)
        }
        ButtonEventStore.record(
            ButtonEvent.recordingResult(
                message,
                message.contains("requested", ignoreCase = true)
            )
        )
    }

    private fun removeFloatingRecordButton() {
        val view = floatingView ?: return
        floatingView = null
        floatingParams = null
        runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun playScreenReadyTone() {
        playScreenTransitionTone(
            tones = intArrayOf(
                ToneGenerator.TONE_DTMF_1,
                ToneGenerator.TONE_DTMF_1,
                ToneGenerator.TONE_DTMF_5,
                ToneGenerator.TONE_DTMF_5,
                ToneGenerator.TONE_DTMF_9,
                ToneGenerator.TONE_DTMF_9
            ),
            volume = SCREEN_READY_VOLUME,
            failureLabel = "SCREEN_ON_ASCENDING_TONE"
        )
    }

    private fun playScreenOffTone() {
        playScreenTransitionTone(
            tones = intArrayOf(
                ToneGenerator.TONE_DTMF_9,
                ToneGenerator.TONE_DTMF_9,
                ToneGenerator.TONE_DTMF_5,
                ToneGenerator.TONE_DTMF_5,
                ToneGenerator.TONE_DTMF_1,
                ToneGenerator.TONE_DTMF_1
            ),
            volume = SCREEN_OFF_VOLUME,
            failureLabel = "SCREEN_OFF_DESCENDING_TONE"
        )
    }

    private fun playScreenTransitionTone(
        tones: IntArray,
        volume: Int,
        failureLabel: String
    ) {
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, volume)
        }.getOrElse { error ->
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "$failureLabel failed: ${error.javaClass.simpleName}",
                    false
                )
            )
            return
        }

        tones.forEachIndexed { index, tone ->
            handler.postDelayed({
                runCatching {
                    generator.startTone(tone, SCREEN_FALLBACK_NOTE_MS)
                }.onFailure { error ->
                    ButtonEventStore.record(
                        ButtonEvent.recordingResult(
                            "$failureLabel failed: ${error.javaClass.simpleName}",
                            false
                        )
                    )
                }
            }, index * SCREEN_FALLBACK_STEP_MS)
        }
        handler.postDelayed(
            { generator.release() },
            (tones.size * SCREEN_FALLBACK_STEP_MS) + SCREEN_FALLBACK_RELEASE_MARGIN_MS
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val nextPackage = event.packageName?.toString() ?: return
        if (nextPackage == "com.android.systemui") {
            removeFloatingRecordButton()
            return
        }
        if (nextPackage != foregroundPackage) {
            dismissedPackage = null
            foregroundPackage = nextPackage
        }
        refreshFloatingRecordButton()
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
        removeFloatingRecordButton()
        (getSystemService(Context.INPUT_SERVICE) as InputManager)
            .unregisterInputDeviceListener(this)
        if (configReceiverRegistered) {
            unregisterReceiver(floatingConfigReceiver)
            configReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val ACK_BEEP_INTERVAL_MS = 150L
        private const val WARNING_DOUBLE_PRESS_MS = 800L
        private const val SCREEN_READY_VOLUME = 85
        private const val SCREEN_OFF_VOLUME = 90
        private const val SCREEN_FALLBACK_NOTE_MS = 125
        private const val SCREEN_FALLBACK_STEP_MS = 175L
        private const val SCREEN_FALLBACK_RELEASE_MARGIN_MS = 180L
    }
}
