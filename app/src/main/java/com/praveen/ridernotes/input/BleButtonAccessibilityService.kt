package com.praveen.ridernotes.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Receives hardware KeyEvents before the currently visible application.
 *
 * Returning false is intentional: it tells Android that Rider Notes observed
 * the event but did not consume it. YouTube, volume control, and other apps can
 * therefore continue responding normally during Milestone 1 testing.
 */
class BleButtonAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Accessibility service"))
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen content is deliberately not inspected in this milestone.
    }

    override fun onInterrupt() {
        // No continuous feedback is produced, so there is nothing to interrupt.
    }
}
