package com.praveen.ridernotes.input

import android.content.Context
import android.view.KeyEvent

object RemoteButtonConfig {
    private const val PREFS = "rider_notes_remote"
    private const val DEVICE = "device_name"
    private const val SIGNALS = "signals"
    private const val TRAINING = "training"

    data class Config(val deviceName: String, val signals: Set<String>)

    fun setTraining(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(TRAINING, active).apply()
    }

    fun isTraining(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(TRAINING, false)

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            prefs.getString(DEVICE, "Beauty-R1") ?: "Beauty-R1",
            prefs.getStringSet(
                SIGNALS,
                setOf(
                    signature(KeyEvent.KEYCODE_VOLUME_UP, 115),
                    signature(KeyEvent.KEYCODE_VOLUME_DOWN, 114)
                )
            ).orEmpty()
        )
    }

    fun save(context: Context, deviceName: String, signals: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DEVICE, deviceName)
            .putStringSet(SIGNALS, signals)
            .apply()
    }

    fun matches(context: Context, event: KeyEvent): Boolean {
        val config = load(context)
        return event.device?.name?.equals(config.deviceName, ignoreCase = true) == true &&
            signature(event.keyCode, event.scanCode) in config.signals
    }

    fun signature(keyCode: Int, scanCode: Int) = "$keyCode:$scanCode"
}
