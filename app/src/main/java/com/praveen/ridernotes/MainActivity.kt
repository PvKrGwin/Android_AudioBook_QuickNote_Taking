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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.praveen.ridernotes.input.BleButtonAccessibilityService
import com.praveen.ridernotes.input.ButtonEvent
import com.praveen.ridernotes.input.ButtonEventStore
import com.praveen.ridernotes.input.RemoteButtonConfig
import com.praveen.ridernotes.recording.SavedVoiceNote
import com.praveen.ridernotes.recording.SavedVoiceNoteRepository
import com.praveen.ridernotes.transcription.ScribTranscriber
import java.io.File
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
    private val remoteSetupStatus = mutableStateOf("")
    private var remoteTraining = false
    private var remoteTrainingDevice: String? = null
    private val remoteTrainingSignals = linkedSetOf<String>()
    private var remoteTrainingPresses = 0
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
    private val savedNotes = mutableStateOf<List<SavedVoiceNote>>(emptyList())
    private val playingFileName = mutableStateOf<String?>(null)
    private var notePlayer: MediaPlayer? = null
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
                    onRefreshNotes = ::refreshSavedNotes,
                    onPlayNote = ::playOrStopNote,
                    onDeleteNotes = ::deleteNotes,
                    backupStatus = backupStatus,
                    onExportNotes = ::requestExport,
                    onImportBackup = ::requestImport,
                    onClear = ButtonEventStore::clear,
                    onCopy = ::copyResults,
                    remoteSetupStatus = remoteSetupStatus,
                    onStartRemoteSetup = ::startRemoteSetup
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        detectorEnabled.value = isDetectorEnabled()
        microphoneEnabled.value = hasMicrophonePermission()
        scribStatus.value = ScribTranscriber.status(this)
        transcriptionLanguage.value = ScribTranscriber.languageMode(this)
        refreshSavedNotes()
        diagnosticMediaSession.isActive = true
    }

    override fun onPause() {
        diagnosticMediaSession.isActive = false
        super.onPause()
    }

    override fun onDestroy() {
        stopNotePlayback()
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
        savedNotes.value = SavedVoiceNoteRepository.load(this)
    }

    private fun deleteNotes(notes: List<SavedVoiceNote>) {
        if (notes.any { it.audioFile.name == playingFileName.value }) {
            stopNotePlayback()
        }
        notes.forEach { SavedVoiceNoteRepository.delete(it) }
        refreshSavedNotes()
    }

    private fun playOrStopNote(note: SavedVoiceNote) {
        if (playingFileName.value == note.audioFile.name) {
            stopNotePlayback()
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
                start()
            }
            playingFileName.value = note.audioFile.name
        } catch (_: Exception) {
            stopNotePlayback()
        }
    }

    private fun stopNotePlayback() {
        notePlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        notePlayer = null
        playingFileName.value = null
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
                            if (file.exists() && added.add(file.name)) {
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
        try {
            val folder = File(filesDir, "voice_notes").apply { mkdirs() }
            val allowedExtensions = setOf("m4a", "json", "txt", "vtt", "srt")
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
                            File(folder, safeName).outputStream().use { output ->
                                zip.copyTo(output)
                            }
                            importedFiles += 1
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Backup could not be opened")
            refreshSavedNotes()
            backupStatus.value =
                "Backup restored: $importedFiles file(s) imported."
        } catch (error: Exception) {
            backupStatus.value = "Import failed: ${error.javaClass.simpleName}"
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
        remoteSetupStatus.value = "Press your chosen Bluetooth button four times"
    }

    private fun copyResults() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = ButtonEventStore.asShareableText()
            .ifBlank { "No Beauty-R1 signals captured yet." }
        clipboard.setPrimaryClip(ClipData.newPlainText("Beauty-R1 signal test", text))
    }

    companion object {
        private const val RECORDING_PERMISSION_REQUEST = 4102
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
    onRefreshNotes: () -> Unit,
    onPlayNote: (SavedVoiceNote) -> Unit,
    onDeleteNotes: (List<SavedVoiceNote>) -> Unit,
    backupStatus: State<String>,
    onExportNotes: (List<SavedVoiceNote>) -> Unit,
    onImportBackup: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    remoteSetupStatus: State<String>,
    onStartRemoteSetup: () -> Unit
) {
    val events = ButtonEventStore.events.collectAsState()
    val selectedTab = remember { mutableStateOf(0) }
    val expandedSources = remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedNotePaths = remember { mutableStateOf<Set<String>>(emptySet()) }
    val showDeleteConfirmation = remember { mutableStateOf(false) }
    val selectedNotes = savedNotes.value.filter {
        it.audioFile.absolutePath in selectedNotePaths.value
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
                                    Text("After pairing, tap Train and press the one button you want Rider Notes to use four times.")
                                    Button(onClick = onStartRemoteSetup) { Text("Train Bluetooth button") }
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
                                    items(
                                        items = sourceNotes,
                                        key = { it.audioFile.absolutePath }
                                    ) { note ->
                                        SavedNoteCard(
                                            note = note,
                                            isPlaying =
                                                playingFileName.value == note.audioFile.name,
                                            isSelected =
                                                note.audioFile.absolutePath in selectedNotePaths.value,
                                            onSelectionChange = { selected ->
                                                selectedNotePaths.value =
                                                    if (selected) {
                                                        selectedNotePaths.value +
                                                            note.audioFile.absolutePath
                                                    } else {
                                                        selectedNotePaths.value -
                                                            note.audioFile.absolutePath
                                                    }
                                            },
                                            onPlay = { onPlayNote(note) }
                                        )
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

@Composable
private fun SavedNoteCard(
    note: SavedVoiceNote,
    isPlaying: Boolean,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onPlay: () -> Unit
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
            note.author?.let { Text("Author: $it") }
            note.chapter?.let {
                val label = if (note.sourceApp == "Audible") {
                    "Chapter / episode"
                } else {
                    "Chapter"
                }
                Text("$label: $it")
            }
            note.playbackPositionLabel?.let { Text("Media position: $it") }
            Button(onClick = onPlay) {
                Text(if (isPlaying) "Stop recording playback" else "Play recording")
            }
            Text(
                "Audio: ${note.audioFile.name}",
                style = MaterialTheme.typography.bodySmall
            )
            if (!note.transcript.isNullOrBlank()) {
                Text("Transcript", fontWeight = FontWeight.SemiBold)
                Text(note.transcript)
            } else {
                Text(
                    "Transcript: ${note.transcriptionStatus.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
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
