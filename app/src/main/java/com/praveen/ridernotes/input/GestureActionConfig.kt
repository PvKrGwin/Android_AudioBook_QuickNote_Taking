package com.praveen.ridernotes.input

import android.content.Context

/** Persistent, user-editable action mapping for the trained remote button. */
object GestureActionConfig {
    enum class Action(val label: String) {
        PLAY_PAUSE("Play / pause"),
        RECORD_NOTE("Record voice note"),
        REWIND("Rewind"),
        FORWARD("Forward"),
        NONE("No action")
    }

    private const val PREFS = "rider_notes_gesture_actions"

    fun actionFor(context: Context, gesture: RemotePressPatternDetector.Gesture): Action {
        val default = defaultFor(gesture)
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(gesture.name, default.name)
        return runCatching { Action.valueOf(stored.orEmpty()) }.getOrDefault(default)
    }

    fun setAction(
        context: Context,
        gesture: RemotePressPatternDetector.Gesture,
        action: Action
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(gesture.name, action.name).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun defaultFor(gesture: RemotePressPatternDetector.Gesture): Action = when (gesture) {
        RemotePressPatternDetector.Gesture.SINGLE -> Action.PLAY_PAUSE
        RemotePressPatternDetector.Gesture.DOUBLE -> Action.RECORD_NOTE
        RemotePressPatternDetector.Gesture.TRIPLE -> Action.REWIND
        RemotePressPatternDetector.Gesture.QUADRUPLE -> Action.FORWARD
    }
}
