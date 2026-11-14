package com.praveen.ridernotes.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.widget.Toast

/** Handles a long-power assistant invocation without opening a Rider Notes screen. */
class RiderVoiceInteractionSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val result = RiderAssistantAction.run(appContext)
        Toast.makeText(appContext, result, Toast.LENGTH_SHORT).show()
        hide()
    }
}
