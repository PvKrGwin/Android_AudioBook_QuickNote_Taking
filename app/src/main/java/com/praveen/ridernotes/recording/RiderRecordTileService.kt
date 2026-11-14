package com.praveen.ridernotes.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore

/** Portable recording control exposed in Android Quick Settings. */
class RiderRecordTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        if (lastHandledClickElapsedMs != 0L &&
            now - lastHandledClickElapsedMs < DUPLICATE_CLICK_GUARD_MS
        ) {
            ButtonEventStore.record(
                ButtonEvent.recordingResult(
                    "Duplicate Quick Settings tile click ignored",
                    true
                )
            )
            updateTile()
            return
        }
        lastHandledClickElapsedMs = now
        val result = when {
            RecordingController.isRecordingOrStarting &&
                RecordingController.isExtensionWindowOpen ->
                RecordingController.extend(this)
            RecordingController.isRecordingOrStarting ->
                RecordingController.stop(this)
            else -> RecordingController.start(this)
        }
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        ButtonEventStore.record(
            ButtonEvent.recordingResult(
                "Quick Settings tile: $result",
                !result.contains("failed", ignoreCase = true) &&
                    !result.contains("required", ignoreCase = true)
            )
        )
        updateTile()
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (RecordingController.isRecordingOrStarting) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            label = when {
                RecordingController.isExtensionWindowOpen -> "Extend Rider Note"
                RecordingController.isRecordingOrStarting -> "Stop Rider Note"
                else -> "Start Rider Note"
            }
            updateTile()
        }
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, RiderRecordTileService::class.java)
            )
            context.sendBroadcast(
                Intent(ACTION_RECORDING_STATE_CHANGED).setPackage(context.packageName)
            )
        }

        const val ACTION_RECORDING_STATE_CHANGED =
            "com.praveen.ridernotes.action.RECORDING_STATE_CHANGED"

        private const val DUPLICATE_CLICK_GUARD_MS = 1_500L

        @Volatile
        private var lastHandledClickElapsedMs = 0L
    }
}
