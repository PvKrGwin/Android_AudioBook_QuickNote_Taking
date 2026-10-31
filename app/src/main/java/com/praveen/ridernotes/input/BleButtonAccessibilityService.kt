package com.praveen.ridernotes.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Observes remote key events without consuming them.
 * A screen-off remote press produces a one-second audible acknowledgement.
 * Android must deliver the event for this feedback to be possible.
 */
class BleButtonAccessibilityService : AccessibilityService() {
    private var lastBeepAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Accessibility service"))
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
             event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.device?.name?.contains("Beauty-R1", ignoreCase = true) == true
        ) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val now = SystemClock.elapsedRealtime()
            if (!power.isInteractive && now - lastBeepAt >= 1200L) {
                lastBeepAt = now
                // Use the music stream so acknowledgement is audible with audiobook playback.
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 1000)
                android.os.Handler(mainLooper).postDelayed({ tone.release() }, 1100L)
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen content is deliberately not inspected.
    }

    override fun onInterrupt() {}
}
