package com.praveen.ridernotes.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A small in-memory log shared by the Activity and Accessibility service.
 * Persistence will be introduced only when the product actually needs it.
 */
object ButtonEventStore {
    private const val MAX_EVENTS = 100
    private const val SAME_EVENT_WINDOW_MS = 15L

    private val mutableEvents = MutableStateFlow<List<ButtonEvent>>(emptyList())
    val events: StateFlow<List<ButtonEvent>> = mutableEvents

    @Synchronized
    fun record(newEvent: ButtonEvent) {
        val current = mutableEvents.value

        // When the service is enabled and the app is open, the same KeyEvent can
        // pass through both routes. Merge those routes rather than showing a
        // confusing duplicate row.
        val duplicateIndex = current.indexOfFirst { existing ->
            existing.action == newEvent.action &&
                existing.keyCode == newEvent.keyCode &&
                existing.scanCode == newEvent.scanCode &&
                kotlin.math.abs(existing.eventTimeMillis - newEvent.eventTimeMillis) <= SAME_EVENT_WINDOW_MS
        }

        val updated = if (duplicateIndex >= 0) {
            current.toMutableList().apply {
                val previous = this[duplicateIndex]
                this[duplicateIndex] = previous.copy(
                    captureRoutes = previous.captureRoutes + newEvent.captureRoutes
                )
            }
        } else {
            listOf(newEvent) + current
        }

        mutableEvents.value = updated.take(MAX_EVENTS)
    }

    fun clear() {
        mutableEvents.value = emptyList()
    }

    fun asShareableText(): String = events.value
        .asReversed()
        .joinToString(separator = "\n") { it.asShareableText() }
}
