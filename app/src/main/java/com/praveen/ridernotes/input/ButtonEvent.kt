package com.praveen.ridernotes.input

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A readable snapshot of one Android hardware-key event.
 *
 * Android reuses KeyEvent objects internally, so we copy the useful values
 * immediately instead of storing the original object.
 */
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
    val captureRoutes: Set<String>
) {
    val likelyMeaning: String
        get() = ButtonMeaning.forKeyCode(keyCode)

    fun asShareableText(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(capturedAtMillis))
        return buildString {
            append(time)
            append(" | ")
            append(action)
            append(" | ")
            append(keyName)
            append(" (")
            append(keyCode)
            append(") | scan=")
            append(scanCode)
            append(" | repeat=")
            append(repeatCount)
            append(" | long=")
            append(isLongPress)
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
    }
}

/** Human-readable hints only; the captured numeric data remains authoritative. */
object ButtonMeaning {
    fun forKeyCode(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Media play/pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Media play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Media pause"
        KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
        KeyEvent.KEYCODE_DPAD_UP -> "Up / scroll"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down / scroll"
        KeyEvent.KEYCODE_DPAD_CENTER -> "Centre/confirm"
        KeyEvent.KEYCODE_ENTER -> "Enter/confirm"
        KeyEvent.KEYCODE_CAMERA -> "Camera shutter"
        KeyEvent.KEYCODE_PAGE_UP -> "Page up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page down"
        else -> "Unmapped—keep this result"
    }
}
