package com.praveen.ridernotes.call

import android.app.Notification
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.praveen.ridernotes.input.RemotePressPatternDetector
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives an ongoing phone or app call priority over Rider Notes media gestures.
 *
 * Call notification actions support both regular calls and calling apps such
 * as WhatsApp without making Rider Notes the default phone app.
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
        val actions = notification.actions?.toList().orEmpty()

        // Android 12+ CallStyle places the definitive PendingIntents in these
        // extras. Literal keys keep this source compatible with older SDK stubs.
        @Suppress("DEPRECATION")
        fun pendingIntent(key: String): PendingIntent? =
            notification.extras.getParcelable(key) as? PendingIntent

        val answer = pendingIntent(EXTRA_ANSWER_INTENT)
            ?: actions.firstByTitle("answer", "accept", "pick up")
        val reject = pendingIntent(EXTRA_DECLINE_INTENT)
            ?: actions.firstByTitle("decline", "reject")
        val hangUp = pendingIntent(EXTRA_HANG_UP_INTENT)
            ?: actions.firstByTitle("hang up", "end call", "disconnect")

        val isCall = notification.category == Notification.CATEGORY_CALL ||
            answer != null || reject != null || hangUp != null
        if (!isCall || (answer == null && reject == null && hangUp == null)) {
            calls.remove(sbn.key)
            return
        }

        calls[sbn.key] = CallActions(
            packageName = sbn.packageName,
            answer = answer,
            reject = reject,
            hangUp = hangUp,
            updatedAt = SystemClock.elapsedRealtime()
        )
    }

    fun removeNotification(sbn: StatusBarNotification) {
        calls.remove(sbn.key)
    }

    fun clear() {
        calls.clear()
    }

    fun hasOngoingCall(context: Context): Boolean =
        (currentCall()?.state ?: CallState.NONE) != CallState.NONE ||
            cellularCallState(context) != CallState.NONE ||
            audioCallState(context) != CallState.NONE

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
        val cellularState = cellularCallState(context)
        val state = call?.state?.takeIf { it != CallState.NONE }
            ?: cellularState.takeIf { it != CallState.NONE }
            ?: audioCallState(context)
        if (state == CallState.NONE) return null

        return when (state) {
            CallState.RINGING -> when (gesture) {
                RemotePressPatternDetector.Gesture.SINGLE ->
                    send(call?.answer)
                        ?: if (cellularState == CallState.RINGING) {
                            answerCellularCall(context)
                        } else {
                            dispatchHeadsetHook(context, "Answered call")
                        }
                RemotePressPatternDetector.Gesture.DOUBLE ->
                    send(call?.reject)
                        ?: if (cellularState == CallState.RINGING) {
                            endCellularCall(context, "Rejected cellular call")
                        } else {
                            Result(false, "Reject unavailable: calling app exposed no decline action")
                        }
                else -> Result(false, "Call ringing: use single to answer or double to reject")
            }
            CallState.ACTIVE -> when (gesture) {
                RemotePressPatternDetector.Gesture.SINGLE,
                RemotePressPatternDetector.Gesture.DOUBLE ->
                    send(call?.hangUp)
                        ?: if (cellularState == CallState.ACTIVE) {
                            endCellularCall(context, "Ended cellular call")
                        } else {
                            dispatchHeadsetHook(context, "Ended call")
                        }
                else -> Result(false, "Call active: use single or double to end")
            }
            CallState.NONE -> null
        }
    }

    private fun currentCall(): CallActions? =
        calls.values.maxByOrNull { it.updatedAt }

    private fun hasPhonePermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun cellularCallState(context: Context): CallState {
        if (!hasPhonePermission(context, Manifest.permission.READ_PHONE_STATE)) {
            return CallState.NONE
        }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return when (telephony.callState) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.ACTIVE
            else -> CallState.NONE
        }
    }

    @Suppress("DEPRECATION")
    private fun answerCellularCall(context: Context): Result {
        if (!hasPhonePermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return Result(false, "Enable Rider Notes phone-call permission")
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            Result(true, "Answered cellular call")
        } catch (error: SecurityException) {
            Result(false, "Cellular answer blocked: phone-call permission required")
        } catch (error: RuntimeException) {
            Result(false, "Cellular answer failed: ${error.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun endCellularCall(context: Context, successMessage: String): Result {
        if (!hasPhonePermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            return Result(false, "Enable Rider Notes phone-call permission")
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (telecom.endCall()) {
                Result(true, successMessage)
            } else {
                Result(false, "Android did not end the cellular call")
            }
        } catch (error: SecurityException) {
            Result(false, "Cellular call control blocked by Android")
        } catch (error: RuntimeException) {
            Result(false, "Cellular call control failed: ${error.javaClass.simpleName}")
        }
    }

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
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)
            )
            audio.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0)
            )
            Result(true, successMessage)
        } catch (error: RuntimeException) {
            Result(false, "Call control failed: ${error.javaClass.simpleName}")
        }
    }

    private fun List<Notification.Action>.firstByTitle(
        vararg labels: String
    ): PendingIntent? =
        firstOrNull { action ->
            val title = action.title?.toString()?.trim().orEmpty()
            labels.any { title.equals(it, ignoreCase = true) }
        }?.actionIntent

    private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
    private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
    private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
}
