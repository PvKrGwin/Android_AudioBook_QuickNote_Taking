package com.praveen.ridernotes.assistant

import android.service.voice.VoiceInteractionService

/**
 * Makes Rider Notes eligible for Android's Digital Assistant role.
 *
 * Android keeps the selected service available, so this class deliberately does no
 * continuous work. Recording is changed only when a session is explicitly shown.
 */
class RiderVoiceInteractionService : VoiceInteractionService()
