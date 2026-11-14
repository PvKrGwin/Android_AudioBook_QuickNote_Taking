package com.praveen.ridernotes.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Android launches this activity through the system Assistant role. It deliberately
 * has no UI: invoking the assistant toggles the existing Rider Notes recording flow.
 */
class RiderAssistantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = RiderAssistantAction.run(this)
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }
}
