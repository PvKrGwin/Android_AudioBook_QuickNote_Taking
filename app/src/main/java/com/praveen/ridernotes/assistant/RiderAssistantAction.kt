package com.praveen.ridernotes.assistant

import android.content.Context
import com.praveen.ridernotes.recording.RecordingController

/** Executes the same recording action regardless of how Android invokes Rider Notes. */
internal object RiderAssistantAction {
    fun run(context: Context): String = when {
        RecordingController.isRecordingOrStarting &&
            RecordingController.isExtensionWindowOpen ->
            RecordingController.extend(context)
        RecordingController.isRecordingOrStarting ->
            RecordingController.stop(context)
        else -> RecordingController.start(context)
    }
}
