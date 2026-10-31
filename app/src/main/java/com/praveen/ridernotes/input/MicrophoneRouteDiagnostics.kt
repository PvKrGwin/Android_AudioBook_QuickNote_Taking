package com.praveen.ridernotes.input

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build

/**
 * Recorder integration helper. Call after AudioRecord.startRecording(), not before.
 * A Bluetooth connection or successful recording alone does not prove microphone routing.
 */
object MicrophoneRouteDiagnostics {
    fun describe(context: Context, recorder: AudioRecord): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val actual = recorder.routedDevice
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val bluetoothInputs = inputs.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val type = actual?.type
        val isBluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        return "Microphone route: " + when {
            actual == null -> "UNKNOWN (no routed input reported)"
            isBluetooth -> "BLUETOOTH CONFIRMED"
            else -> "NOT BLUETOOTH / PHONE OR OTHER INPUT"
        } + "; active=" + (actual?.productName ?: "unknown") +
            "; type=" + (type ?: "unknown") +
            "; available Bluetooth inputs=" +
            bluetoothInputs.joinToString { it.productName.toString() }
    }
}
