package com.praveen.ridernotes.input

import android.view.KeyEvent
import android.view.MotionEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A readable snapshot of one external-input event. */
data class ButtonEvent(
    val capturedAtMillis: Long,
    val eventTimeMillis: Long,
    val action: String,
    val keyCode: Int,
    val keyName: String,
    val scanCode: Int,
    val repeatCount: Int,
    val isLongPress: Boolean,
    val deviceId: Int,
    val deviceName: String,
    val sourceHex: String,
    val captureRoutes: Set<String>,
    val details: String = ""
) {
    val likelyMeaning: String
        get() = if (details.isNotBlank()) details else ButtonMeaning.forKeyCode(keyCode)

    fun asShareableText(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(capturedAtMillis))
        return buildString {
            append(time)
            append(" | ")
            append(action)
            append(" | ")
            append(keyName)
            if (keyCode >= 0) {
                append(" (")
                append(keyCode)
                append(") | scan=")
                append(scanCode)
                append(" | repeat=")
                append(repeatCount)
                append(" | long=")
                append(isLongPress)
            }
            append(" | device=")
            append(deviceName)
            append(" [")
            append(deviceId)
            append("] | source=")
            append(sourceHex)
            append(" | via=")
            append(captureRoutes.sorted().joinToString(" + "))
            append(" | likely=")
            append(likelyMeaning)
        }
    }

    companion object {
        fun from(event: KeyEvent, route: String): ButtonEvent {
            val actionName = when (event.action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP"
                KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
                else -> "UNKNOWN(${event.action})"
            }

            return ButtonEvent(
                capturedAtMillis = System.currentTimeMillis(),
                eventTimeMillis = event.eventTime,
                action = actionName,
                keyCode = event.keyCode,
                keyName = KeyEvent.keyCodeToString(event.keyCode),
                scanCode = event.scanCode,
                repeatCount = event.repeatCount,
                isLongPress = event.isLongPress,
                deviceId = event.deviceId,
                deviceName = event.device?.name ?: "Unknown device",
                sourceHex = "0x${event.source.toString(16)}",
                captureRoutes = setOf(route)
            )
        }

        fun from(event: MotionEvent, route: String): ButtonEvent {
            val actionName = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> "MOTION_DOWN"
                MotionEvent.ACTION_UP -> "MOTION_UP"
                MotionEvent.ACTION_MOVE -> "MOTION_MOVE"
                MotionEvent.ACTION_SCROLL -> "MOTION_SCROLL"
                MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
                MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
                MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
                else -> "MOTION_${event.actionMasked}"
            }
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)

            return ButtonEvent(
                capturedAtMillis = System.currentTimeMillis(),
                eventTimeMillis = event.eventTime,
                action = actionName,
                keyCode = -1,
                keyName = "MOTION_EVENT",
                scanCode = 0,
                repeatCount = 0,
                isLongPress = false,
                deviceId = event.deviceId,
                deviceName = event.device?.name ?: "Unknown device",
                sourceHex = "0x${event.source.toString(16)}",
                captureRoutes = setOf(route),
                details = "Motion: button=${event.actionButton}, buttons=${event.buttonState}, " +
                    "vScroll=$vertical, hScroll=$horizontal"
            )
        }

        fun gesture(
            gesture: RemotePressPatternDetector.Gesture,
            deviceId: Int,
            deviceName: String
        ): ButtonEvent = ButtonEvent(
            capturedAtMillis = System.currentTimeMillis(),
            eventTimeMillis = android.os.SystemClock.uptimeMillis(),
            action = "GESTURE",
            keyCode = -1,
            keyName = "BEAUTY_${gesture.name}",
            scanCode = 0,
            repeatCount = 0,
            isLongPress = false,
            deviceId = deviceId,
            deviceName = deviceName,
            sourceHex = "0x301",
            captureRoutes = setOf("Beauty-R1 gesture classifier"),
            details = when (gesture) {
                RemotePressPatternDetector.Gesture.SINGLE -> "Single press — Play/Pause requested"
                RemotePressPatternDetector.Gesture.DOUBLE -> "Double press — voice note requested"
                RemotePressPatternDetector.Gesture.TRIPLE -> "Triple press — rewind 10 seconds requested"
                RemotePressPatternDetector.Gesture.QUADRUPLE -> "Four presses — forward 10 seconds requested"
            }
        )

        fun recordingResult(message: String, successful: Boolean): ButtonEvent = ButtonEvent(
            capturedAtMillis = System.currentTimeMillis(),
            eventTimeMillis = android.os.SystemClock.uptimeMillis(),
            action = if (successful) "RECORDING_OK" else "RECORDING_INFO",
            keyCode = -1,
            keyName = "VOICE_NOTE_RESULT",
            scanCode = 0,
            repeatCount = 0,
            isLongPress = false,
            deviceId = -1,
            deviceName = "Phone microphone",
            sourceHex = "n/a",
            captureRoutes = setOf("Rider Notes recorder"),
            details = message
        )

        fun mediaResult(message: String, successful: Boolean): ButtonEvent = ButtonEvent(
            capturedAtMillis = System.currentTimeMillis(),
            eventTimeMillis = android.os.SystemClock.uptimeMillis(),
            action = if (successful) "MEDIA_OK" else "MEDIA_INFO",
            keyCode = -1,
            keyName = "MEDIA_ACTION_RESULT",
            scanCode = 0,
            repeatCount = 0,
            isLongPress = false,
            deviceId = -1,
            deviceName = "Android media session",
            sourceHex = "n/a",
            captureRoutes = setOf("Rider Notes media controller"),
            details = message
        )
    }
}

/** Human-readable hints only; the captured numeric data remains authoritative. */
object ButtonMeaning {
    fun forKeyCode(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Media play/pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Media play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Media pause"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "Media rewind"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Media fast-forward"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Media previous"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "Media next"
        KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
        KeyEvent.KEYCODE_DPAD_UP -> "Up / scroll"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down / scroll"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left / previous"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right / next"
        KeyEvent.KEYCODE_DPAD_CENTER -> "Centre/confirm"
        KeyEvent.KEYCODE_ENTER -> "Enter/confirm"
        KeyEvent.KEYCODE_CAMERA -> "Camera shutter"
        KeyEvent.KEYCODE_PAGE_UP -> "Page up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page down"
        else -> "Unmapped—keep this result"
    }
}
