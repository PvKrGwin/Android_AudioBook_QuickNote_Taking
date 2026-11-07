package com.praveen.ridernotes.transcription

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.praveen.ridernotes.cloud.CloudSyncManager
import org.json.JSONArray
import org.json.JSONObject
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.ITranscriptionSession
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest
import java.io.File
import java.util.ArrayDeque

/**
 * Sends saved Rider Notes recordings to a user-approved Open Transcribe engine.
 * Scrib implements this contract entirely on-device.
 */
object ScribTranscriber {
    private data class TimedSegment(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )
    private const val ACTION = "org.opentranscribe.api.ITranscriptionService"
    private const val SCRIB_PACKAGE = "org.scrib.transcriber"
    private const val PREFS = "rider_notes_transcription"
    private const val PREF_COMPONENT = "approved_transcriber_component"
    private const val PREF_LANGUAGE_MODE = "transcription_language_mode"

    const val LANGUAGE_AUTO = "AUTO"
    const val LANGUAGE_ENGLISH = "ENGLISH"
    const val LANGUAGE_KANNADA = "KANNADA"

    private data class ActiveJob(
        val file: File,
        val timeoutMs: Long,
        val connection: ServiceConnection,
        var session: ITranscriptionSession? = null
    )

    private data class QueuedJob(val file: File, val timeoutMs: Long)

    private val queue = ArrayDeque<QueuedJob>()
    private var activeJob: ActiveJob? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var activeTimeout: Runnable? = null

    fun status(context: Context): String {
        val component = approvedComponent(context)
        return when {
            component == null && findScrib(context) != null ->
                "Scrib installed — tap Enable Scrib transcription"
            component == null ->
                "Scrib not detected"
            else ->
                "Scrib enabled for automatic offline transcription"
        }
    }

    fun enableScrib(context: Context): String {
        val component = findScrib(context)
            ?: return "Scrib was not detected. Install and open Scrib first."

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_COMPONENT, component.flattenToString())
            .apply()

        return "Scrib enabled. New recordings will be transcribed automatically."
    }

    fun languageMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE_MODE, LANGUAGE_ENGLISH)
            ?: LANGUAGE_ENGLISH

    fun setLanguageMode(context: Context, mode: String) {
        require(mode in setOf(LANGUAGE_AUTO, LANGUAGE_ENGLISH, LANGUAGE_KANNADA))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LANGUAGE_MODE, mode)
            .apply()
    }

    fun languageLabel(mode: String): String = when (mode) {
        LANGUAGE_AUTO -> "Auto"
        LANGUAGE_KANNADA -> "Kannada"
        else -> "English"
    }

    @Synchronized
    fun enqueue(context: Context, audioFile: File) {
        val savedTimeout = readTimeout(metadataFileFor(audioFile))
        enqueue(context, audioFile, savedTimeout)
    }

    @Synchronized
    private fun enqueue(context: Context, audioFile: File, timeoutMs: Long) {
        val appContext = context.applicationContext
        val metadataFile = metadataFileFor(audioFile)
        val component = approvedComponent(appContext)

        if (component == null) {
            updateMetadata(
                metadataFile,
                "SCRIB_NOT_ENABLED",
                error = "Open Rider Notes Home and enable Scrib transcription"
            )
            return
        }

        if (activeJob?.file?.absolutePath == audioFile.absolutePath ||
            queue.any { it.file.absolutePath == audioFile.absolutePath }
        ) return
        updateMetadata(metadataFile, "QUEUED", timeoutMs = timeoutMs)
        queue.addLast(QueuedJob(audioFile, timeoutMs))
        startNext(appContext)
    }

    @Synchronized
    fun stop(context: Context, audioFile: File): String {
        val queued = queue.firstOrNull { it.file.absolutePath == audioFile.absolutePath }
        if (queued != null) {
            queue.remove(queued)
            updateMetadata(metadataFileFor(audioFile), "PAUSED", error = "Stopped by user")
            return "Transcription stopped; the next queued recording can continue."
        }
        val job = activeJob
        if (job?.file?.absolutePath != audioFile.absolutePath) {
            return "This recording is not currently queued or transcribing."
        }
        runCatching { job.session?.cancel() }
        updateMetadata(metadataFileFor(audioFile), "PAUSED", error = "Stopped by user")
        finishActive(context.applicationContext, audioFile, startNext = true)
        return "Transcription stopped; moving to the next queued recording."
    }

    @Synchronized
    fun retry(context: Context, audioFile: File): String {
        val wasTimedOut = readStatus(metadataFileFor(audioFile)) == "TIMED_OUT"
        stop(context, audioFile)
        val timeoutMs = if (wasTimedOut) RETRY_AFTER_TIMEOUT_MS else NORMAL_TIMEOUT_MS
        enqueue(context, audioFile, timeoutMs)
        return if (wasTimedOut) {
            "Timed-out transcription queued again with a 30-minute limit."
        } else {
            "Transcription queued again with a 10-minute limit."
        }
    }

    @Synchronized
    private fun startNext(appContext: Context) {
        if (activeJob != null || queue.isEmpty()) return
        val queuedJob = queue.removeFirst()
        val audioFile = queuedJob.file
        if (!audioFile.exists()) {
            startNext(appContext)
            return
        }
        val metadataFile = metadataFileFor(audioFile)
        val component = approvedComponent(appContext)
        if (component == null) {
            updateMetadata(metadataFile, "SCRIB_NOT_ENABLED", error = "Enable Scrib transcription")
            startNext(appContext)
            return
        }

        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val service = ITranscriptionService.Stub.asInterface(binder)
                    val capabilities = service.capabilities
                    if (!capabilities.modelReady) {
                        updateMetadata(
                            metadataFile,
                            "MODEL_REQUIRED",
                            error = "Open Scrib and download/select a transcription model"
                        )
                        finishActive(appContext, audioFile, startNext = true)
                        return
                    }

                    updateMetadata(
                        metadataFile,
                        "TRANSCRIBING",
                        timeoutMs = queuedJob.timeoutMs
                    )
                    scheduleTimeout(
                        appContext,
                        audioFile,
                        metadataFile,
                        queuedJob.timeoutMs
                    )
                    val audio = ParcelFileDescriptor.open(
                        audioFile,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    val request = TranscriptionRequest().apply {
                        fileName = audioFile.name
                        mimeType = "audio/mp4"
                        languageHint = when (languageMode(appContext)) {
                            LANGUAGE_AUTO -> ""
                            LANGUAGE_KANNADA -> "kn"
                            else -> "en"
                        }
                    }
                    val timedSegments = mutableListOf<TimedSegment>()
                    val callback = object : ITranscriptionCallback.Stub() {
                        override fun onTranscriptionProgress(text: String?) {
                            if (!isActive(audioFile)) return
                            updateMetadata(metadataFile, "TRANSCRIBING")
                        }

                        override fun onTranscriptionResult(text: String?) {
                            if (!isActive(audioFile)) return
                            val transcript = text?.trim().orEmpty()
                            if (transcript.isBlank()) {
                                updateMetadata(
                                    metadataFile,
                                    "NO_SPEECH",
                                    error = "Scrib returned an empty transcript"
                                )
                            } else {
                                val completedSegments = synchronized(timedSegments) {
                                    timedSegments.sortedBy { it.startMs }.toList()
                                }
                                updateMetadata(
                                    metadataFile,
                                    "COMPLETED",
                                    transcript = transcript,
                                    segments = completedSegments
                                )
                                File(
                                    audioFile.parentFile,
                                    audioFile.nameWithoutExtension + ".txt"
                                ).writeText(transcript)
                            }
                            CloudSyncManager.enqueueNow(appContext)
                            finishActive(appContext, audioFile, startNext = true)
                        }

                        override fun onTranscriptionSegment(
                            startMs: Long,
                            endMs: Long,
                            text: String?
                        ) {
                            if (!isActive(audioFile)) return
                            val value = text?.trim().orEmpty()
                            if (value.isNotBlank() && startMs >= 0L && endMs > startMs) {
                                synchronized(timedSegments) {
                                    timedSegments += TimedSegment(startMs, endMs, value)
                                }
                            }
                        }

                        override fun onTranscriptionError(error: TranscriptionError?) {
                            if (!isActive(audioFile)) return
                            val detail = error?.message
                                ?: error?.type?.toString()
                                ?: "Unknown Scrib error"
                            updateMetadata(metadataFile, "FAILED", error = detail)
                            CloudSyncManager.enqueueNow(appContext)
                            finishActive(appContext, audioFile, startNext = true)
                        }
                    }

                    val session = service.transcribe(audio, request, callback)
                    synchronized(this@ScribTranscriber) {
                        activeJob?.takeIf {
                            it.file.absolutePath == audioFile.absolutePath
                        }?.session = session
                    }
                    audio.close()
                } catch (error: Exception) {
                    updateMetadata(
                        metadataFile,
                        "FAILED",
                        error = "Scrib connection failed: " +
                            error.javaClass.simpleName
                    )
                    finishActive(appContext, audioFile, startNext = true)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finishActive(appContext, audioFile, startNext = true)
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!isActive(audioFile)) return
                updateMetadata(
                    metadataFile,
                    "FAILED",
                    error = "Scrib service stopped unexpectedly"
                )
                finishActive(appContext, audioFile, startNext = true)
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!isActive(audioFile)) return
                updateMetadata(
                    metadataFile,
                    "FAILED",
                    error = "Scrib did not provide its transcription service"
                )
                finishActive(appContext, audioFile, startNext = true)
            }
        }

        activeJob = ActiveJob(audioFile, queuedJob.timeoutMs, connection)
        val intent = Intent(ACTION).setComponent(component)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            updateMetadata(
                metadataFile,
                "FAILED",
                error = "Could not connect to Scrib"
            )
            activeJob = null
            startNext(appContext)
        }
    }

    @Synchronized
    private fun finishActive(appContext: Context, expectedFile: File, startNext: Boolean) {
        val job = activeJob?.takeIf {
            it.file.absolutePath == expectedFile.absolutePath
        } ?: return
        activeTimeout?.let(timeoutHandler::removeCallbacks)
        activeTimeout = null
        activeJob = null
        try {
            appContext.unbindService(job.connection)
        } catch (_: IllegalArgumentException) {
            // Already disconnected.
        }
        if (startNext) startNext(appContext)
    }

    @Synchronized
    private fun scheduleTimeout(
        appContext: Context,
        audioFile: File,
        metadataFile: File,
        timeoutMs: Long
    ) {
        activeTimeout?.let(timeoutHandler::removeCallbacks)
        val timeout = Runnable {
            synchronized(this@ScribTranscriber) {
                val job = activeJob?.takeIf {
                    it.file.absolutePath == audioFile.absolutePath
                } ?: return@synchronized
                runCatching { job.session?.cancel() }
                updateMetadata(
                    metadataFile,
                    "TIMED_OUT",
                    error = "Scrib exceeded the ${timeoutMs / 60_000L}-minute transcription limit",
                    timeoutMs = timeoutMs
                )
                finishActive(appContext, audioFile, startNext = true)
            }
        }
        activeTimeout = timeout
        timeoutHandler.postDelayed(timeout, timeoutMs)
    }

    @Synchronized
    private fun isActive(audioFile: File): Boolean =
        activeJob?.file?.absolutePath == audioFile.absolutePath

    private fun findScrib(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(ACTION), 0)
        val service = services.firstOrNull {
            it.serviceInfo.packageName == SCRIB_PACKAGE
        } ?: return null
        return ComponentName(service.serviceInfo.packageName, service.serviceInfo.name)
    }

    private fun approvedComponent(context: Context): ComponentName? {
        val flattened = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_COMPONENT, null)
            ?: return null
        val component = ComponentName.unflattenFromString(flattened) ?: return null
        return if (component.packageName == SCRIB_PACKAGE) component else null
    }

    private fun metadataFileFor(audioFile: File) =
        File(audioFile.parentFile, audioFile.nameWithoutExtension + ".json")

    private fun readStatus(metadataFile: File): String? = runCatching {
        JSONObject(metadataFile.readText()).optString("transcriptionStatus")
    }.getOrNull()

    private fun readTimeout(metadataFile: File): Long = runCatching {
        JSONObject(metadataFile.readText())
            .optLong("transcriptionTimeoutMs", NORMAL_TIMEOUT_MS)
            .coerceIn(NORMAL_TIMEOUT_MS, RETRY_AFTER_TIMEOUT_MS)
    }.getOrDefault(NORMAL_TIMEOUT_MS)

    private const val NORMAL_TIMEOUT_MS = 10L * 60L * 1_000L
    private const val RETRY_AFTER_TIMEOUT_MS = 30L * 60L * 1_000L

    @Synchronized
    private fun updateMetadata(
        metadataFile: File,
        status: String,
        transcript: String? = null,
        error: String? = null,
        segments: List<TimedSegment>? = null,
        timeoutMs: Long? = null
    ) {
        try {
            val json = if (metadataFile.exists()) {
                JSONObject(metadataFile.readText())
            } else {
                JSONObject()
            }
            json.put("transcriptionStatus", status)
            if (timeoutMs != null) json.put("transcriptionTimeoutMs", timeoutMs)
            if (transcript != null) json.put("transcript", transcript)
            if (segments != null) {
                val array = JSONArray()
                segments.forEach { segment ->
                    array.put(
                        JSONObject()
                            .put("startMs", segment.startMs)
                            .put("endMs", segment.endMs)
                            .put("text", segment.text)
                    )
                }
                json.put("transcriptSegments", array)
            }
            if (error != null) {
                json.put("transcriptionError", error)
            } else {
                json.remove("transcriptionError")
            }
            metadataFile.writeText(json.toString(2))
        } catch (_: Exception) {
            // Audio remains available even if transcript metadata cannot update.
        }
    }
}
