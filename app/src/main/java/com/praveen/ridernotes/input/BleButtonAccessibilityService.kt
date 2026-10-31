package com.praveen.ridernotes.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Observes hardware keys globally and classifies Beauty-R1's usable camera
 * button as a single, double or triple press.
 */
class BleButtonAccessibilityService : AccessibilityService() {
    private val detector = RemotePressPatternDetector()
    private val handler = Handler(Looper.getMainLooper())

    private var lastDeviceId = -1
    private var lastDeviceName = "Beauty-R1"

    private val resolvePendingGesture = Runnable {
        detector.resolve(SystemClock.uptimeMillis())?.let(::recordGesture)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Accessibility service"))

        if (!event.isBeautyCameraButton()) {
            return false
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

        handler.removeCallbacks(resolvePendingGesture)
        detector.onPress(event.eventTime)?.let(::recordGesture)

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
    }

    private fun KeyEvent.isBeautyCameraButton(): Boolean {
        val isBeautyDevice = device?.name?.contains("Beauty-R1", ignoreCase = true) == true
        val isAlternatingCameraKey =
            keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        return isBeautyDevice && isAlternatingCameraKey
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen content is deliberately not inspected.
    }

    override fun onInterrupt() {
        handler.removeCallbacks(resolvePendingGesture)
        detector.cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacks(resolvePendingGesture)
        detector.cancel()
        super.onDestroy()
    }
}
