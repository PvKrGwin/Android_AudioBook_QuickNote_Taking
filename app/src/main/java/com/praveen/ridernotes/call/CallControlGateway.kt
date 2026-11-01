package com.praveen.ridernotes.call

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import com.praveen.ridernotes.input.RemotePressPatternDetector
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives an ongoing phone or app call priority over Rider Notes media gestures.
 *
 * Call notification semantic actions support both regular calls and calling
 * apps such as WhatsApp without making Rider Notes the default phone app.
 */
object CallControlGateway {
    data class Result(val successful: Boolean, val message: String)

    private enum class CallState { NONE, RINGING, ACTIVE }

    private data class CallActions(
        val packageName: String,
        val answer: PendingIntent?,
        val reject: PendingIntent?,
        val hangUp: PendingIntent?,
        val updatedAt: Long
    ) {
        val state: CallState
            get() = if (answer != null || reject != null) CallState.RINGING
            else if (hangUp != null) CallState.ACTIVE
            else CallState.NONE
    }

    private val calls = ConcurrentHashMap<String, CallActions>()

    fun updateNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val actions = notification.actions.orEmpty()
        val isCall = notification.category == Notification.CATEGORY_CALL ||
            actions.any { it.isCallAction() }
        if (!isCall) {
            calls.remove(sbn.key)
            return
        }

        fun actionFor(vararg meanings: Int): PendingIntent? =
            actions.firstOrNull { it.semanticAction in meanings }?.actionIntent

        val answer = actionFor(Notification.Action.SEMANTIC_ACTION_ANSWER)
            ?: actions.firstByTitle("answer", "accept", "pick up")
        val reject = actionFor(Notification.Action.SEMANTIC_ACTION_DECLINE_CALL)
            ?: actions.firstByTitle("decline", "reject")
        val hangUp = actionFor(Notification.Action.SEMANTIC_ACTION_HANGUP_CALL)
            ?: actions.firstByTitle("hang up", "end call", "disconnect")

        if (answer == null && reject == null && hangUp == null) {
            calls.remove(sbn.key)
        } else {
            calls[sbn.key] = CallActions(
                packageName = sbn.packageName,
                answer = answer,
                reject = reject,
                hangUp = hangUp,
                updatedAt = SystemClock.elapsedRealtime()
            )
        }
    }

    fun removeNotification(sbn: StatusBarNotification) {
        calls.remove(sbn.key)
    }

    fun clear() {
        calls.clear()
    }

    fun hasOngoingCall(context: Context): Boolean =
        currentCall()?.state != CallState.NONE || audioCallState(context) != CallState.NONE

    /**
     * Incoming call: single answers, double rejects.
     * Active call: single or double hangs up.
     * Triple/four-press remain ignored during calls to avoid accidental seeks.
     */
    fun handleGesture(
        context: Context,
        gesture: RemotePressPatternDetector.Gesture
    ): Result? {
        val call = currentCall()
        val state = call?.state?.takeIf { it != CallState.NONE } ?: audioCallState(context)
        if (state == CallState.NONE) return null

        return when (state) {
            CallState.RINGING -> when (gesture) {
                RemotePressPatternDetector.Gesture.SINGLE ->
                    send(call?.answer) ?: dispatchHeadsetHook(context, "Answered call")
                RemotePressPatternDetector.Gesture.DOUBLE ->
                    send(call?.reject)
                        ?: Result(false, "Reject unavailable: calling app exposed no decline action")
                else -> Result(false, "Call ringing: use single to answer or double to reject")
            }
            CallState.ACTIVE -> when (gesture) {
                RemotePressPatternDetector.Gesture.SINGLE,
                RemotePressPatternDetector.Gesture.DOUBLE ->
                    send(call?.hangUp) ?: dispatchHeadsetHook(context, "Ended call")
                else -> Result(false, "Call active: use single or double to end")
            }
            CallState.NONE -> null
        }
    }

    private fun currentCall(): CallActions? =
        calls.values.maxByOrNull { it.updatedAt }

    private fun audioCallState(context: Context): CallState {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (audio.mode) {
            AudioManager.MODE_RINGTONE -> CallState.RINGING
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> CallState.ACTIVE
            else -> CallState.NONE
        }
    }

    private fun send(intent: PendingIntent?): Result? {
        if (intent == null) return null
        return try {
            intent.send()
            Result(true, "Call action sent")
        } catch (error: PendingIntent.CanceledException) {
            Result(false, "Call action expired")
        }
    }

    private fun dispatchHeadsetHook(context: Context, successMessage: String): Result {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSEATHOOK, 0)
            )
            audio.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSEATHOOK, 0)
            )
            Result(true, successMessage)
        } catch (error: RuntimeException) {
            Result(false, "Call control failed: ${error.javaClass.simpleName}")
        }
    }

    private fun Notification.Action.isCallAction(): Boolean =
        semanticAction == Notification.Action.SEMANTIC_ACTION_ANSWER ||
            semanticAction == Notification.Action.SEMANTIC_ACTION_DECLINE_CALL ||
            semanticAction == Notification.Action.SEMANTIC_ACTION_HANGUP_CALL

    private fun Array<Notification.Action>.firstByTitle(
        vararg labels: String
    ): PendingIntent? =
        firstOrNull { action ->
            val title = action.title?.toString()?.trim().orEmpty()
            labels.any { title.equals(it, ignoreCase = true) }
        }?.actionIntent
}
