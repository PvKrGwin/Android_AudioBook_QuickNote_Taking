package com.praveen.ridernotes.input

import android.content.Context
import android.content.Intent

object FloatingRecordConfig {
    const val ACTION_CHANGED = "com.praveen.ridernotes.FLOATING_RECORD_CONFIG_CHANGED"
    private const val PREFS = "rider_notes_floating_record"
    private const val ENABLED = "enabled"
    private const val POSITION_X = "position_x"
    private const val POSITION_Y = "position_y"

    // Temporarily disabled: accessibility overlays can interfere with some
    // Bluetooth headset/remote combinations. Keep the stored state forced off.
    fun isEnabled(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(ENABLED, false)) {
            preferences.edit().putBoolean(ENABLED, false).apply()
        }
        return false
    }

    fun setEnabled(context: Context, @Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, false).apply()
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    fun position(context: Context): Pair<Int, Int> {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getInt(POSITION_X, 24) to preferences.getInt(POSITION_Y, 240)
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(POSITION_X, x)
            .putInt(POSITION_Y, y)
            .apply()
    }
}
