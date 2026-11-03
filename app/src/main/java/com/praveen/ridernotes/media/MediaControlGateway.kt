package com.praveen.ridernotes.media

import android.content.ComponentName
import android.content.Context
import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Bundle
import android.view.KeyEvent
import android.service.notification.StatusBarNotification
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object MediaControlGateway {
    @Volatile var notificationAccessConnected: Boolean = false

    private const val SEEK_STEP_MS = 10_000L
    private const val LINK_GRACE_MS = 12_000L
    private val lastKnownPlayingAt = ConcurrentHashMap<String, Long>()
    private val observedPlayingPackages = ConcurrentHashMap.newKeySet<String>()

    private val supportedAppNames = mapOf(
        "com.audible.application" to "Audible",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.apps.books" to "Google Play Books"
    )

    private fun isMediaApp(context: Context, pkg: String): Boolean =
        pkg != context.packageName && pkg != "com.android.systemui"
    private val notificationTextByPackage =
        ConcurrentHashMap<String, List<String>>()

    data class Result(val successful: Boolean, val message: String)

    data class VoiceNoteMediaContext(
        val packageName: String?,
        val appName: String?,
        val title: String?,
        val subtitle: String?,
        val author: String?,
        val playbackPositionMs: Long?,
        val sourceUrl: String?,
        val wasPlaying: Boolean
    ) {
        val isStandalone: Boolean get() = packageName == null
    }

    @Volatile
    private var voiceNoteContext: VoiceNoteMediaContext? = null
    @Volatile private var deferredResumeContext: VoiceNoteMediaContext? = null


    fun prepareForVoiceNote(context: Context): VoiceNoteMediaContext {
        val controller = try {
            // A paused or stale session must not turn a spontaneous thought
            // into a media-linked recording.
            findControllers(context)
                .asSequence()
                .filter { isMediaApp(context, it.packageName) }
                .filter { session -> isEligibleForLinkedRecording(session) }
                .maxByOrNull(::priority)
        } catch (_: Exception) {
            // Standalone recording must work without notification access too.
            null
        }

        if (controller == null) {
            return VoiceNoteMediaContext(null, null, null, null, null, null, null, false)
                .also { voiceNoteContext = it }
        }

        val state = controller.playbackState
        val wasPlaying = when (state?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> true
            else -> false
        }
        val metadata = controller.metadata
        val (mediaTitle, mediaChapter, mediaAuthor) = mediaFields(
            controller.packageName,
            metadata,
            notificationTextByPackage[controller.packageName].orEmpty()
        )
        val metadataEntries = mediaLinkEntries(controller, metadata)
        val sourceUrl = YouTubeSourceLink.fromMetadata(
            controller.packageName,
            metadataEntries
        )
        if (
            controller.packageName in setOf(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                YouTubeSourceLink.YOUTUBE_MUSIC_PACKAGE
            ) && sourceUrl == null
        ) {
            val diagnostic = metadataEntries.joinToString(" | ") { (key, value) ->
                "$key=${value.take(120)}"
            }.take(1_000)
            ButtonEventStore.record(
                ButtonEvent.mediaResult(
                    "YouTube link unavailable; media metadata=[$diagnostic]",
                    false
                )
            )
        }
        val noteContext = VoiceNoteMediaContext(
            packageName = controller.packageName,
            appName = appLabel(context, controller.packageName),
            title = mediaTitle,
            subtitle = mediaChapter,
            author = mediaAuthor,
            playbackPositionMs = state?.let(::estimatedPosition),
            sourceUrl = sourceUrl,
            wasPlaying = wasPlaying
        )
        voiceNoteContext = noteContext

        if (wasPlaying) {
            if (supports(controller, PlaybackState.ACTION_PAUSE)) {
                controller.transportControls.pause()
            } else {
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
        }
        return noteContext
    }

    fun currentVoiceNoteContext(): VoiceNoteMediaContext? = voiceNoteContext

    fun ensureVoiceNoteMediaPaused(context: Context): Boolean {
        val savedContext = voiceNoteContext ?: return true
        if (!savedContext.wasPlaying || savedContext.packageName == null) return true

        val controller = try {
            findControllers(context)
                .firstOrNull { it.packageName == savedContext.packageName }
        } catch (_: Exception) {
            null
        } ?: return false

        val stillPlaying = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> true
            else -> false
        }
        if (!stillPlaying) return true

        if (supports(controller, PlaybackState.ACTION_PAUSE)) {
            controller.transportControls.pause()
        } else {
            dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        return false
    }

    fun observeActivePlayback(context: Context) {
        runCatching {
            findControllers(context)
                .filter { isMediaApp(context, it.packageName) }
                .forEach { controller ->
                    val state = controller.playbackState
                    if (state?.state == PlaybackState.STATE_PLAYING) {
                        observedPlayingPackages.add(controller.packageName)
                        lastKnownPlayingAt[controller.packageName] = SystemClock.elapsedRealtime()
                    }
                }
        }
    }

    fun updateNotificationMetadata(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.praveen.ridernotes") return
        val extras = sbn.notification.extras
        val values = buildList {
            listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT
            ).forEach { key ->
                extras.getCharSequence(key)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                .orEmpty()
                .map { it.toString().trim() }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }.distinct()
        if (values.isNotEmpty()) notificationTextByPackage[sbn.packageName] = values
    }

    fun removeNotificationMetadata(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.praveen.ridernotes") {
            notificationTextByPackage.remove(sbn.packageName)
        }
    }

    fun clearNotificationMetadata() {
        notificationTextByPackage.clear()
    }

    fun finishVoiceNote(
        context: Context,
        delayMs: Long = 1_500L,
        deferForCall: Boolean = false
    ) {
        val savedContext = voiceNoteContext
        voiceNoteContext = null
        if (savedContext?.wasPlaying != true || savedContext.packageName == null) return
        if (deferForCall) {
            deferredResumeContext = savedContext
            return
        }

        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val retryDelays = longArrayOf(delayMs, delayMs + 1_500L, delayMs + 3_000L)
        retryDelays.forEachIndexed { index, attemptAt ->
            handler.postDelayed({
                attemptVoiceNoteResume(
                    context = appContext,
                    savedContext = savedContext,
                    attempt = index + 1,
                    finalAttempt = index == retryDelays.lastIndex
                )
            }, attemptAt)
        }
    }

    private fun attemptVoiceNoteResume(
        context: Context,
        savedContext: VoiceNoteMediaContext,
        attempt: Int,
        finalAttempt: Boolean
    ) {
        try {
            val controller = findControllers(context)
                .firstOrNull { it.packageName == savedContext.packageName }
            if (controller == null) {
                ButtonEventStore.record(
                    ButtonEvent.mediaResult(
                        "Media resume attempt $attempt: " +
                            "${savedContext.appName ?: savedContext.packageName} session unavailable",
                        false
                    )
                )
                return
            }

            val alreadyPlaying = when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING -> true
                else -> false
            }
            if (alreadyPlaying) {
                ButtonEventStore.record(
                    ButtonEvent.mediaResult(
                        "Media resumed: ${savedContext.appName ?: savedContext.packageName}",
                        true
                    )
                )
                return
            }

            if (supports(controller, PlaybackState.ACTION_PLAY)) {
                controller.transportControls.play()
            } else {
                // PLAY is idempotent; unlike PLAY_PAUSE it cannot accidentally
                // pause media that recovered between retries.
                dispatchMediaKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            ButtonEventStore.record(
                ButtonEvent.mediaResult(
                    "Media resume command sent to " +
                        "${savedContext.appName ?: savedContext.packageName} " +
                        "(attempt $attempt${if (finalAttempt) ", final" else ""})",
                    true
                )
            )
        } catch (error: Exception) {
            ButtonEventStore.record(
                ButtonEvent.mediaResult(
                    "Media resume attempt $attempt failed: ${error.javaClass.simpleName}",
                    false
                )
            )
        }
    }

    fun resumeDeferredAfterCall(context: Context) {
        val saved = deferredResumeContext ?: return
        deferredResumeContext = null
        voiceNoteContext = saved
        finishVoiceNote(context)
    }

    fun togglePlayPause(context: Context): Result {
        val controller = controllerOrResult(context)
        if (controller.second != null) return controller.second!!
        val mediaController = controller.first!!
        val packageLabel = appLabel(context, mediaController.packageName)

        return try {
            when (mediaController.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING -> {
                    if (supports(mediaController, PlaybackState.ACTION_PAUSE)) {
                        mediaController.transportControls.pause()
                    } else {
                        dispatchMediaKey(mediaController, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    Result(true, "Paused $packageLabel")
                }
                else -> {
                    if (supports(mediaController, PlaybackState.ACTION_PLAY)) {
                        mediaController.transportControls.play()
                    } else {
                        dispatchMediaKey(mediaController, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    Result(true, "Playing $packageLabel")
                }
            }
        } catch (error: RuntimeException) {
            Result(false, "Media control failed: ${error.javaClass.simpleName}")
        }
    }

    fun rewindTenSeconds(context: Context): Result {
        val controller = controllerOrResult(context)
        if (controller.second != null) return controller.second!!
        val mediaController = controller.first!!
        val packageLabel = appLabel(context, mediaController.packageName)
        val state = mediaController.playbackState
            ?: return Result(false, "$packageLabel has no playback position")

        return try {
            when {
                supports(mediaController, PlaybackState.ACTION_SEEK_TO) -> {
                    val currentPosition = estimatedPosition(state)
                    val targetPosition = max(0L, currentPosition - SEEK_STEP_MS)
                    mediaController.transportControls.seekTo(targetPosition)
                    Result(true, "Rewound $packageLabel by 10 seconds")
                }
                supports(mediaController, PlaybackState.ACTION_REWIND) -> {
                    mediaController.transportControls.rewind()
                    Result(true, "Used $packageLabel rewind command")
                }
                else -> Result(false, "$packageLabel does not expose rewind control")
            }
        } catch (error: RuntimeException) {
            Result(false, "Rewind failed: ${error.javaClass.simpleName}")
        }
    }

    fun forwardTenSeconds(context: Context): Result {
        val controller = controllerOrResult(context)
        if (controller.second != null) return controller.second!!
        val mediaController = controller.first!!
        val packageLabel = appLabel(context, mediaController.packageName)
        val state = mediaController.playbackState
            ?: return Result(false, "$packageLabel has no playback position")

        return try {
            when {
                supports(mediaController, PlaybackState.ACTION_SEEK_TO) -> {
                    val targetPosition = estimatedPosition(state) + SEEK_STEP_MS
                    mediaController.transportControls.seekTo(targetPosition)
                    Result(true, "Forwarded $packageLabel by 10 seconds")
                }
                supports(mediaController, PlaybackState.ACTION_FAST_FORWARD) -> {
                    mediaController.transportControls.fastForward()
                    Result(true, "Used $packageLabel forward command")
                }
                else -> Result(false, "$packageLabel does not expose forward control")
            }
        } catch (error: RuntimeException) {
            Result(false, "Forward failed: ${error.javaClass.simpleName}")
        }
    }

    private fun controllerOrResult(context: Context): Pair<MediaController?, Result?> {
        val controller = try {
            findBestSupportedController(context)
        } catch (_: SecurityException) {
            return null to Result(false, "Notification access required for media control")
        }

        if (controller != null) return controller to null
        val message = if (notificationAccessConnected) {
            "No active or paused supported media session"
        } else {
            "Enable Rider Notes notification access"
        }
        return null to Result(false, message)
    }

    private fun findBestSupportedController(context: Context): MediaController? =
        findControllers(context)
            .asSequence()
            .filter { isMediaApp(context, it.packageName) }
            .maxByOrNull(::priority)

    private fun findControllers(context: Context): List<MediaController> {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, RiderNotificationListenerService::class.java)
        return manager.getActiveSessions(listener)
    }

    /**
     * A paused media session is linked only when this process observed it playing
     * within the previous 12 seconds. A stale paused session at app launch is not
     * proof that playback happened during Rider Notes' lifetime.
     */
    private fun isEligibleForLinkedRecording(controller: MediaController): Boolean {
        val pkg = controller.packageName
        val state = controller.playbackState ?: return false
        val now = SystemClock.elapsedRealtime()
        if (state.state == PlaybackState.STATE_PLAYING) {
            lastKnownPlayingAt[pkg] = now
            observedPlayingPackages.add(pkg)
            return true
        }
        if (state.state != PlaybackState.STATE_PAUSED ||
            !observedPlayingPackages.contains(pkg)) return false
        val observedAt = lastKnownPlayingAt[pkg] ?: return false
        val pausedAt = state.lastPositionUpdateTime
            .takeIf { it in observedAt..now } ?: observedAt
        return now - pausedAt in 0L..LINK_GRACE_MS
    }

    private fun priority(controller: MediaController): Int =
        when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 4
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 3
            PlaybackState.STATE_PAUSED -> 2
            else -> 1
        }

    private fun estimatedPosition(state: PlaybackState): Long {
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return max(0L, position)
    }

    private fun supports(controller: MediaController, action: Long): Boolean {
        val actions = controller.playbackState?.actions ?: 0L
        return actions and action != 0L
    }

    private fun dispatchMediaKey(controller: MediaController, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        controller.dispatchMediaButtonEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        controller.dispatchMediaButtonEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }

    private fun metadataTextValues(metadata: MediaMetadata?): List<String> {
        return metadataTextEntries(metadata).map { (_, value) -> value }.distinct()
    }

    private fun metadataTextEntries(metadata: MediaMetadata?): List<Pair<String, String>> {
        if (metadata == null) return emptyList()
        return metadata.keySet()
            .mapNotNull { key ->
                runCatching { metadata.getString(key) }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { value -> key to value }
            }
            .distinctBy { it.first to it.second }
    }

    private fun mediaLinkEntries(
        controller: MediaController,
        metadata: MediaMetadata?
    ): List<Pair<String, String>> {
        val result = metadataTextEntries(metadata).toMutableList()

        fun addDescription(prefix: String, description: android.media.MediaDescription?) {
            description ?: return
            description.mediaId?.trim()?.takeIf { it.isNotBlank() }?.let {
                result += "$prefix.mediaId" to it
            }
            description.mediaUri?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                result += "$prefix.mediaUri" to it
            }
            result += bundleTextEntries(description.extras, "$prefix.extra")
        }

        addDescription("metadata.description", metadata?.description)
        result += bundleTextEntries(controller.extras, "session.extra")
        result += bundleTextEntries(controller.playbackState?.extras, "playback.extra")

        val activeQueueItemId = controller.playbackState?.activeQueueItemId
        controller.queue
            ?.firstOrNull { activeQueueItemId != null && it.queueId == activeQueueItemId }
            ?.description
            ?.let { addDescription("queue.description", it) }

        return result.distinctBy { it.first to it.second }
    }

    @Suppress("DEPRECATION")
    private fun bundleTextEntries(bundle: Bundle?, prefix: String): List<Pair<String, String>> {
        bundle ?: return emptyList()
        return bundle.keySet().mapNotNull { key ->
            val value = runCatching { bundle.get(key) }.getOrNull()
            when (value) {
                is String -> value
                is CharSequence -> value.toString()
                else -> null
            }?.trim()?.takeIf { it.isNotBlank() }?.let { "$prefix.$key" to it }
        }
    }

    private fun mediaFields(
        packageName: String,
        metadata: MediaMetadata?,
        notificationText: List<String>
    ): Triple<String?, String?, String?> {
        fun value(key: String): String? =
            metadata?.getString(key)?.trim()?.takeIf { it.isNotBlank() }

        val title = value(MediaMetadata.METADATA_KEY_TITLE)
        val displayTitle = value(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val displaySubtitle = value(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val displayDescription = value(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        val album = value(MediaMetadata.METADATA_KEY_ALBUM)
        val author = value(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: value(MediaMetadata.METADATA_KEY_ARTIST)

        return when (packageName) {
            "com.google.android.apps.books" -> {
                // Play Books publishes the chapter as TITLE and the book as
                // DISPLAY_SUBTITLE/ALBUM, which is the reverse of our generic labels.
                val bookTitle = displaySubtitle ?: album ?: displayTitle ?: title
                val chapter = title
                    ?.takeUnless { it.equals(bookTitle, ignoreCase = true) }
                Triple(bookTitle, chapter, author)
            }

            "com.audible.application" -> {
                // Audible commonly publishes the author as DISPLAY_SUBTITLE,
                // while chapter/episode text may live in DISPLAY_DESCRIPTION,
                // DISPLAY_TITLE, or only in its playback notification.
                val bookOrEpisodeTitle = title ?: album ?: displayTitle
                    ?: notificationText.firstOrNull()
                val excluded = listOfNotNull(
                    bookOrEpisodeTitle,
                    album,
                    author,
                    displaySubtitle,
                    "Audible"
                )

                fun isUseful(candidate: String?): Boolean {
                    if (candidate.isNullOrBlank() || candidate.length < 2) return false
                    if (excluded.any { it.equals(candidate, ignoreCase = true) }) return false
                    if (candidate.matches(Regex("""\d{1,2}:\d{2}(:\d{2})?"""))) return false
                    if (candidate.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return false
                    if (candidate.startsWith("http", ignoreCase = true)) return false
                    if (candidate.length > 200) return false
                    if (
                        candidate.equals("WIDEVINE_OFFLINE", ignoreCase = true) ||
                        candidate.equals("Product", ignoreCase = true) ||
                        candidate.equals("ContinuousListen", ignoreCase = true) ||
                        candidate.matches(Regex("""B0[A-Z0-9]+"""))
                    ) return false
                    return true
                }

                val candidates = listOfNotNull(displayDescription, displayTitle) +
                    metadataTextValues(metadata).filter(::isUseful) +
                    notificationText.filter(::isUseful)
                val chapterOrEpisode = candidates
                    .filter(::isUseful)
                    .distinctBy { it.lowercase() }
                    .sortedByDescending { candidate ->
                        when {
                            candidate.matches(Regex("""\d+\s*[:.-]\s*.+""")) -> 4
                            candidate.contains("chapter", ignoreCase = true) -> 3
                            candidate.contains("episode", ignoreCase = true) -> 3
                            candidate.contains("part", ignoreCase = true) -> 2
                            else -> 1
                        }
                    }
                    .firstOrNull()

                Triple(bookOrEpisodeTitle, chapterOrEpisode, author)
            }

            else -> {
                val chapter = displaySubtitle ?: album
                Triple(title, chapter, author)
            }
        }
    }

    private fun appLabel(context: Context, packageName: String): String =
        supportedAppNames[packageName] ?: try {
            val application = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(application).toString()
        } catch (_: Exception) {
            packageName
        }
}
