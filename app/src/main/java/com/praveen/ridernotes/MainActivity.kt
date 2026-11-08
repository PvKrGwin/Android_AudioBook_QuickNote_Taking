package com.praveen.ridernotes

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.praveen.ridernotes.input.BleButtonAccessibilityService
import com.praveen.ridernotes.cloud.CloudSyncManager
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore
import com.praveen.ridernotes.input.RemoteButtonConfig
import com.praveen.ridernotes.input.GestureActionConfig
import com.praveen.ridernotes.input.RemotePressPatternDetector
import com.praveen.ridernotes.media.MediaControlGateway
import com.praveen.ridernotes.media.SharedMediaLinkStore
import com.praveen.ridernotes.media.YouTubeSourceLink
import com.praveen.ridernotes.recording.SavedVoiceNote
import com.praveen.ridernotes.recording.SavedVoiceNoteRepository
import com.praveen.ridernotes.recording.TranscriptSegment
import com.praveen.ridernotes.transcription.ScribTranscriber
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    private val detectorEnabled = mutableStateOf(false)
    private val microphoneEnabled = mutableStateOf(false)
    private val scribStatus = mutableStateOf("")
    private val transcriptionLanguage =
        mutableStateOf(ScribTranscriber.LANGUAGE_ENGLISH)
    private val backupStatus = mutableStateOf("")
    private val cloudSyncStatus = mutableStateOf(
        CloudSyncManager.Status(null, CloudSyncManager.Mode.OFF, "")
    )
    private val sharedLinkStatus = mutableStateOf("")
    private val remoteSetupStatus = mutableStateOf("")
    private val gestureActions = mutableStateOf<Map<RemotePressPatternDetector.Gesture, GestureActionConfig.Action>>(emptyMap())
    private var remoteTraining = false
    private var remoteTrainingDevice: String? = null
    private val remoteTrainingSignals = linkedSetOf<String>()
    private var remoteTrainingPresses = 0
    private val remoteSetupHandler = Handler(Looper.getMainLooper())
    private val remoteTrainingTimeout = Runnable {
        if (remoteTraining) {
            remoteTraining = false
            RemoteButtonConfig.setTraining(this, false)
            remoteSetupStatus.value =
                "Training timed out after 15 seconds; previous remote kept"
        }
    }
    private var pendingExportNotes: List<SavedVoiceNote> = emptyList()

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destination ->
        if (destination != null) exportPendingNotes(destination)
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { source ->
        if (source != null) importBackup(source)
    }
    private val cloudFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { destination ->
        if (destination != null) {
            val previous = CloudSyncManager.destinationUri(this)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                contentResolver.takePersistableUriPermission(destination, flags)
                CloudSyncManager.setDestination(this, destination)
                if (previous != null && previous != destination) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(previous, flags)
                    }
                }
                cloudSyncStatus.value = CloudSyncManager.status(this)
            }.onFailure { error ->
                backupStatus.value = "Cloud folder failed: ${error.javaClass.simpleName}"
            }
        }
    }
    private val savedNotes = mutableStateOf<List<SavedVoiceNote>>(emptyList())
    private val playingFileName = mutableStateOf<String?>(null)
    private val playingPositionMs = mutableStateOf(0L)
    private val playingDurationMs = mutableStateOf(0L)
    private val playbackPaused = mutableStateOf(false)
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var notePlayer: MediaPlayer? = null
    private val playbackProgress = object : Runnable {
        override fun run() {
            val player = notePlayer ?: return
            playingPositionMs.value = runCatching { player.currentPosition.toLong() }
                .getOrDefault(playingPositionMs.value)
            playingDurationMs.value = runCatching { player.duration.toLong() }
                .getOrDefault(playingDurationMs.value)
            playbackHandler.postDelayed(this, PLAYBACK_PROGRESS_STEP_MS)
        }
    }
    private lateinit var diagnosticMediaSession: MediaSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDiagnosticMediaSession()

        setContent {
            RiderNotesTheme {
                DetectorScreen(
                    detectorEnabled = detectorEnabled,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    microphoneEnabled = microphoneEnabled,
                    onRequestMicrophone = ::requestRecordingPermissions,
                    scribStatus = scribStatus,
                    onEnableScrib = ::enableScrib,
                    transcriptionLanguage = transcriptionLanguage,
                    onSelectTranscriptionLanguage = ::selectTranscriptionLanguage,
                    savedNotes = savedNotes,
                    playingFileName = playingFileName,
                    playingPositionMs = playingPositionMs,
                    playingDurationMs = playingDurationMs,
                    playbackPaused = playbackPaused,
                    onRefreshNotes = ::refreshSavedNotes,
                    onPlayNote = ::playOrPauseNote,
                    onStopNote = ::stopNotePlayback,
                    onSeekNote = ::seekNotePlayback,
                    onDeleteNotes = ::deleteNotes,
                    backupStatus = backupStatus,
                    sharedLinkStatus = sharedLinkStatus,
                    onExportNotes = ::requestExport,
                    onImportBackup = ::requestImport,
                    onClear = ButtonEventStore::clear,
                    onCopy = ::copyResults,
                    remoteSetupStatus = remoteSetupStatus,
                    onStartRemoteSetup = ::startRemoteSetup,
                    onResetRemote = ::resetRemoteToBeauty,
                    gestureActions = gestureActions,
                    onSetGestureAction = ::setGestureAction,
                    onResetGestureActions = ::resetGestureActions,
                    onStopTranscription = ::stopTranscription,
                    onRetryTranscription = ::retryTranscription,
                    onCopyTitle = ::copyNoteTitle,
                    onOpenSource = ::openSourceAtTimestamp,
                    onSearchSource = ::searchSourceTitle,
                    onSetManualSourceLink = ::setManualSourceLink,
                    cloudSyncStatus = cloudSyncStatus,
                    onChooseCloudFolder = ::chooseCloudFolder,
                    onRemoveCloudFolder = ::removeCloudFolder,
                    onSetCloudSyncMode = ::setCloudSyncMode,
                    onSyncNow = ::syncCloudNow
                )
            }
        }
        handleSharedMediaIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedMediaIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        detectorEnabled.value = isDetectorEnabled()
        microphoneEnabled.value = hasMicrophonePermission()
        scribStatus.value = ScribTranscriber.status(this)
        transcriptionLanguage.value = ScribTranscriber.languageMode(this)
        MediaControlGateway.observeActivePlayback(this)
        refreshSharedLinkStatus()
        refreshGestureActions()
        refreshSavedNotes()
        cloudSyncStatus.value = CloudSyncManager.status(this)
        savedNotes.value
            .filter { it.transcriptionStatus in setOf("PENDING", "QUEUED", "TRANSCRIBING") }
            .forEach { ScribTranscriber.enqueue(this, it.audioFile) }
        diagnosticMediaSession.isActive = true
    }

    override fun onPause() {
        diagnosticMediaSession.isActive = false
        super.onPause()
    }

    override fun onDestroy() {
        stopNotePlayback()
        remoteSetupHandler.removeCallbacks(remoteTrainingTimeout)
        if (remoteTraining) RemoteButtonConfig.setTraining(this, false)
        diagnosticMediaSession.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        ButtonEventStore.record(ButtonEvent.from(event, route = "Foreground activity"))
        if (remoteTraining && event.device?.isExternal == true &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            val name = event.device?.name ?: "External Bluetooth remote"
            if (remoteTrainingDevice == null) remoteTrainingDevice = name
            if (remoteTrainingDevice == name) {
                remoteTrainingSignals +=
                    RemoteButtonConfig.signature(event.keyCode, event.scanCode)
                remoteTrainingPresses += 1
                remoteSetupStatus.value =
                    "Detected $name: $remoteTrainingPresses/4 presses"
                if (remoteTrainingPresses >= 4) {
                    RemoteButtonConfig.save(this, name, remoteTrainingSignals)
                    RemoteButtonConfig.setTraining(this, false)
                    remoteSetupHandler.removeCallbacks(remoteTrainingTimeout)
                    remoteTraining = false
                    remoteSetupStatus.value =
                        "Configured $name with ${remoteTrainingSignals.size} signal(s)"
                }
            }
            return true
        }

        // Diagnostic safety: an external remote must not activate Clear or Copy
        // while we are trying to identify it. Phone buttons keep normal behavior.
        return if (event.device?.isExternal == true) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true) {
            ButtonEventStore.record(
                ButtonEvent.from(event, route = "Foreground generic motion")
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true) {
            ButtonEventStore.record(
                ButtonEvent.from(event, route = "Foreground external touch")
            )
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setupDiagnosticMediaSession() {
        diagnosticMediaSession = MediaSession(this, "RiderNotesSignalDetector").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonIntent
                        .getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

                    if (event != null) {
                        ButtonEventStore.record(
                            ButtonEvent.from(event, route = "Diagnostic media session")
                        )
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_REWIND or
                            PlaybackState.ACTION_FAST_FORWARD or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT
                    )
                    .setState(
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun isDetectorEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, BleButtonAccessibilityService::class.java)

        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val actual = ComponentName(
                    info.resolveInfo.serviceInfo.packageName,
                    info.resolveInfo.serviceInfo.name
                )
                actual == expected
            }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRecordingPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            RECORDING_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORDING_PERMISSION_REQUEST) {
            microphoneEnabled.value = hasMicrophonePermission()
        }
    }

    private fun refreshSavedNotes() {
        SavedVoiceNoteRepository.synchronizeYouTubeLinks(this)
        savedNotes.value = SavedVoiceNoteRepository.load(this)
    }

    private fun deleteNotes(notes: List<SavedVoiceNote>) {
        if (notes.any { it.audioFile.name == playingFileName.value }) {
            stopNotePlayback()
        }
        notes.forEach { SavedVoiceNoteRepository.delete(it) }
        refreshSavedNotes()
    }

    private fun chooseCloudFolder() {
        cloudFolderLauncher.launch(CloudSyncManager.destinationUri(this))
    }

    private fun removeCloudFolder() {
        val uri = CloudSyncManager.destinationUri(this)
        CloudSyncManager.removeDestination(this)
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        cloudSyncStatus.value = CloudSyncManager.status(this)
        backupStatus.value = "Cloud folder removed; local notes were kept"
    }

    private fun setCloudSyncMode(mode: CloudSyncManager.Mode) {
        CloudSyncManager.setMode(this, mode)
        cloudSyncStatus.value = CloudSyncManager.status(this)
    }

    private fun syncCloudNow() {
        CloudSyncManager.enqueueNow(this)
        cloudSyncStatus.value = CloudSyncManager.status(this)
    }

    private fun stopTranscription(note: SavedVoiceNote) {
        backupStatus.value = ScribTranscriber.stop(this, note.audioFile)
        refreshSavedNotes()
    }

    private fun retryTranscription(note: SavedVoiceNote) {
        backupStatus.value = ScribTranscriber.retry(this, note.audioFile)
        refreshSavedNotes()
    }

    private fun refreshGestureActions() {
        gestureActions.value = RemotePressPatternDetector.Gesture.values().associateWith {
            GestureActionConfig.actionFor(this, it)
        }
    }

    private fun setGestureAction(
        gesture: RemotePressPatternDetector.Gesture,
        action: GestureActionConfig.Action
    ) {
        GestureActionConfig.setAction(this, gesture, action)
        refreshGestureActions()
    }

    private fun resetGestureActions() {
        GestureActionConfig.reset(this)
        refreshGestureActions()
        remoteSetupStatus.value = "Gesture actions reset to Beauty-R1 defaults"
    }

    private fun playOrPauseNote(note: SavedVoiceNote) {
        if (playingFileName.value == note.audioFile.name) {
            val player = notePlayer ?: return
            if (player.isPlaying) {
                player.pause()
                playbackPaused.value = true
                playbackHandler.removeCallbacks(playbackProgress)
            } else {
                player.start()
                playbackPaused.value = false
                playbackHandler.post(playbackProgress)
            }
            return
        }

        stopNotePlayback()
        try {
            notePlayer = MediaPlayer().apply {
                setDataSource(note.audioFile.absolutePath)
                setOnCompletionListener { stopNotePlayback() }
                setOnErrorListener { _, _, _ ->
                    stopNotePlayback()
                    true
                }
                prepare()
                playingDurationMs.value = duration.toLong()
                playingPositionMs.value = 0L
                start()
            }
            playingFileName.value = note.audioFile.name
            playbackPaused.value = false
            playbackHandler.removeCallbacks(playbackProgress)
            playbackHandler.post(playbackProgress)
        } catch (_: Exception) {
            stopNotePlayback()
        }
    }

    private fun seekNotePlayback(positionMs: Long) {
        val player = notePlayer ?: return
        val bounded = positionMs.coerceIn(0L, playingDurationMs.value)
        runCatching { player.seekTo(bounded.toInt()) }
        playingPositionMs.value = bounded
    }

    private fun stopNotePlayback() {
        playbackHandler.removeCallbacks(playbackProgress)
        notePlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        notePlayer = null
        playingFileName.value = null
        playingPositionMs.value = 0L
        playingDurationMs.value = 0L
        playbackPaused.value = false
    }

    private fun requestExport(notes: List<SavedVoiceNote>) {
        if (notes.isEmpty()) return
        pendingExportNotes = notes
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        exportBackupLauncher.launch("RiderNotes_Backup_$timestamp.zip")
    }

    private fun exportPendingNotes(destination: android.net.Uri) {
        try {
            contentResolver.openOutputStream(destination)?.use { stream ->
                ZipOutputStream(stream).use { zip ->
                    val added = mutableSetOf<String>()
                    pendingExportNotes.forEach { note ->
                        val parent = note.audioFile.parentFile ?: return@forEach
                        val base = note.audioFile.nameWithoutExtension
                        listOf("m4a", "json", "txt", "vtt", "srt").forEach { extension ->
                            val file = File(parent, "$base.$extension")
                            if (extension == "txt" && added.add("$base.txt")) {
                                zip.putNextEntry(ZipEntry("$base.txt"))
                                zip.write(exportText(note).toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            } else if (file.exists() && added.add(file.name)) {
                                zip.putNextEntry(ZipEntry(file.name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            } ?: error("Google Drive destination could not be opened")
            backupStatus.value =
                "Exported ${pendingExportNotes.size} note(s). Google Drive will sync the backup."
        } catch (error: Exception) {
            backupStatus.value = "Export failed: ${error.javaClass.simpleName}"
        } finally {
            pendingExportNotes = emptyList()
        }
    }

    private fun requestImport() {
        importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    private fun importBackup(source: android.net.Uri) {
        var importedFiles = 0
        var importedNotes = 0
        try {
            val folder = File(filesDir, "voice_notes").apply { mkdirs() }
            val allowedExtensions = setOf("m4a", "json", "txt", "vtt", "srt")
            val entries = linkedMapOf<String, ByteArray>()
            contentResolver.openInputStream(source)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val safeName = entry.name.substringAfterLast('/')
                        val extension = safeName.substringAfterLast('.', "").lowercase()
                        if (!entry.isDirectory &&
                            safeName.isNotBlank() &&
                            extension in allowedExtensions
                        ) {
                            val output = ByteArrayOutputStream()
                            zip.copyTo(output)
                            entries[safeName] = output.toByteArray()
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Backup could not be opened")
            entries.keys.map { it.substringBeforeLast('.') }.distinct().forEach { base ->
                if (entries["$base.m4a"] == null) return@forEach
                var restoredBase = base
                var copy = 2
                while (File(folder, "$restoredBase.m4a").exists()) {
                    restoredBase = "${base}_imported_$copy"
                    copy += 1
                }
                allowedExtensions.forEach { extension ->
                    entries["$base.$extension"]?.let { bytes ->
                        File(folder, "$restoredBase.$extension").writeBytes(bytes)
                        importedFiles += 1
                    }
                }
                importedNotes += 1
            }
            refreshSavedNotes()
            CloudSyncManager.enqueueNow(this)
            backupStatus.value =
                "Backup restored: $importedNotes note(s), $importedFiles file(s). " +
                    "Expand its source heading to view it."
        } catch (error: Exception) {
            backupStatus.value = "Import failed: ${error.javaClass.simpleName}"
        }
    }

    private fun exportText(note: SavedVoiceNote): String = buildString {
        appendLine("RIDER NOTES RECORDING")
        appendLine("Source: ${note.sourceApp}")
        appendLine("Title: ${note.title}")
        note.module?.let { appendLine("Module: $it") }
        note.author?.let { appendLine("${creatorLabel(note.sourceApp)}: $it") }
        note.chapter?.let { appendLine("Chapter / episode: $it") }
        note.playbackPositionLabel?.let { appendLine("Media position: $it") }
        note.sourceUrlAtPlaybackPosition?.let { appendLine("Open source at this time: $it") }
        if (note.sourceUrlAtPlaybackPosition == null) {
            note.sourceSearchUrl?.let { appendLine("Search source by title: $it") }
        }
        appendLine("Recorded: ${note.recordedAtLabel}")
        appendLine("Audio file: ${note.audioFile.name}")
        appendLine("Transcription status: ${note.transcriptionStatus}")
        appendLine()
        appendLine("TRANSCRIPT")
        appendLine(note.transcript ?: "Transcript not available.")
    }

    private fun openSourceAtTimestamp(note: SavedVoiceNote) {
        val url = note.sourceUrlAtPlaybackPosition ?: return
        openMediaUrl(note, url)
    }

    private fun searchSourceTitle(note: SavedVoiceNote) {
        val url = note.sourceSearchUrl ?: return
        openMediaUrl(note, url)
    }

    private fun openMediaUrl(note: SavedVoiceNote, url: String) {
        val preferredPackage = when (note.sourceApp) {
            "YouTube" -> "com.google.android.youtube"
            "YouTube Music" -> "com.google.android.apps.youtube.music"
            else -> null
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            if (preferredPackage != null) intent.setPackage(preferredPackage)
            startActivity(intent)
        }.recoverCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { error ->
            backupStatus.value = "Could not open source: ${error.javaClass.simpleName}"
        }
    }

    private fun copyNoteTitle(note: SavedVoiceNote) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rider Notes title", note.title))
        backupStatus.value = "Title copied: ${note.title}"
    }

    private fun setManualSourceLink(note: SavedVoiceNote, enteredUrl: String): Boolean {
        val canonicalUrl = YouTubeSourceLink.canonicalUrl(note.sourceApp, enteredUrl)
        if (canonicalUrl == null) {
            backupStatus.value = "Please enter a valid ${note.sourceApp} video link."
            return false
        }
        val updated = SavedVoiceNoteRepository.applySourceUrlToMatchingNotes(
            context = this,
            sourceApp = note.sourceApp,
            title = note.title,
            author = note.author,
            sourceUrl = canonicalUrl
        )
        val savedUrl = SavedVoiceNoteRepository.findReusableSourceUrl(
            context = this,
            sourceApp = note.sourceApp,
            title = note.title,
            author = note.author
        )
        if (savedUrl != canonicalUrl) {
            backupStatus.value = "The link could not be saved to this title group."
            return false
        }
        refreshSavedNotes()
        CloudSyncManager.enqueueNow(this)
        backupStatus.value = if (updated > 0) {
            "Link saved to $updated ${note.sourceApp} recording(s) with the same title and channel."
        } else {
            "This link is already saved for the title and channel."
        }
        return true
    }

    private fun handleSharedMediaIntent(sharedIntent: Intent?) {
        if (sharedIntent?.action != Intent.ACTION_SEND || sharedIntent.type != "text/plain") return
        val sharedText = sharedIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (sharedText == null) {
            sharedLinkStatus.value = "No YouTube link was included in the shared item."
            return
        }

        val identity = MediaControlGateway.currentYouTubeIdentity(this)
        val sharedSource = YouTubeSourceLink.fromSharedText(
            sharedText,
            identity?.packageName
        )
        if (sharedSource == null) {
            sharedLinkStatus.value =
                "The shared text did not contain a supported YouTube link."
            return
        }

        val currentlyPlayingIdentity = identity?.takeIf {
            it.packageName == sharedSource.packageName && it.isPlaying
        }
        val sharedTitle = sharedIntent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: identity
                ?.takeIf { it.packageName == sharedSource.packageName }
                ?.title
        SharedMediaLinkStore.stage(
            context = this,
            packageName = sharedSource.packageName,
            sourceUrl = sharedSource.sourceUrl,
            currentlyPlayingTitle = currentlyPlayingIdentity?.title,
            currentlyPlayingAuthor = currentlyPlayingIdentity?.author,
            sharedTitle = sharedTitle
        )
        refreshSharedLinkStatus()
    }

    private fun refreshSharedLinkStatus() {
        sharedLinkStatus.value = SharedMediaLinkStore.currentLinks(this)
            .joinToString("\n\n") { link ->
                val source = if (
                    link.packageName == YouTubeSourceLink.YOUTUBE_MUSIC_PACKAGE
                ) {
                    "YouTube Music"
                } else {
                    "YouTube"
                }
                val state = if (link.isBoundToCurrentItem) "Current" else "Queued"
                val title = link.title ?: "Waiting for playback title"
                "$state $source link\nTitle: $title\nLink: ${link.sourceUrl}"
            }
    }

    private fun enableScrib() {
        scribStatus.value = ScribTranscriber.enableScrib(this)
    }

    private fun selectTranscriptionLanguage(mode: String) {
        ScribTranscriber.setLanguageMode(this, mode)
        transcriptionLanguage.value = mode
    }

    private fun startRemoteSetup() {
        remoteTraining = true
        RemoteButtonConfig.setTraining(this, true)
        remoteTrainingDevice = null
        remoteTrainingSignals.clear()
        remoteTrainingPresses = 0
        remoteSetupStatus.value =
            "Press your chosen Bluetooth button four times within 15 seconds"
        remoteSetupHandler.removeCallbacks(remoteTrainingTimeout)
        remoteSetupHandler.postDelayed(remoteTrainingTimeout, REMOTE_TRAINING_TIMEOUT_MS)
    }

    private fun resetRemoteToBeauty() {
        remoteSetupHandler.removeCallbacks(remoteTrainingTimeout)
        remoteTraining = false
        RemoteButtonConfig.resetToBeauty(this)
        remoteSetupStatus.value = "Reset complete: Beauty-R1 is the active remote"
    }

    private fun copyResults() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = ButtonEventStore.asShareableText()
            .ifBlank { "No Beauty-R1 signals captured yet." }
        clipboard.setPrimaryClip(ClipData.newPlainText("Beauty-R1 signal test", text))
    }

    companion object {
        private const val RECORDING_PERMISSION_REQUEST = 4102
        private const val REMOTE_TRAINING_TIMEOUT_MS = 15_000L
        private const val PLAYBACK_PROGRESS_STEP_MS = 250L
    }
}

@Composable
private fun DetectorScreen(
    detectorEnabled: State<Boolean>,
    onOpenAccessibilitySettings: () -> Unit,
    microphoneEnabled: State<Boolean>,
    onRequestMicrophone: () -> Unit,
    scribStatus: State<String>,
    onEnableScrib: () -> Unit,
    transcriptionLanguage: State<String>,
    onSelectTranscriptionLanguage: (String) -> Unit,
    savedNotes: State<List<SavedVoiceNote>>,
    playingFileName: State<String?>,
    playingPositionMs: State<Long>,
    playingDurationMs: State<Long>,
    playbackPaused: State<Boolean>,
    onRefreshNotes: () -> Unit,
    onPlayNote: (SavedVoiceNote) -> Unit,
    onStopNote: () -> Unit,
    onSeekNote: (Long) -> Unit,
    onDeleteNotes: (List<SavedVoiceNote>) -> Unit,
    backupStatus: State<String>,
    sharedLinkStatus: State<String>,
    onExportNotes: (List<SavedVoiceNote>) -> Unit,
    onImportBackup: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    remoteSetupStatus: State<String>,
    onStartRemoteSetup: () -> Unit,
    onResetRemote: () -> Unit,
    gestureActions: State<Map<RemotePressPatternDetector.Gesture, GestureActionConfig.Action>>,
    onSetGestureAction: (RemotePressPatternDetector.Gesture, GestureActionConfig.Action) -> Unit,
    onResetGestureActions: () -> Unit,
    onStopTranscription: (SavedVoiceNote) -> Unit,
    onRetryTranscription: (SavedVoiceNote) -> Unit,
    onCopyTitle: (SavedVoiceNote) -> Unit,
    onOpenSource: (SavedVoiceNote) -> Unit,
    onSearchSource: (SavedVoiceNote) -> Unit,
    onSetManualSourceLink: (SavedVoiceNote, String) -> Boolean,
    cloudSyncStatus: State<CloudSyncManager.Status>,
    onChooseCloudFolder: () -> Unit,
    onRemoveCloudFolder: () -> Unit,
    onSetCloudSyncMode: (CloudSyncManager.Mode) -> Unit,
    onSyncNow: () -> Unit
) {
    val events = ButtonEventStore.events.collectAsState()
    val selectedTab = remember { mutableStateOf(0) }
    val expandedSources = remember { mutableStateOf<Set<String>>(emptySet()) }
    val expandedTitles = remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedNotePaths = remember { mutableStateOf<Set<String>>(emptySet()) }
    val showDeleteConfirmation = remember { mutableStateOf(false) }
    val showLinkGuide = remember { mutableStateOf(false) }
    val showSharedLinkDetails = remember { mutableStateOf(false) }
    val manualLinkTarget = remember { mutableStateOf<SavedVoiceNote?>(null) }
    val manualLinkText = remember { mutableStateOf("") }
    val selectedNotes = savedNotes.value.filter {
        it.audioFile.absolutePath in selectedNotePaths.value
    }
    val noteCard: @Composable (SavedVoiceNote) -> Unit = { note ->
        SavedNoteCard(
            note = note,
            isPlaying = playingFileName.value == note.audioFile.name,
            isSelected = note.audioFile.absolutePath in selectedNotePaths.value,
            onSelectionChange = { selected ->
                selectedNotePaths.value = if (selected) {
                    selectedNotePaths.value + note.audioFile.absolutePath
                } else {
                    selectedNotePaths.value - note.audioFile.absolutePath
                }
            },
            playbackPositionMs = if (playingFileName.value == note.audioFile.name) {
                playingPositionMs.value
            } else 0L,
            playbackDurationMs = if (playingFileName.value == note.audioFile.name) {
                playingDurationMs.value
            } else 0L,
            isPaused = playingFileName.value == note.audioFile.name && playbackPaused.value,
            onPlay = { onPlayNote(note) },
            onStop = onStopNote,
            onSeek = onSeekNote,
            onStopTranscription = { onStopTranscription(note) },
            onRetryTranscription = { onRetryTranscription(note) },
            onCopyTitle = { onCopyTitle(note) },
            onOpenSource = { onOpenSource(note) },
            onSearchSource = { onSearchSource(note) }
        )
    }

    if (showDeleteConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation.value = false },
            title = { Text("Delete selected notes?") },
            text = {
                Text(
                    "This permanently deletes ${selectedNotes.size} recording(s), " +
                        "including their transcripts and metadata."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteNotes(selectedNotes)
                        selectedNotePaths.value = emptySet()
                        showDeleteConfirmation.value = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLinkGuide.value) {
        AlertDialog(
            onDismissRequest = { showLinkGuide.value = false },
            title = { Text("📝 YouTube link guide") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Share a YouTube or YT Music item to Rider Notes before playing it. Rider Notes temporarily keeps that exact link.")
                    Text("• Sharing another item from the same service replaces its previous temporary link.")
                    Text("• Playing another item in that same service without sharing clears its temporary link.")
                    Text("• YouTube and YT Music have separate temporary slots, so both links can be kept at the same time.")
                    Text("• Audible, Play Books and other media do not clear either temporary link.")
                    Text("• Saved links are reused for recordings with the same YouTube/YT Music title and channel; every recording keeps its own timestamp.")
                    Text("• A missing link can be added manually from its title heading in Notes.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showLinkGuide.value = false }) {
                    Text("Got it")
                }
            }
        )
    }

    if (showSharedLinkDetails.value && sharedLinkStatus.value.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showSharedLinkDetails.value = false },
            title = { Text("Temporary YouTube links") },
            text = { Text(sharedLinkStatus.value) },
            confirmButton = {
                TextButton(onClick = { showSharedLinkDetails.value = false }) {
                    Text("Close")
                }
            }
        )
    }

    manualLinkTarget.value?.let { note ->
        AlertDialog(
            onDismissRequest = {
                manualLinkTarget.value = null
                manualLinkText.value = ""
            },
            title = { Text("🔗 Add link for this title") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(note.title, fontWeight = FontWeight.Bold)
                    note.author?.let { Text("Channel: $it") }
                    Text(
                        "The base link will be applied to all matching recordings. " +
                            "Each recording keeps its own playback timestamp."
                    )
                    OutlinedTextField(
                        value = manualLinkText.value,
                        onValueChange = { manualLinkText.value = it },
                        label = { Text("YouTube or YT Music link") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onSetManualSourceLink(note, manualLinkText.value)) {
                            manualLinkTarget.value = null
                            manualLinkText.value = ""
                        }
                    }
                ) {
                    Text("Save link")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        manualLinkTarget.value = null
                        manualLinkText.value = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Rider Notes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Bluetooth media controls and voice notes",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (selectedTab.value == 0) {
                    Button(onClick = { selectedTab.value = 0 }) { Text("Home") }
                } else {
                    OutlinedButton(onClick = { selectedTab.value = 0 }) { Text("Home") }
                }
                if (selectedTab.value == 1) {
                    Button(onClick = { selectedTab.value = 1 }) {
                        Text("Notes (${savedNotes.value.size})")
                    }
                } else {
                    OutlinedButton(onClick = { selectedTab.value = 1 }) {
                        Text("Notes (${savedNotes.value.size})")
                    }
                }
                if (selectedTab.value == 2) {
                    Button(onClick = { selectedTab.value = 2 }) { Text("Log") }
                } else {
                    OutlinedButton(onClick = { selectedTab.value = 2 }) { Text("Log") }
                }
            }
            Spacer(Modifier.height(12.dp))

            when (selectedTab.value) {
                0 -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Image(
                                painter = painterResource(R.drawable.rider_notes_logo),
                                contentDescription = "Rider Notes logo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("How Rider Notes works", fontWeight = FontWeight.Bold)
                                    Text("1. Pair your Bluetooth remote and headset in Android settings.")
                                    Text("2. Enable the Rider Notes accessibility detector and microphone.")
                                    Text("3. Single press: play/pause; double: voice note.")
                                    Text("4. Triple press: rewind 10 seconds; four presses: forward 10 seconds.")
                                    Text("5. During the final recording warning, single stops and double extends by 40 seconds.")
                                    Text("6. Notes and offline Scrib transcripts appear under the Notes tab.")
                                    Text("7. For an exact YouTube or YT Music link, share the item to Rider Notes before playing it. Temporary-link status is shown in Notes.")
                                    TextButton(onClick = { showLinkGuide.value = true }) {
                                        Text("📝 Link guide: cleared vs replaced")
                                    }
                                }
                            }
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Remote setup", fontWeight = FontWeight.Bold)
                                    Text("After pairing, tap Train and press the chosen button four times within 15 seconds.")
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(onClick = onStartRemoteSetup) {
                                            Text("Train button")
                                        }
                                        OutlinedButton(onClick = onResetRemote) {
                                            Text("Reset to Beauty-R1")
                                        }
                                    }
                                    if (remoteSetupStatus.value.isNotBlank()) {
                                        Text(remoteSetupStatus.value, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        item {
                            StatusCard(
                                enabled = detectorEnabled.value,
                                onOpenSettings = onOpenAccessibilitySettings
                            )
                        }
                        item {
                            MicrophoneCard(
                                enabled = microphoneEnabled.value,
                                onRequestPermission = onRequestMicrophone
                            )
                        }
                        item {
                            TranscriptionLanguageCard(
                                selectedMode = transcriptionLanguage.value,
                                onSelectMode = onSelectTranscriptionLanguage
                            )
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "Free offline transcription with Scrib",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text("1. Install the regular Scrib app from F-Droid.")
                                    Text("2. Open Scrib and download/select a multilingual model.")
                                    Text("3. Return here and tap Enable Scrib.")
                                    Text("4. New recordings are transcribed automatically offline.")
                                    Text(
                                        scribStatus.value,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (!scribStatus.value.startsWith("Scrib enabled")) {
                                        Button(onClick = onEnableScrib) {
                                            Text("Enable Scrib transcription")
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text("Beauty-R1 controls", fontWeight = FontWeight.Bold)
                                    Text("Single press · Play or pause")
                                    Text("Double press · Record voice note")
                                    Text("Triple press · Rewind")
                                    Text("Four presses · Forward")
                                }
                            }
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text("Gesture action settings", fontWeight = FontWeight.Bold)
                                    Text("Tap an action to cycle through the available choices.")
                                    RemotePressPatternDetector.Gesture.values().forEach { gesture ->
                                        val action = gestureActions.value[gesture]
                                            ?: GestureActionConfig.defaultFor(gesture)
                                        OutlinedButton(
                                            onClick = {
                                                val choices = GestureActionConfig.Action.values()
                                                val next = choices[(action.ordinal + 1) % choices.size]
                                                onSetGestureAction(gesture, next)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("${gestureLabel(gesture)}: ${action.label}")
                                        }
                                    }
                                    TextButton(onClick = onResetGestureActions) {
                                        Text("Reset gesture actions")
                                    }
                                    Text("Acknowledgement: 1–4 short beeps match the detected press count.")
                                }
                            }
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        "Google Drive backup",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "In Notes, select recordings and tap Export. " +
                                            "Choose Google Drive in Android's save screen."
                                    )
                                    Text(
                                        "On a new phone signed into the same Google account, " +
                                            "tap Import backup and choose the saved ZIP."
                                    )
                                }
                            }
                        }
                        item {
                            Card {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text("Automatic cloud sync", fontWeight = FontWeight.Bold)
                                    Text(
                                        cloudSyncStatus.value.destinationName?.let {
                                            "Cloud folder: $it"
                                        } ?: "No cloud folder selected"
                                    )
                                    Text(
                                        "Choose a folder from Google Drive, OneDrive, or another " +
                                            "provider shown by Android. The choice remains after restart."
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = onChooseCloudFolder) {
                                            Text(if (cloudSyncStatus.value.destinationName == null) "Choose folder" else "Replace folder")
                                        }
                                        if (cloudSyncStatus.value.destinationName != null) {
                                            OutlinedButton(onClick = onRemoveCloudFolder) {
                                                Text("Remove")
                                            }
                                        }
                                    }
                                    CloudSyncManager.Mode.values().forEach { mode ->
                                        val selected = cloudSyncStatus.value.mode == mode
                                        if (selected) {
                                            Button(
                                                onClick = { onSetCloudSyncMode(mode) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text(mode.label) }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onSetCloudSyncMode(mode) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text(mode.label) }
                                        }
                                    }
                                    Button(
                                        onClick = onSyncNow,
                                        enabled = cloudSyncStatus.value.destinationName != null &&
                                            cloudSyncStatus.value.mode != CloudSyncManager.Mode.OFF
                                    ) {
                                        Text("Sync now")
                                    }
                                    if (cloudSyncStatus.value.message.isNotBlank()) {
                                        Text(
                                            cloudSyncStatus.value.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        "New and locally changed recordings, metadata, and transcripts " +
                                            "are uploaded automatically. Cloud copies are not deleted " +
                                            "when a local note is deleted."
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                text = "Created by Praveen Kumar V",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                1 -> {
                    val notesBySource = savedNotes.value
                        .groupBy { it.sourceApp }
                        .toSortedMap(String.CASE_INSENSITIVE_ORDER)

                    if (sharedLinkStatus.value.isNotBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Temporary media link",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    compactSharedLinkStatus(sharedLinkStatus.value),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                TextButton(onClick = { showSharedLinkDetails.value = true }) {
                                    Text("View full link")
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Text(
                        "Saved notes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = onRefreshNotes) { Text("Refresh") }
                            OutlinedButton(onClick = onImportBackup) {
                                Text("Import backup")
                            }
                        }
                        if (savedNotes.value.isNotEmpty()) {
                            val allSelected = selectedNotes.size == savedNotes.value.size
                            OutlinedButton(
                                onClick = {
                                    selectedNotePaths.value = if (allSelected) {
                                        emptySet()
                                    } else {
                                        savedNotes.value.mapTo(mutableSetOf()) {
                                            it.audioFile.absolutePath
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (allSelected) "Clear selection" else "Select all (${savedNotes.value.size})")
                            }
                        }
                        if (selectedNotes.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(onClick = { onExportNotes(selectedNotes) }) {
                                    Text("Export (${selectedNotes.size})")
                                }
                                Button(onClick = {
                                    showDeleteConfirmation.value = true
                                }) {
                                    Text("Delete (${selectedNotes.size})")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (backupStatus.value.isNotBlank()) {
                        Text(
                            backupStatus.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    if (savedNotes.value.isEmpty()) {
                        Card {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text("No saved notes yet", fontWeight = FontWeight.Bold)
                                Text("Double-press Beauty-R1 to record your first note.")
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            notesBySource.forEach { (source, sourceNotes) ->
                                item(key = "heading-$source") {
                                    val expanded = source in expandedSources.value
                                    OutlinedButton(
                                        onClick = {
                                            expandedSources.value =
                                                if (expanded) {
                                                    expandedSources.value - source
                                                } else {
                                                    expandedSources.value + source
                                                }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (expanded) {
                                                "▼  $source (${sourceNotes.size})"
                                            } else {
                                                "▶  $source (${sourceNotes.size})"
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (source in expandedSources.value) {
                                    if (supportsTitleGrouping(source)) {
                                        val notesByTitle = sourceNotes
                                            .groupBy { titleGroupKey(source, it) }
                                            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                                        notesByTitle.forEach { (groupKey, titleNotes) ->
                                            val title = titleGroupLabel(source, groupKey)
                                            val titleKey = "$source::$groupKey"
                                            item(key = "title-$titleKey") {
                                                val expanded = titleKey in expandedTitles.value
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp)
                                                ) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            expandedTitles.value = if (expanded) {
                                                                expandedTitles.value - titleKey
                                                            } else {
                                                                expandedTitles.value + titleKey
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(
                                                            if (expanded) {
                                                                "▼  $title (${titleNotes.size})"
                                                            } else {
                                                                "▶  $title (${titleNotes.size})"
                                                            },
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                    if (YouTubeSourceLink.isSupportedSourceApp(source)) {
                                                        TextButton(
                                                            onClick = {
                                                                val target = titleNotes.first()
                                                                manualLinkTarget.value = target
                                                                manualLinkText.value = titleNotes
                                                                    .asSequence()
                                                                    .mapNotNull { it.sourceUrl }
                                                                    .firstOrNull()
                                                                    .orEmpty()
                                                            }
                                                        ) {
                                                            Text("🔗 Add or edit link for this title")
                                                        }
                                                    }
                                                }
                                            }
                                            if (titleKey in expandedTitles.value) {
                                                items(
                                                    items = titleNotes.sortedByDescending {
                                                        it.recordedAtEpochMs
                                                    },
                                                    key = { it.audioFile.absolutePath }
                                                ) { note -> noteCard(note) }
                                            }
                                        }
                                    } else {
                                        items(
                                            items = sourceNotes.sortedByDescending {
                                                it.recordedAtEpochMs
                                            },
                                            key = { it.audioFile.absolutePath }
                                        ) { note -> noteCard(note) }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    Text(
                        text = "Captured signals (${events.value.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(onClick = onClear) { Text("Clear") }
                        OutlinedButton(onClick = onCopy) { Text("Copy results") }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (events.value.isEmpty()) {
                        EmptyInstructions()
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = events.value,
                                key = { event ->
                                    "${event.eventTimeMillis}-${event.action}-${event.keyCode}-${event.scanCode}"
                                }
                            ) { event ->
                                EventCard(event)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun supportsTitleGrouping(source: String): Boolean =
    source.equals("Audible", ignoreCase = true) ||
        source.equals("YouTube", ignoreCase = true) ||
        source.equals("YouTube Music", ignoreCase = true) ||
        source.equals("Google Play Books", ignoreCase = true) ||
        source.equals("Coursera", ignoreCase = true) ||
        source.equals("Netflix", ignoreCase = true)

private fun creatorLabel(source: String): String = when {
    YouTubeSourceLink.isSupportedSourceApp(source) -> "Channel"
    source.equals("Coursera", ignoreCase = true) -> "University"
    else -> "Author"
}

private fun titleGroupKey(source: String, note: SavedVoiceNote): String {
    val title = note.title.trim().ifBlank { "Untitled" }
    return if (YouTubeSourceLink.isSupportedSourceApp(source)) {
        "$title\u001F${note.author?.trim().orEmpty()}"
    } else {
        title
    }
}

private fun titleGroupLabel(source: String, key: String): String {
    if (!YouTubeSourceLink.isSupportedSourceApp(source)) return key
    val title = key.substringBefore('\u001F')
    val channel = key.substringAfter('\u001F', "")
    return if (channel.isBlank()) title else "$title · $channel"
}

private fun compactSharedLinkStatus(status: String): String =
    status.lineSequence().joinToString("\n") { line ->
        if (line.startsWith("Link: ")) {
            val url = line.removePrefix("Link: ")
            "Link: " + if (url.length > 30) url.take(27) + "…" else url
        } else {
            line
        }
    }

@Composable
private fun SavedNoteCard(
    note: SavedVoiceNote,
    isPlaying: Boolean,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    isPaused: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onStopTranscription: () -> Unit,
    onRetryTranscription: () -> Unit,
    onCopyTitle: () -> Unit,
    onOpenSource: () -> Unit,
    onSearchSource: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onSelectionChange
                )
                Text(note.title, fontWeight = FontWeight.Bold)
            }
            Text("${note.sourceApp} · ${note.recordedAtLabel}")
            note.module?.let { Text("Module: $it") }
            note.author?.let { Text("${creatorLabel(note.sourceApp)}: $it") }
            note.chapter?.let {
                val label = if (note.sourceApp == "Audible") {
                    "Chapter / episode"
                } else {
                    "Chapter"
                }
                Text("$label: $it")
            }
            note.playbackPositionLabel?.let { Text("Media position: $it") }
            OutlinedButton(onClick = onCopyTitle) {
                Text("Copy title")
            }
            if (note.sourceUrlAtPlaybackPosition != null) {
                OutlinedButton(onClick = onOpenSource) {
                    Text("Open video at this time")
                }
            }
            if (note.sourceSearchUrl != null) {
                OutlinedButton(onClick = onSearchSource) {
                    Text(
                        if (note.sourceApp == "YouTube Music") {
                            "Search title in YouTube Music"
                        } else {
                            "Search title in YouTube"
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay) {
                    Text(
                        when {
                            !isPlaying -> "Play recording"
                            isPaused -> "Resume"
                            else -> "Pause"
                        }
                    )
                }
                if (isPlaying) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
            }
            if (isPlaying && playbackDurationMs > 0L) {
                Slider(
                    value = playbackPositionMs.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..playbackDurationMs.toFloat()
                )
                Text(
                    "${formatPlaybackTime(playbackPositionMs)} / " +
                        formatPlaybackTime(playbackDurationMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Audio: ${note.audioFile.name}",
                style = MaterialTheme.typography.bodySmall
            )
            if (!note.transcript.isNullOrBlank()) {
                Text("Transcript", fontWeight = FontWeight.SemiBold)
                if (isPlaying && playbackDurationMs > 0L) {
                    PlaybackHighlightedTranscript(
                        transcript = note.transcript,
                        segments = note.transcriptSegments,
                        positionMs = playbackPositionMs
                    )
                } else {
                    Text(note.transcript)
                }
            } else {
                Text(
                    "Transcript: ${note.transcriptionStatus.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (note.transcriptionStatus in setOf("QUEUED", "TRANSCRIBING")) {
                        OutlinedButton(onClick = onStopTranscription) {
                            Text("Stop transcription")
                        }
                    }
                    OutlinedButton(onClick = onRetryTranscription) {
                        Text("Retry transcription")
                    }
                }
            }
        }
    }
}

private fun gestureLabel(gesture: RemotePressPatternDetector.Gesture): String = when (gesture) {
    RemotePressPatternDetector.Gesture.SINGLE -> "Single press"
    RemotePressPatternDetector.Gesture.DOUBLE -> "Double press"
    RemotePressPatternDetector.Gesture.TRIPLE -> "Triple press"
    RemotePressPatternDetector.Gesture.QUADRUPLE -> "Four presses"
}

@Composable
private fun PlaybackHighlightedTranscript(
    transcript: String,
    segments: List<TranscriptSegment>,
    positionMs: Long
) {
    if (segments.isEmpty()) {
        // Older transcripts have no timing data; showing them statically is
        // preferable to misleading full-recording proportional highlighting.
        Text(transcript)
        return
    }

    val activeIndex = segments.indexOfLast { positionMs >= it.startMs }
        .coerceAtLeast(0)
        .coerceAtMost(segments.lastIndex)
    val activeSegment = segments[activeIndex]
    val segmentWords = Regex("""\S+""").findAll(activeSegment.text).toList()
    val segmentDuration = (activeSegment.endMs - activeSegment.startMs).coerceAtLeast(1L)
    val segmentProgress =
        ((positionMs - activeSegment.startMs).toDouble() / segmentDuration.toDouble())
            .coerceIn(0.0, 0.999999)
    val activeWord = if (segmentWords.isEmpty()) {
        null
    } else {
        segmentWords[(segmentProgress * segmentWords.size).toInt()].range
    }

    Text(
        buildAnnotatedString {
            segments.forEachIndexed { index, segment ->
                if (index > 0) append(" ")
                val segmentStart = length
                append(segment.text)
                if (index == activeIndex && activeWord != null) {
                    addStyle(
                        SpanStyle(
                            background = Color(0xFFFFE082),
                            color = Color(0xFF1F1F1F),
                            fontWeight = FontWeight.Bold
                        ),
                        start = segmentStart + activeWord.first,
                        end = segmentStart + activeWord.last + 1
                    )
                }
            }
        }
    )
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return String.format(
        Locale.US,
        "%d:%02d",
        totalSeconds / 60L,
        totalSeconds % 60L
    )
}

@Composable
private fun StatusCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    val background = if (enabled) Color(0xFFE7F4EA) else Color(0xFFFFF3E0)
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (enabled) "Global key detector enabled" else "Global key detector not enabled",
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (enabled) {
                    "While this screen is open, Rider Notes also captures and blocks external " +
                        "motion/media signals so the remote cannot press Clear accidentally."
                } else {
                    "Enable ‘Rider Notes button detector’ for background hardware-key observation."
                }
            )
            if (!enabled) {
                Button(onClick = onOpenSettings) {
                    Text("Open Accessibility settings")
                }
            }
        }
    }
}

@Composable
private fun TranscriptionLanguageCard(
    selectedMode: String,
    onSelectMode: (String) -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Transcription language", fontWeight = FontWeight.Bold)
            Text(
                when (selectedMode) {
                    ScribTranscriber.LANGUAGE_AUTO ->
                        "Auto-detect is best when recordings may switch between English and Kannada."
                    ScribTranscriber.LANGUAGE_KANNADA ->
                        "Kannada mode expects Kannada speech and requests Kannada-script transcription."
                    else ->
                        "English mode is best for mainly English speech with occasional Kannada names."
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    ScribTranscriber.LANGUAGE_AUTO,
                    ScribTranscriber.LANGUAGE_ENGLISH,
                    ScribTranscriber.LANGUAGE_KANNADA
                ).forEach { mode ->
                    val label = ScribTranscriber.languageLabel(mode)
                    if (selectedMode == mode) {
                        Button(onClick = { onSelectMode(mode) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onSelectMode(mode) }) { Text(label) }
                    }
                }
            }
            Text(
                "Applies to new recordings only.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MicrophoneCard(enabled: Boolean, onRequestPermission: () -> Unit) {
    val background = if (enabled) Color(0xFFE7F4EA) else Color(0xFFFFF3E0)
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (enabled) "Microphone ready" else "Microphone permission required",
                fontWeight = FontWeight.Bold
            )
            Text(
                if (enabled) {
                    "Double press can record a voice-note test."
                } else {
                    "Allow microphone access before testing voice-note recording."
                }
            )
            if (!enabled) {
                Button(onClick = onRequestPermission) {
                    Text("Enable recording")
                }
            }
        }
    }
}

@Composable
private fun EmptyInstructions() {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("No signals yet", fontWeight = FontWeight.Bold)
            Text("1. Confirm Beauty-R1 is connected.")
            Text("2. Keep this screen open for the expanded diagnostic.")
            Text("3. Press one remote button once.")
            Text("4. Clear, repeat for each physical button, then copy the results.")
        }
    }
}

@Composable
private fun EventCard(event: ButtonEvent) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(event.keyName, fontWeight = FontWeight.Bold)
                Text(event.action)
            }
            if (event.keyCode >= 0) {
                Text("Key ${event.keyCode} · Scan ${event.scanCode} · Repeat ${event.repeatCount}")
            }
            Text("Likely: ${event.likelyMeaning}")
            Text("Device: ${event.deviceName} (${event.deviceId})")
            Text(
                text = "Captured via: ${event.captureRoutes.sorted().joinToString(" + ")}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RiderNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF28536B),
            secondary = Color(0xFF5B7C8D),
            background = Color(0xFFF6F8FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFEAF0F3)
        ),
        content = { Surface(modifier = Modifier.fillMaxSize(), content = content) }
    )
}
