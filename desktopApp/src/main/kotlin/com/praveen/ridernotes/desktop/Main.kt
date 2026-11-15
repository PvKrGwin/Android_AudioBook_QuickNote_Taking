package com.praveen.ridernotes.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    var windowVisible by remember { mutableStateOf(true) }
    var mediaNoteRequest by remember { mutableStateOf(0) }
    var trayAvailable by remember { mutableStateOf(false) }
    val tray = remember {
        DesktopTrayController(
            onOpen = { windowVisible = true },
            onAddMediaNote = {
                windowVisible = true
                mediaNoteRequest += 1
            },
            onExit = ::exitApplication
        )
    }
    DisposableEffect(Unit) {
        trayAvailable = runCatching { tray.install() }.getOrDefault(false)
        onDispose { tray.close() }
    }
    Window(
        onCloseRequest = {
            if (trayAvailable) windowVisible = false else exitApplication()
        },
        visible = windowVisible,
        title = "Rider Notes PC"
    ) {
        MaterialTheme {
            RiderNotesDesktopApp(mediaNoteRequest, tray)
        }
    }
}

@Composable
private fun RiderNotesDesktopApp(
    externalMediaNoteRequest: Int,
    tray: DesktopTrayController
) {
    var snapshot by remember { mutableStateOf<BackupSnapshot?>(null) }
    var baseSnapshot by remember { mutableStateOf<BackupSnapshot?>(null) }
    var status by remember { mutableStateOf("Open RiderNotes_Full_Backup.zip to begin.") }
    var selectedTab by remember { mutableStateOf(0) }
    var showCloudSyncReminder by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }
    var syncSucceeded by remember { mutableStateOf(false) }
    var undoReturnsDirty by remember { mutableStateOf(false) }
    var showSyncPrompt by remember { mutableStateOf(false) }
    var showMediaNoteDialog by remember { mutableStateOf(false) }
    var mediaDraft by remember { mutableStateOf<CapturedMedia?>(null) }
    var mediaNoteText by remember { mutableStateOf("") }
    var mediaTitle by remember { mutableStateOf("") }
    var mediaSource by remember { mutableStateOf("") }
    var mediaAuthor by remember { mutableStateOf("") }
    var mediaModule by remember { mutableStateOf("") }
    var mediaChapter by remember { mutableStateOf("") }
    var mediaPosition by remember { mutableStateOf("") }
    var mediaSourceUrl by remember { mutableStateOf("") }
    var syncObservation by remember { mutableStateOf<DesktopSyncObservation?>(null) }
    var showCloudChangeDialog by remember { mutableStateOf(false) }
    var dismissedCloudRevision by remember { mutableStateOf<String?>(null) }
    var showOfflineEditWarning by remember { mutableStateOf(false) }
    var pendingOfflineSnapshot by remember { mutableStateOf<BackupSnapshot?>(null) }
    var offlineEditAcknowledged by remember { mutableStateOf(false) }
    var startupEnabled by remember { mutableStateOf(WindowsStartupManager.isEnabled()) }
    val audioPlayer = remember { DesktopAudioPlayer() }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer.close() } }

    fun loadBackup(zip: File) {
        runCatching { BackupImporter.import(zip) }
            .onSuccess {
                audioPlayer.stop()
                snapshot = it
                baseSnapshot = runCatching { BackupImporter.import(zip) }.getOrDefault(it)
                selectedTab = 0
                dirty = false
                syncSucceeded = false
                undoReturnsDirty = false
                DesktopUndoStore.clear()
                offlineEditAcknowledged = false
                showCloudChangeDialog = false
                showSyncPrompt = false
                showMediaNoteDialog = false
                syncObservation = DesktopSyncMonitor.inspect(it)
                DesktopDeviceIdentity.rememberBackupPath(zip.absolutePath)
                status = "Loaded ${it.notes.size} notes and ${it.groups.size} topic folders from ${zip.name}"
            }
            .onFailure { error ->
                status = "Import failed: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    fun markChanged(updated: BackupSnapshot) {
        undoReturnsDirty = dirty
        syncSucceeded = false
        // Re-check at the exact moment an edit is committed. The background watcher is useful
        // for passive viewing, but this closes the small gap between two polling cycles.
        val immediateObservation = snapshot?.let(DesktopSyncMonitor::inspect)
        if (immediateObservation != null) syncObservation = immediateObservation
        if (immediateObservation?.health == DesktopSyncHealth.NEW_CLOUD_VERSION) {
            snapshot = updated
            dirty = true
            showCloudChangeDialog = true
            status = "A newer Drive version was found before this edit was synchronized"
            return
        }
        val health = immediateObservation?.health ?: syncObservation?.health
        if (!offlineEditAcknowledged &&
            (health == DesktopSyncHealth.OFFLINE_UNVERIFIED || health == DesktopSyncHealth.DRIVE_UNVERIFIED)
        ) {
            pendingOfflineSnapshot = updated
            showOfflineEditWarning = true
            return
        }
        snapshot = updated
        dirty = true
        status = "Local changes are ready — Sync now is recommended"
        showSyncPrompt = true
    }

    fun publishNow(forceAgainstRemote: SyncManifest? = null) {
        val current = snapshot ?: return
        runCatching { BackupPublisher.publish(current, forceAgainstRemote) }
            .onSuccess {
                snapshot = it
                baseSnapshot = runCatching { BackupImporter.import(it.sourceZip) }.getOrDefault(it)
                dirty = false
                syncSucceeded = true
                undoReturnsDirty = false
                DesktopUndoStore.clear()
                showSyncPrompt = false
                status = "Cloud-folder ZIP updated by ${DesktopDeviceIdentity.name()} at ${formatTime(System.currentTimeMillis())}"
                syncObservation = DesktopSyncMonitor.inspect(it)
                tray.notify("Rider Notes synchronized", "The Drive backup ZIP was updated successfully.")
            }
            .onFailure { error ->
                status = "Sync stopped safely: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    fun requestMediaNote() {
        val captured = WindowsMediaBridge.captureAndPause()
        mediaDraft = captured
        mediaTitle = captured?.title.orEmpty()
        mediaSource = captured?.sourceApp.orEmpty()
        mediaAuthor = captured?.author.orEmpty()
        mediaModule = captured?.module.orEmpty()
        mediaChapter = captured?.chapter.orEmpty()
        mediaPosition = captured?.playbackPositionMs?.let(::formatPositionInput).orEmpty()
        mediaSourceUrl = captured?.sourceUrl.orEmpty()
        showMediaNoteDialog = true
    }

    LaunchedEffect(Unit) {
        DesktopDeviceIdentity.lastBackupPath()
            ?.let { File(it) }
            ?.takeIf(File::isFile)
            ?.let(::loadBackup)
    }

    LaunchedEffect(externalMediaNoteRequest) {
        if (externalMediaNoteRequest > 0) requestMediaNote()
    }

    LaunchedEffect(snapshot?.sourceZip?.absolutePath) {
        while (true) {
            delay(4_000)
            val current = snapshot ?: continue
            val observation = DesktopSyncMonitor.inspect(current)
            syncObservation = observation
            val revisionKey = observation.remoteRevision?.revisionId
                ?: if (observation.health == DesktopSyncHealth.NEW_CLOUD_VERSION) {
                    "zip:${current.sourceZip.lastModified()}:${current.sourceZip.length()}"
                } else null
            if (observation.health == DesktopSyncHealth.NEW_CLOUD_VERSION &&
                revisionKey != dismissedCloudRevision
            ) {
                showCloudChangeDialog = true
                tray.notify("New Rider Notes backup", observation.message)
            }
        }
    }

    if (showOfflineEditWarning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Cloud version cannot be verified") },
            text = {
                Text(
                    "This PC appears offline, or the Drive revision marker is unavailable. " +
                        "You can keep editing, but another device may have newer changes."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offlineEditAcknowledged = true
                    snapshot = pendingOfflineSnapshot
                    pendingOfflineSnapshot = null
                    dirty = true
                    showOfflineEditWarning = false
                    status = "Offline changes are pending — verify Drive before syncing"
                }) { Text("Continue editing") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingOfflineSnapshot = null
                    showOfflineEditWarning = false
                    snapshot?.sourceZip?.takeIf(File::isFile)?.let(::loadBackup)
                }) { Text("Discard this edit") }
            }
        )
    }

    if (showCloudChangeDialog) {
        val observation = syncObservation
        AlertDialog(
            onDismissRequest = {
                dismissedCloudRevision = observation?.remoteRevision?.revisionId
                showCloudChangeDialog = false
            },
            title = { Text("A newer cloud version is available") },
            text = {
                Text(
                    if (dirty) {
                        "This PC and the cloud both contain changes. Merge both is recommended and keeps independently created notes and Topic points."
                    } else {
                        "${observation?.message.orEmpty()}. Reload it before continuing so this PC shows the latest notes."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = snapshot
                    val base = baseSnapshot
                    if (dirty && current != null && base != null) {
                        runCatching {
                            val cloud = BackupImporter.import(current.sourceZip)
                            BackupMerger.keepBoth(base, current, cloud)
                        }.onSuccess { merged ->
                            snapshot = merged
                            baseSnapshot = merged
                            dirty = true
                            showCloudChangeDialog = false
                            publishNow(observation?.remoteRevision)
                        }.onFailure { status = "Merge stopped safely: ${it.message}" }
                    } else {
                        current?.sourceZip?.takeIf(File::isFile)?.let(::loadBackup)
                        showCloudChangeDialog = false
                    }
                }) { Text(if (dirty) "Merge both (recommended)" else "Reload cloud version") }
            },
            dismissButton = {
                Row {
                    if (dirty) {
                        TextButton(onClick = {
                            snapshot?.sourceZip?.takeIf(File::isFile)?.let(::loadBackup)
                            showCloudChangeDialog = false
                        }) { Text("Use cloud") }
                        TextButton(onClick = {
                            showCloudChangeDialog = false
                            publishNow(observation?.remoteRevision)
                        }) { Text("Keep PC as base") }
                    } else {
                        TextButton(onClick = {
                            dismissedCloudRevision = observation?.remoteRevision?.revisionId
                            showCloudChangeDialog = false
                        }) { Text("Later") }
                    }
                }
            }
        )
    }

    if (showCloudSyncReminder) {
        AlertDialog(
            onDismissRequest = { showCloudSyncReminder = false },
            title = { Text("Before opening a cloud backup") },
            text = {
                Text(
                    "Ensure that all recent changes on your phone have been included in a fresh " +
                        "full backup and successfully synchronized to the cloud. Otherwise, the " +
                        "ZIP downloaded on this PC may not contain your latest mobile notes, " +
                        "transcripts, Topics, or attachments."
                )
            },
            confirmButton = {
                TextButton(onClick = { showCloudSyncReminder = false }) {
                    Text("I understand")
                }
            }
        )
    }

    if (showSyncPrompt && dirty) {
        AlertDialog(
            onDismissRequest = { showSyncPrompt = false },
            title = { Text("Sync these changes?") },
            text = {
                Text(
                    "Rider Notes has unsaved cloud changes. Sync now rebuilds the complete ZIP " +
                        "and replaces it safely in your Google Drive for Desktop folder."
                )
            },
            confirmButton = { TextButton(onClick = { publishNow() }) { Text("Sync now") } },
            dismissButton = { TextButton(onClick = { showSyncPrompt = false }) { Text("Later") } }
        )
    }

    if (showMediaNoteDialog) {
        AlertDialog(
            onDismissRequest = { showMediaNoteDialog = false },
            title = { Text("Add note at paused media time") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mediaTitle, { mediaTitle = it }, label = { Text("Book / course / video title") })
                    OutlinedTextField(mediaSource, { mediaSource = it }, label = { Text("Source app") })
                    OutlinedTextField(mediaAuthor, { mediaAuthor = it }, label = { Text("Author / channel") })
                    OutlinedTextField(mediaModule, { mediaModule = it }, label = { Text("Module (optional)") })
                    OutlinedTextField(mediaChapter, { mediaChapter = it }, label = { Text("Chapter / episode (optional)") })
                    OutlinedTextField(
                        mediaPosition,
                        { mediaPosition = it },
                        label = { Text("Media position (mm:ss or hh:mm:ss)") }
                    )
                    OutlinedTextField(
                        mediaSourceUrl,
                        { mediaSourceUrl = it },
                        label = { Text("Source link (optional, verify before saving)") }
                    )
                    OutlinedTextField(mediaNoteText, { mediaNoteText = it }, label = { Text("Your note") })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = mediaNoteText.isNotBlank() && snapshot != null,
                    onClick = {
                        val media = CapturedMedia(
                            sourceApp = mediaSource.ifBlank { "Windows media" },
                            sourcePackage = mediaDraft?.sourcePackage,
                            title = mediaTitle.ifBlank { "Typed media note" },
                            author = mediaAuthor.takeIf(String::isNotBlank),
                            module = mediaModule.takeIf(String::isNotBlank),
                            chapter = mediaChapter.takeIf(String::isNotBlank),
                            sourceUrl = mediaSourceUrl.trim().takeIf(String::isNotBlank),
                            playbackPositionMs = parsePositionInput(mediaPosition)
                        )
                        markChanged(BackupEditor.addTypedMediaNote(snapshot!!, media, mediaNoteText))
                        showMediaNoteDialog = false
                        mediaNoteText = ""
                    }
                ) { Text("Add note") }
            },
            dismissButton = { TextButton(onClick = { showMediaNoteDialog = false }) { Text("Cancel") } }
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Rider Notes", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Windows companion · Google Drive desktop sync")
                }
                OutlinedButton(
                    enabled = snapshot != null,
                    onClick = ::requestMediaNote
                ) { Text("✎ Add media note") }
                OutlinedButton(
                    enabled = DesktopUndoStore.canUndo(snapshot),
                    onClick = {
                        val current = snapshot ?: return@OutlinedButton
                        runCatching { DesktopUndoStore.restore(current) }
                            .onSuccess {
                                snapshot = it
                                dirty = undoReturnsDirty
                                syncSucceeded = false
                                status = if (dirty) {
                                    "Previous edit undone — earlier local changes still need Sync now"
                                } else {
                                    "Previous edit undone — no local changes remain"
                                }
                            }
                            .onFailure { status = "Undo failed safely: ${it.message}" }
                    }
                ) { Text("↶ Undo") }
                Button(
                    enabled = dirty,
                    onClick = { publishNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),
                        contentColor = Color.White,
                        disabledContainerColor = if (syncSucceeded) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                        disabledContentColor = Color.White
                    )
                ) {
                    Text(
                        when {
                            dirty -> "Sync now — recommended"
                            syncSucceeded -> "✓ Local sync successful — upload ZIP to cloud"
                            else -> "Sync now — no local changes"
                        }
                    )
                }
                if (WindowsStartupManager.isSupported()) {
                    OutlinedButton(onClick = {
                        val target = !startupEnabled
                        WindowsStartupManager.setEnabled(target)
                            .onSuccess {
                                startupEnabled = target
                                status = if (target) "Rider Notes will start with Windows" else "Windows startup disabled"
                            }
                            .onFailure { status = it.message ?: "Could not change Windows startup" }
                    }) { Text(if (startupEnabled) "Startup: On" else "Startup: Off") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Current ZIP: ${snapshot?.sourceZip?.absolutePath ?: "No backup ZIP selected"}",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp
                    )
                    Button(onClick = { chooseBackup()?.let(::loadBackup) }) {
                        Text(if (snapshot == null) "Open ZIP" else "Change ZIP")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(status, color = MaterialTheme.colorScheme.primary)
            syncObservation?.let { observation ->
                Text(
                    "Cloud status: ${observation.message}",
                    color = when (observation.health) {
                        DesktopSyncHealth.CURRENT -> Color(0xFF2E7D32)
                        DesktopSyncHealth.NEW_CLOUD_VERSION -> Color(0xFF1565C0)
                        else -> Color(0xFFB26A00)
                    },
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(10.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Notes (${snapshot?.notes?.count { !it.topicOnly } ?: 0})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Topics (${snapshot?.groups?.size ?: 0})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Help / User Manual") }
                )
            }
            Spacer(Modifier.height(10.dp))

            val loaded = snapshot
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Rider Notes backup loaded")
                }
            } else if (selectedTab == 0) {
                NotesScreen(loaded, ::markChanged, audioPlayer)
            } else if (selectedTab == 1) {
                TopicsScreen(loaded, ::markChanged, audioPlayer)
            } else {
                UserManualScreen()
            }
        }
    }
}

@Composable
private fun NotesScreen(
    snapshot: BackupSnapshot,
    onChanged: (BackupSnapshot) -> Unit,
    audioPlayer: DesktopAudioPlayer
) {
    val notes = snapshot.notes.filterNot { it.topicOnly }
    var expandedSources by remember(notes) { mutableStateOf(emptySet<String>()) }
    var expandedTitles by remember(notes) { mutableStateOf(emptySet<String>()) }
    var selectedNoteIds by remember(snapshot.extractedRoot.absolutePath) { mutableStateOf(emptySet<String>()) }
    var topicPickerNote by remember { mutableStateOf<DesktopNote?>(null) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteLabel by remember { mutableStateOf("") }
    val grouped = notes.groupBy { it.sourceApp }.toSortedMap(String.CASE_INSENSITIVE_ORDER)

    fun requestDelete(ids: Set<String>, label: String) {
        if (ids.isEmpty()) return
        pendingDeleteIds = ids
        pendingDeleteLabel = label
    }

    fun executeDelete() {
        val ids = pendingDeleteIds
        if (ids.isEmpty()) return
        audioPlayer.stop()
        onChanged(BackupEditor.deleteNotes(snapshot, ids))
        selectedNoteIds = selectedNoteIds - ids
        pendingDeleteIds = emptySet()
        pendingDeleteLabel = ""
    }

    if (pendingDeleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                pendingDeleteIds = emptySet()
                pendingDeleteLabel = ""
            },
            title = { Text("Delete $pendingDeleteLabel?") },
            text = {
                Text(
                    "This will delete ${pendingDeleteIds.size} note(s), including their audio, metadata, " +
                        "and transcript files. You can restore this deletion with Undo before syncing."
                )
            },
            confirmButton = {
                TextButton(onClick = { executeDelete() }) { Text("Yes, delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDeleteIds = emptySet()
                    pendingDeleteLabel = ""
                }) { Text("No") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "note-selection-toolbar") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Selected: ${selectedNoteIds.size}", modifier = Modifier.weight(1f))
                OutlinedButton(
                    enabled = notes.isNotEmpty() && selectedNoteIds.size != notes.size,
                    onClick = { selectedNoteIds = notes.mapTo(mutableSetOf()) { it.id } }
                ) { Text("Select all") }
                Button(
                    enabled = selectedNoteIds.isNotEmpty(),
                    onClick = { requestDelete(selectedNoteIds, "selected notes") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("🗑 Delete selected") }
                OutlinedButton(
                    enabled = selectedNoteIds.isNotEmpty(),
                    onClick = { selectedNoteIds = emptySet() }
                ) { Text("↻ Clear selection") }
            }
        }
        grouped.forEach { (source, sourceNotes) ->
            item(key = "source:$source") {
                val expanded = source in expandedSources
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            expandedSources = if (expanded) expandedSources - source
                            else expandedSources + source
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("${if (expanded) "▼" else "▶"} $source (${sourceNotes.size})")
                    }
                    TextButton(onClick = {
                        requestDelete(sourceNotes.mapTo(mutableSetOf()) { it.id }, "all notes under $source")
                    }) {
                        Text("🗑", fontSize = 18.sp)
                    }
                }
            }
            if (source in expandedSources) {
                val titleGroups = sourceNotes.groupBy { it.title }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                titleGroups.forEach { (title, titleNotes) ->
                    val titleKey = "$source::$title"
                    item(key = "title:$titleKey") {
                        val expanded = titleKey in expandedTitles
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    expandedTitles = if (expanded) expandedTitles - titleKey
                                    else expandedTitles + titleKey
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("${if (expanded) "▼" else "▶"} $title (${titleNotes.size})")
                            }
                            TextButton(onClick = {
                                requestDelete(titleNotes.mapTo(mutableSetOf()) { it.id }, "all notes under $title")
                            }) {
                                Text("🗑", fontSize = 18.sp)
                            }
                        }
                    }
                    if (titleKey in expandedTitles) {
                        items(titleNotes.sortedByDescending { it.recordedAtEpochMs }, key = { it.id }) {
                            note -> NoteCard(
                                note = note,
                                audioPlayer = audioPlayer,
                                selected = note.id in selectedNoteIds,
                                onSelectedChange = { selected ->
                                    selectedNoteIds = if (selected) selectedNoteIds + note.id
                                    else selectedNoteIds - note.id
                                },
                                onDelete = { requestDelete(setOf(note.id), note.title) },
                                onAddToTopics = { topicPickerNote = note }
                            )
                        }
                    }
                }
            }
        }
    }
    topicPickerNote?.let { note ->
        TopicFolderPickerDialog(
            snapshot = snapshot,
            note = note,
            onDismiss = { topicPickerNote = null },
            onSnapshotChanged = onChanged,
            onAdded = {
                onChanged(it)
                topicPickerNote = null
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: DesktopNote,
    audioPlayer: DesktopAudioPlayer,
    selected: Boolean = false,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onAddToTopics: (() -> Unit)? = null
) {
    var expanded by remember(note.id) { mutableStateOf(false) }
    val playback = audioPlayer.state
    val isCurrent = playback.file?.absolutePath == note.audioFile?.absolutePath
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                onSelectedChange?.let { change ->
                    Checkbox(checked = selected, onCheckedChange = change)
                }
                val browseUrl = noteBrowseUrl(note)
                Text(
                    note.title,
                    fontWeight = FontWeight.Bold,
                    color = if (browseUrl != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).then(
                        if (browseUrl != null) Modifier.clickable { openUrl(browseUrl) }
                        else Modifier
                    )
                )
                onAddToTopics?.let {
                    TextButton(onClick = it) {
                        Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                onDelete?.let {
                    TextButton(onClick = it) { Text("🗑", fontSize = 18.sp) }
                }
            }
            Text("${note.sourceApp} · ${formatTime(note.recordedAtEpochMs)}")
            Text(
                "Origin: ${note.originDeviceName ?: "Legacy / unknown device"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                note.module?.let { Text("Module: $it") }
                note.chapter?.let { Text("Chapter / episode: $it") }
                note.author?.let { Text("Author / channel: $it") }
                note.playbackPositionMs?.let { Text("Media position: ${formatPosition(it)}") }
                note.typedNote?.takeIf(String::isNotBlank)?.let {
                    Text("Typed note", fontWeight = FontWeight.SemiBold)
                    Text(it)
                }
                Text("Transcription: ${note.transcriptionStatus}")
                note.transcript?.takeIf(String::isNotBlank)?.let { transcript ->
                    Spacer(Modifier.height(4.dp))
                    Text("Transcript", fontWeight = FontWeight.SemiBold)
                    PlaybackTranscript(
                        transcript = transcript,
                        segments = note.transcriptSegments,
                        positionMs = if (isCurrent) playback.positionMs else 0L,
                        followPlayback = isCurrent && playback.playing
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    note.audioFile?.takeIf { it.exists() && it.length() > 0L }?.let { audio ->
                        Button(onClick = { audioPlayer.playOrPause(audio) }) {
                            Text(
                                when {
                                    !isCurrent || !playback.playing -> "Play recording"
                                    playback.paused -> "Resume"
                                    else -> "Pause"
                                }
                            )
                        }
                        if (isCurrent && playback.playing) {
                            OutlinedButton(onClick = audioPlayer::stop) { Text("Stop") }
                        }
                    }
                    note.sourceUrl?.let { url ->
                        OutlinedButton(onClick = { openUrl(url) }) { Text("Open source") }
                    }
                    onDelete?.let {
                        Button(
                            onClick = it,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) { Text("Delete note") }
                    }
                }
                if (isCurrent && playback.durationMs > 0L) {
                    Slider(
                        value = playback.positionMs.coerceAtMost(playback.durationMs).toFloat(),
                        onValueChange = { audioPlayer.seek(it.toLong()) },
                        valueRange = 0f..playback.durationMs.toFloat()
                    )
                    Text("${formatPosition(playback.positionMs)} / ${formatPosition(playback.durationMs)}")
                }
                if (isCurrent) playback.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            } else {
                Text("Click to expand", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TopicFolderPickerDialog(
    snapshot: BackupSnapshot,
    note: DesktopNote,
    onDismiss: () -> Unit,
    onSnapshotChanged: (BackupSnapshot) -> Unit,
    onAdded: (BackupSnapshot) -> Unit
) {
    var currentGroupId by remember(note.id) { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var workingSnapshot by remember(snapshot) { mutableStateOf(snapshot) }
    val current = workingSnapshot.groups.firstOrNull { it.id == currentGroupId }
    val children = workingSnapshot.groups.filter { it.parentId == currentGroupId }.sortedBy { it.order }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current?.let { desktopTopicPath(workingSnapshot, it) } ?: "Add note to Topics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                current?.let { group ->
                    Button(
                        onClick = {
                            onAdded(BackupEditor.addRecordingPoint(workingSnapshot, group.id, note))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add as the last point in this folder") }
                }
                if (creatingFolder) {
                    OutlinedTextField(
                        newFolderName,
                        { newFolderName = it },
                        label = { Text(if (current == null) "New main folder" else "New subfolder") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = newFolderName.isNotBlank(), onClick = {
                            val updated = BackupEditor.createGroup(
                                workingSnapshot,
                                newFolderName,
                                current?.id
                            )
                            workingSnapshot = updated
                            currentGroupId = updated.groups.lastOrNull()?.id
                            onSnapshotChanged(updated)
                            newFolderName = ""
                            creatingFolder = false
                        }) { Text("Create") }
                        TextButton(onClick = { creatingFolder = false }) { Text("Cancel") }
                    }
                } else {
                    TextButton(onClick = { creatingFolder = true }) {
                        Text(if (current == null) "+ New main folder" else "+ New subfolder")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(children, key = { it.id }) { group ->
                        TextButton(
                            onClick = { currentGroupId = group.id },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📁 ${group.name}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { currentGroupId = current.parentId }) { Text("← Back") }
            }
        }
    )
}

@Composable
private fun TopicsScreen(
    snapshot: BackupSnapshot,
    onChanged: (BackupSnapshot) -> Unit,
    audioPlayer: DesktopAudioPlayer
) {
    val orderedGroups = remember(snapshot) { flattenGroups(snapshot.groups) }
    var expandedGroupIds by remember(snapshot.extractedRoot.absolutePath) {
        mutableStateOf(snapshot.groups.filter { it.parentId == null }.map { it.id }.toSet())
    }
    var selectedGroupId by remember(snapshot.extractedRoot.absolutePath) {
        mutableStateOf(orderedGroups.firstOrNull()?.first?.id)
    }
    var groupDialogParent by remember { mutableStateOf<String?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var pointDialogMode by remember { mutableStateOf<String?>(null) }
    var pointTitle by remember { mutableStateOf("") }
    var pointBody by remember { mutableStateOf("") }
    var pointNotes by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<File?>(null) }
    var showAttachmentNotesDialog by remember { mutableStateOf(false) }
    var showLegendDialog by remember { mutableStateOf(false) }
    var legendColor by remember { mutableStateOf("RED") }
    var legendMeaning by remember { mutableStateOf("") }
    var selectedPlacementIds by remember(snapshot.extractedRoot.absolutePath, selectedGroupId) {
        mutableStateOf(emptySet<String>())
    }
    var showBulkColorDialog by remember { mutableStateOf(false) }
    var pendingPointDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var linkPlacementId by remember { mutableStateOf<String?>(null) }
    var linkPickerGroupId by remember { mutableStateOf<String?>(null) }
    var filterColor by remember { mutableStateOf<String?>(null) }
    var filterStarred by remember { mutableStateOf(false) }
    var showFilterColorDialog by remember { mutableStateOf(false) }
    val recorder = remember { DesktopAudioRecorder() }
    var recording by remember { mutableStateOf(false) }

    val matchingPlacements = snapshot.placements.filter { placement ->
        topicPlacementMatches(placement, filterColor, filterStarred)
    }
    val filtering = filterColor != null || filterStarred
    val relevantGroupIds = topicRelevantGroupIds(snapshot.groups, matchingPlacements)
    val filteredGroups = if (filtering) snapshot.groups.filter { it.id in relevantGroupIds } else snapshot.groups
    val filteredVisibleGroups = visibleTopicGroups(filteredGroups, expandedGroupIds)
    LaunchedEffect(filterColor, filterStarred, relevantGroupIds) {
        if (filtering) expandedGroupIds = expandedGroupIds + relevantGroupIds
    }

    if (showFilterColorDialog) {
        AlertDialog(
            onDismissRequest = { showFilterColorDialog = false },
            title = { Text("Filter Topics by colour") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    topicColors.forEach { color ->
                        val meaning = if (color == "WHITE") "Nothing special"
                        else snapshot.colorLegend[color] ?: "Not defined"
                        OutlinedButton(
                            onClick = {
                                filterColor = color
                                showFilterColorDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = topicColor(color))
                        ) { Text("${color.lowercase().replaceFirstChar { it.uppercase() }} — $meaning") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showFilterColorDialog = false }) { Text("Cancel") } }
        )
    }

    if (linkPlacementId != null) {
        val sourcePlacement = snapshot.placements.firstOrNull { it.id == linkPlacementId }
        val selectedFolder = snapshot.groups.firstOrNull { it.id == linkPickerGroupId }
        val children = snapshot.groups
            .filter { it.parentId == linkPickerGroupId }
            .sortedBy { it.order }
        val alreadyLinkedHere = selectedFolder != null && sourcePlacement != null &&
            snapshot.placements.any {
                it.itemId == sourcePlacement.itemId && it.groupId == selectedFolder.id
            }
        AlertDialog(
            onDismissRequest = { linkPlacementId = null; linkPickerGroupId = null },
            title = { Text(selectedFolder?.let { topicGroupPath(snapshot.groups, it) } ?: "Choose destination folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selectedFolder != null) {
                        Button(
                            enabled = !alreadyLinkedHere && sourcePlacement != null,
                            onClick = {
                                val updated = BackupEditor.linkPlacementToGroup(
                                    snapshot,
                                    linkPlacementId!!,
                                    selectedFolder.id
                                )
                                onChanged(updated)
                                linkPlacementId = null
                                linkPickerGroupId = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (alreadyLinkedHere) "Already linked here" else "Link to this folder")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(children, key = { it.id }) { group ->
                            TextButton(
                                onClick = { linkPickerGroupId = group.id },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("📁 ${group.name}") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { linkPlacementId = null; linkPickerGroupId = null }) { Text("Cancel") }
            },
            dismissButton = {
                if (selectedFolder != null) {
                    TextButton(onClick = { linkPickerGroupId = selectedFolder.parentId }) { Text("← Back") }
                }
            }
        )
    }

    if (pendingPointDeleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingPointDeleteIds = emptySet() },
            title = { Text("Delete selected Topic point(s)?") },
            text = {
                Text(
                    "This removes ${pendingPointDeleteIds.size} point(s) from this Topic. " +
                        "A directly attached file is deleted only if no other Topic placement uses it. " +
                        "You can restore the operation with Undo before syncing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    audioPlayer.stop()
                    onChanged(BackupEditor.removePlacements(snapshot, pendingPointDeleteIds))
                    selectedPlacementIds = selectedPlacementIds - pendingPointDeleteIds
                    pendingPointDeleteIds = emptySet()
                }) { Text("Yes, delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPointDeleteIds = emptySet() }) { Text("No") }
            }
        )
    }

    if (showBulkColorDialog) {
        AlertDialog(
            onDismissRequest = { showBulkColorDialog = false },
            title = { Text("Colour selected points") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    topicColors.forEach { color ->
                        OutlinedButton(
                            onClick = {
                                onChanged(
                                    BackupEditor.updatePlacements(
                                        snapshot,
                                        selectedPlacementIds,
                                        color = color
                                    )
                                )
                                selectedPlacementIds = emptySet()
                                showBulkColorDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = topicColor(color))
                        ) {
                            val meaning = if (color == "WHITE") "Nothing special"
                            else snapshot.colorLegend[color] ?: "Not defined"
                            Text("${color.lowercase().replaceFirstChar { it.uppercase() }} — $meaning")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBulkColorDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text(if (groupDialogParent == null) "New topic folder" else "New subfolder") },
            text = { OutlinedTextField(groupName, { groupName = it }, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(enabled = groupName.isNotBlank(), onClick = {
                    val updated = BackupEditor.createGroup(snapshot, groupName, groupDialogParent)
                    onChanged(updated)
                    groupDialogParent?.let { expandedGroupIds = expandedGroupIds + it }
                    if (selectedGroupId == null) selectedGroupId = updated.groups.lastOrNull()?.id
                    groupName = ""
                    showGroupDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showGroupDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAttachmentNotesDialog) {
        AlertDialog(
            onDismissRequest = {
                showAttachmentNotesDialog = false
                pendingAttachment = null
                pointNotes = ""
            },
            title = { Text("Attachment notes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pendingAttachment?.name ?: "Selected attachment", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        pointNotes,
                        { pointNotes = it },
                        label = { Text("Notes about this file or image (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val source = pendingAttachment
                    val groupId = selectedGroupId
                    if (source != null && groupId != null) {
                        onChanged(BackupEditor.addAttachment(snapshot, groupId, source, pointNotes))
                    }
                    showAttachmentNotesDialog = false
                    pendingAttachment = null
                    pointNotes = ""
                }) { Text("Add attachment") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAttachmentNotesDialog = false
                    pendingAttachment = null
                    pointNotes = ""
                }) { Text("Cancel") }
            }
        )
    }

    if (showLegendDialog) {
        AlertDialog(
            onDismissRequest = { showLegendDialog = false },
            title = { Text("Topic colour legend") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a colour, then add or edit what it means.")
                    topicColors.forEach { color ->
                        val meaning = if (color == "WHITE") {
                            "Nothing special"
                        } else {
                            snapshot.colorLegend[color] ?: "Not defined"
                        }
                        OutlinedButton(
                            enabled = color != "WHITE",
                            onClick = {
                                legendColor = color
                                legendMeaning = snapshot.colorLegend[color].orEmpty()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = topicColor(color)
                            )
                        ) { Text("${color.lowercase().replaceFirstChar { it.uppercase() }} — $meaning") }
                    }
                    OutlinedTextField(
                        value = legendMeaning,
                        onValueChange = { legendMeaning = it },
                        enabled = legendColor != "WHITE",
                        label = { Text("Meaning of ${legendColor.lowercase()}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = legendColor != "WHITE",
                    onClick = {
                        onChanged(BackupEditor.updateLegend(snapshot, legendColor, legendMeaning))
                        showLegendDialog = false
                    }
                ) { Text("Save meaning") }
            },
            dismissButton = {
                TextButton(onClick = { showLegendDialog = false }) { Text("Close") }
            }
        )
    }

    pointDialogMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pointDialogMode = null; pointNotes = "" },
            title = { Text(if (mode == "TEXT") "New text point" else "New link point") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pointTitle, { pointTitle = it }, label = { Text("Title") })
                    OutlinedTextField(
                        pointBody,
                        { pointBody = it },
                        label = { Text(if (mode == "TEXT") "Text" else "Web link") }
                    )
                    if (mode == "LINK") {
                        OutlinedTextField(
                            pointNotes,
                            { pointNotes = it },
                            label = { Text("Notes about this link (optional)") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = pointBody.isNotBlank() && selectedGroupId != null, onClick = {
                    val groupId = selectedGroupId ?: return@TextButton
                    val updated = if (mode == "TEXT") {
                        BackupEditor.addTextPoint(snapshot, groupId, pointTitle, pointBody)
                    } else {
                        BackupEditor.addLinkPoint(snapshot, groupId, pointTitle, pointBody, pointNotes)
                    }
                    onChanged(updated)
                    pointTitle = ""
                    pointBody = ""
                    pointNotes = ""
                    pointDialogMode = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pointDialogMode = null; pointNotes = "" }) { Text("Cancel") } }
        )
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.width(250.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Topic folders", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        groupDialogParent = null
                        showGroupDialog = true
                    }) { Text("+ Folder") }
                }
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    filteredVisibleGroups.forEach { (group, depth) ->
                        val hasChildren = filteredGroups.any { it.parentId == group.id }
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(start = (depth * 12).dp, top = 1.dp, bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasChildren) {
                                TextButton(
                                    onClick = {
                                        expandedGroupIds = if (group.id in expandedGroupIds) {
                                            expandedGroupIds - group.id
                                        } else {
                                            expandedGroupIds + group.id
                                        }
                                    },
                                    modifier = Modifier.width(28.dp).heightIn(min = 28.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(if (group.id in expandedGroupIds) "▾" else "▸", fontSize = 13.sp)
                                }
                            } else {
                                Spacer(Modifier.width(28.dp))
                            }
                            val buttonModifier = Modifier.weight(1f).heightIn(min = 30.dp)
                            val label = "📁 ${group.name}"
                            if (selectedGroupId == group.id) {
                                Button(
                                    onClick = { selectedGroupId = group.id },
                                    modifier = buttonModifier,
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)
                                ) { Text(label, fontSize = 12.sp) }
                            } else {
                                OutlinedButton(
                                    onClick = { selectedGroupId = group.id },
                                    modifier = buttonModifier,
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)
                                ) { Text(label, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }

        val selected = snapshot.groups.firstOrNull { it.id == selectedGroupId }
        val itemById = snapshot.items.associateBy { it.id }
        val placements = snapshot.placements
            .filter { it.groupId == selectedGroupId }
            .filter { topicPlacementMatches(it, filterColor, filterStarred) }
            .sortedBy { it.order }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected?.name ?: "Select a topic",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                selected?.let { group ->
                    TextButton(onClick = {
                        groupDialogParent = group.id
                        showGroupDialog = true
                    }) { Text("+ Subfolder") }
                    TextButton(onClick = { pointNotes = ""; pointDialogMode = "TEXT" }) { Text("+ Text") }
                    TextButton(onClick = { pointNotes = ""; pointDialogMode = "LINK" }) { Text("+ Link") }
                    TextButton(onClick = {
                        chooseAttachment()?.let {
                            pendingAttachment = it
                            pointNotes = ""
                            showAttachmentNotesDialog = true
                        }
                    }) { Text("+ File") }
                    if (recording) {
                        Button(onClick = {
                            recorder.stop()?.let { audio ->
                                onChanged(BackupEditor.addAttachment(snapshot, group.id, audio))
                            }
                            recording = false
                        }) { Text("■ Stop recording") }
                    } else {
                        TextButton(onClick = {
                            runCatching { recorder.start() }
                                .onSuccess { recording = true }
                        }) { Text("● Record") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        topicColors.joinToString(" · ") { color ->
                            val meaning = if (color == "WHITE") "Nothing special"
                            else snapshot.colorLegend[color] ?: "Not defined"
                            "${color.lowercase().replaceFirstChar { it.uppercase() }}: $meaning"
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        legendColor = "RED"
                        legendMeaning = snapshot.colorLegend["RED"].orEmpty()
                        showLegendDialog = true
                    }) { Text("Edit legend") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showFilterColorDialog = true }) {
                    Text(filterColor?.let { "🎨 ${it.lowercase().replaceFirstChar { value -> value.uppercase() }}" } ?: "🎨 Filter by colour")
                }
                OutlinedButton(onClick = {
                    filterStarred = !filterStarred
                }) { Text(if (filterStarred) "★ Starred only" else "☆ Filter by star") }
                if (filtering) {
                    TextButton(onClick = { filterColor = null; filterStarred = false }) { Text("Clear filters") }
                    Text("Edits update this filtered list immediately.", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Selected points: ${selectedPlacementIds.size}", modifier = Modifier.weight(1f), fontSize = 12.sp)
                OutlinedButton(
                    enabled = placements.isNotEmpty() && selectedPlacementIds.size != placements.size,
                    onClick = { selectedPlacementIds = placements.mapTo(mutableSetOf()) { it.id } },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("Select all", fontSize = 12.sp) }
                OutlinedButton(
                    enabled = selectedPlacementIds.isNotEmpty(),
                    onClick = { selectedPlacementIds = emptySet() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("Clear", fontSize = 12.sp) }
                OutlinedButton(
                    enabled = selectedPlacementIds.isNotEmpty(),
                    onClick = {
                        onChanged(BackupEditor.updatePlacements(snapshot, selectedPlacementIds, important = true))
                        selectedPlacementIds = emptySet()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("★ Star", fontSize = 12.sp) }
                OutlinedButton(
                    enabled = selectedPlacementIds.isNotEmpty(),
                    onClick = {
                        onChanged(BackupEditor.updatePlacements(snapshot, selectedPlacementIds, important = false))
                        selectedPlacementIds = emptySet()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("Unstar", fontSize = 12.sp) }
                OutlinedButton(
                    enabled = selectedPlacementIds.isNotEmpty(),
                    onClick = { showBulkColorDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("Color", fontSize = 12.sp) }
                Button(
                    enabled = selectedPlacementIds.isNotEmpty(),
                    onClick = { pendingPointDeleteIds = selectedPlacementIds },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                ) { Text("🗑 Delete", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(placements, key = { _, placement -> placement.id }) { index, placement ->
                    itemById[placement.itemId]?.let { item ->
                        val linkedNote = item.target?.let { target ->
                            snapshot.notes.firstOrNull {
                                it.audioFile?.name == File(target).name ||
                                    it.id == File(target).nameWithoutExtension
                            }
                        }
                        TopicPointCard(
                            snapshot = snapshot,
                            item = item,
                            placement = placement,
                            serialNumber = index + 1,
                            selected = placement.id in selectedPlacementIds,
                            onSelectedChange = { selected ->
                                selectedPlacementIds = if (selected) selectedPlacementIds + placement.id
                                else selectedPlacementIds - placement.id
                            },
                            linkedNote = linkedNote,
                            audioPlayer = audioPlayer,
                            onChanged = onChanged,
                            onRequestLink = {
                                linkPlacementId = placement.id
                                linkPickerGroupId = null
                            },
                            onRequestDelete = { pendingPointDeleteIds = setOf(placement.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicPointCard(
    snapshot: BackupSnapshot,
    item: DesktopTopicItem,
    placement: DesktopTopicPlacement,
    serialNumber: Int,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    linkedNote: DesktopNote?,
    audioPlayer: DesktopAudioPlayer,
    onChanged: (BackupSnapshot) -> Unit,
    onRequestLink: () -> Unit,
    onRequestDelete: () -> Unit
) {
    var expanded by remember(placement.id) { mutableStateOf(false) }
    var showColorDialog by remember(placement.id) { mutableStateOf(false) }
    val playback = audioPlayer.state
    val isCurrent = playback.file?.absolutePath == linkedNote?.audioFile?.absolutePath
    val linkedBrowseUrl = linkedNote?.let(::noteBrowseUrl)
    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("Choose point colour") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    topicColors.forEach { color ->
                        OutlinedButton(
                            onClick = {
                                onChanged(BackupEditor.updatePlacement(snapshot, placement.id, color = color))
                                showColorDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = topicColor(color))
                        ) {
                            val meaning = if (color == "WHITE") "Nothing special"
                            else snapshot.colorLegend[color] ?: "Not defined"
                            Text("${color.lowercase().replaceFirstChar { it.uppercase() }} — $meaning")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showColorDialog = false }) { Text("Cancel") }
            }
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = topicColor(placement.color))
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Text(
                    "$serialNumber. ${item.title}",
                    fontWeight = FontWeight.Bold,
                    color = if (linkedBrowseUrl != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).then(
                        if (linkedBrowseUrl != null) Modifier.clickable { openUrl(linkedBrowseUrl) }
                        else Modifier
                    )
                )
                if (placement.important) Text("★", color = Color(0xFFFFA000), fontSize = 20.sp)
            }
            if (expanded) {
                Text("Type: ${item.type}", fontSize = 12.sp)
                item.text?.let { Text(it) }
                linkedNote?.let { note ->
                    Text("Source: ${note.sourceApp}", fontSize = 12.sp)
                    Text("Title: ${note.title}", fontSize = 12.sp)
                    note.module?.let { Text("Module: $it", fontSize = 12.sp) }
                    note.chapter?.let { Text("Chapter / episode: $it", fontSize = 12.sp) }
                    note.author?.let { Text("Author / channel: $it", fontSize = 12.sp) }
                    note.playbackPositionMs?.let { Text("Media position: ${formatPosition(it)}", fontSize = 12.sp) }
                    Text("Recorded: ${formatTime(note.recordedAtEpochMs)}", fontSize = 12.sp)
                    Text("Origin: ${note.originDeviceName ?: "Legacy / unknown device"}", fontSize = 11.sp)
                    note.typedNote?.takeIf(String::isNotBlank)?.let {
                        Text("Typed note", fontWeight = FontWeight.SemiBold)
                        Text(it)
                    }
                    Text("Transcription: ${note.transcriptionStatus}", fontSize = 12.sp)
                    note.transcript?.takeIf(String::isNotBlank)?.let { transcript ->
                        PlaybackTranscript(
                            transcript = transcript,
                            segments = note.transcriptSegments,
                            positionMs = if (isCurrent) playback.positionMs else 0L,
                            followPlayback = isCurrent && playback.playing
                        )
                    }
                    note.sourceUrl?.let { url ->
                        OutlinedButton(onClick = { openUrl(url) }) { Text("Open media source") }
                    }
                }
                resolveTarget(snapshot, item)?.let { target ->
                    if (item.type == "RECORDING" && linkedNote != null && target.length() > 0L) {
                        Button(onClick = { audioPlayer.playOrPause(target) }) {
                            Text(
                                when {
                                    !isCurrent || !playback.playing -> "Play recording"
                                    playback.paused -> "Resume"
                                    else -> "Pause"
                                }
                            )
                        }
                        if (isCurrent && playback.playing) {
                            OutlinedButton(onClick = audioPlayer::stop) { Text("Stop") }
                        }
                    } else {
                        Button(onClick = { openFile(target) }) { Text("Open attachment") }
                    }
                }
                if (isCurrent && playback.durationMs > 0L) {
                    Slider(
                        value = playback.positionMs.coerceAtMost(playback.durationMs).toFloat(),
                        onValueChange = { audioPlayer.seek(it.toLong()) },
                        valueRange = 0f..playback.durationMs.toFloat()
                    )
                    Text("${formatPosition(playback.positionMs)} / ${formatPosition(playback.durationMs)}")
                }
                item.target?.takeIf { item.type == "LINK" }?.let { url ->
                    Button(onClick = { openUrl(url) }) { Text("Open link") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        onChanged(BackupEditor.updatePlacement(snapshot, placement.id, important = !placement.important))
                    }) { Text(if (placement.important) "Unstar" else "Star") }
                    TextButton(onClick = { onChanged(BackupEditor.movePlacement(snapshot, placement.id, -1)) }) { Text("↑") }
                    TextButton(onClick = { onChanged(BackupEditor.movePlacement(snapshot, placement.id, 1)) }) { Text("↓") }
                    TextButton(onClick = { showColorDialog = true }) { Text("Color") }
                    TextButton(onClick = onRequestLink) { Text("Link to another folder") }
                    TextButton(onClick = onRequestDelete) { Text("Remove") }
                }
            } else {
                Text("Click to expand", fontSize = 12.sp)
            }
        }
    }
}

private fun topicGroupPath(groups: List<DesktopTopicGroup>, group: DesktopTopicGroup): String {
    val names = mutableListOf(group.name)
    var parentId = group.parentId
    val visited = mutableSetOf(group.id)
    while (parentId != null && visited.add(parentId)) {
        val parent = groups.firstOrNull { it.id == parentId } ?: break
        names += parent.name
        parentId = parent.parentId
    }
    return names.asReversed().joinToString(" › ")
}

internal fun topicPlacementMatches(
    placement: DesktopTopicPlacement,
    color: String?,
    starredOnly: Boolean
): Boolean = (color == null || placement.color == color) && (!starredOnly || placement.important)

internal fun topicRelevantGroupIds(
    groups: List<DesktopTopicGroup>,
    placements: List<DesktopTopicPlacement>
): Set<String> = placements.mapTo(mutableSetOf()) { it.groupId }.apply {
    var changed: Boolean
    do {
        changed = false
        groups.filter { it.id in this }.mapNotNull { it.parentId }.forEach { parent ->
            if (add(parent)) changed = true
        }
    } while (changed)
}

@Composable
private fun PlaybackTranscript(
    transcript: String,
    segments: List<DesktopTranscriptSegment>,
    positionMs: Long,
    followPlayback: Boolean
) {
    if (segments.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
        ) {
            Text(transcript)
        }
        return
    }
    val activeIndex = segments.indexOfLast { positionMs >= it.startMs }
        .coerceAtLeast(0)
        .coerceAtMost(segments.lastIndex)
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex, followPlayback) {
        if (followPlayback) listState.animateScrollToItem(activeIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(segments.size) { index ->
            val segment = segments[index]
            val active = index == activeIndex
            Text(
                text = if (active) highlightedSegment(segment, positionMs) else buildAnnotatedString {
                    append(segment.text)
                },
                modifier = Modifier.fillMaxWidth()
                    .background(if (active) Color(0xFFFFF3C4) else Color.Transparent)
                    .padding(4.dp)
            )
        }
    }
}

private fun highlightedSegment(
    segment: DesktopTranscriptSegment,
    positionMs: Long
) = buildAnnotatedString {
    append(segment.text)
    val words = Regex("""\S+""").findAll(segment.text).toList()
    if (words.isEmpty()) return@buildAnnotatedString
    val duration = (segment.endMs - segment.startMs).coerceAtLeast(1L)
    val progress = ((positionMs - segment.startMs).toDouble() / duration.toDouble())
        .coerceIn(0.0, 0.999999)
    val word = words[(progress * words.size).toInt()]
    addStyle(
        SpanStyle(
            background = Color(0xFFFFD54F),
            color = Color(0xFF1F1F1F),
            fontWeight = FontWeight.Bold
        ),
        word.range.first,
        word.range.last + 1
    )
}

@Composable
private fun UserManualScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ManualSection(
            "What Rider Notes PC does",
            "The Windows companion reads the same full backup used by the Android app. " +
                "It displays Notes and Topics, plays recordings with synchronized transcripts, " +
                "adds typed notes at the current Windows media position, and edits Topic folders and points."
        )
        ManualSection(
            "Recommended synchronization — Google Drive for Desktop",
            "1. Install Google Drive for Desktop and allow the Rider Notes cloud folder to synchronize.\n" +
                "2. In this app, open RiderNotes_Full_Backup.zip directly from that synchronized folder.\n" +
                "3. Before an edit, Rider Notes checks the ZIP and RiderNotes_Sync_State.json revision marker.\n" +
                "4. After an edit, Sync now turns red. Press it to rebuild the ZIP and revision marker.\n" +
                "5. A green result means the local synchronized-folder files were updated. Drive for Desktop uploads them automatically.\n" +
                "6. Wait for Google Drive to finish uploading before using Rider Notes on the phone."
        )
        ManualSection(
            "Manual Chrome download and upload",
            "This method works, but it is manual. First press Sync now on the phone. Download both " +
                "RiderNotes_Full_Backup.zip and RiderNotes_Sync_State.json into the same PC folder. " +
                "Open the ZIP, make changes, and press Sync now in the PC app. Then upload and replace " +
                "both the rebuilt ZIP and RiderNotes_Sync_State.json in the original Drive folder. " +
                "Do not delete the old files first: use Google Drive's replace option so the folder keeps one current file of each name. " +
                "Do not use the phone during this transfer, and wait for the upload to finish before reopening it."
        )
        ManualSection(
            "Cloud conflict choices",
            "Merge both keeps independently created notes, recordings, attachments, groups, and points from both devices. " +
                "Use cloud discards the unsynchronized PC working copy. Keep PC as base deliberately replaces the cloud base. " +
                "Merge both is normally the safest option."
        )
        ManualSection(
            "Adding a media note",
            "Play a supported source on Windows and click Add media note, or use the Rider Notes tray icon. " +
                "The PC pauses the active media and fills the available title, creator, album/course, chapter, and position. " +
                "Review the fields, type the note, and save it."
        )
        ManualSection(
            "Notes and Topics",
            "Notes are grouped by source and title. Expand a note to view metadata, transcript, playback controls, and source link. " +
                "Use the note checkboxes for Select all or Delete selected, or use the trash icon on a source/title heading to delete that entire category. " +
                "The most recent deletion can be restored with Undo before synchronization. " +
                "Use ⋮ to add the recording to an existing Topic folder or create a new folder/subfolder. " +
                "Topics can contain text, links, files, recordings, stars, colours, and ordered points. " +
                "Every point has a serial number and checkbox. Selected points can be coloured, starred, unstarred, or deleted together. " +
                "All deletion actions ask Yes/No first. Topic folders themselves are intentionally not deletable from the PC app. " +
                "Color opens the full palette; Edit legend defines each colour's meaning. Undo reverses the most recent PC edit before synchronization."
        )
        ManualSection(
            "Changing the active backup",
            "The complete active ZIP path is always shown at the top. Open ZIP or Change ZIP selects another full backup and completely reloads Notes, Topics, revision status, playback, selections, and the one-step Undo history from that file."
        )
        ManualSection(
            "Startup and system tray",
            "Startup: On creates a Windows Startup shortcut, so Rider Notes launches automatically after Windows sign-in. " +
                "Closing the window keeps it available in the notification-area tray. Use the tray menu to open the app, " +
                "add a media note, or exit completely. Startup does not start Android synchronization by itself; Drive for Desktop must also be running."
        )
        ManualSection(
            "Safe device switching",
            "Finish and synchronize changes on one device before editing on another. The PC checks every few seconds, " +
                "checks again when an edit is committed, and checks once more before publishing. " +
                "These checks can see cloud changes only after Google Drive for Desktop has downloaded them."
        )
    }
}

@Composable
private fun ManualSection(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(body)
        }
    }
}

private fun desktopTopicPath(snapshot: BackupSnapshot, group: DesktopTopicGroup): String {
    val names = mutableListOf(group.name)
    var parentId = group.parentId
    while (parentId != null) {
        val parent = snapshot.groups.firstOrNull { it.id == parentId } ?: break
        names.add(0, parent.name)
        parentId = parent.parentId
    }
    return names.joinToString(" › ")
}

private fun flattenGroups(groups: List<DesktopTopicGroup>): List<Pair<DesktopTopicGroup, Int>> {
    val byParent = groups.groupBy { it.parentId }
    val output = mutableListOf<Pair<DesktopTopicGroup, Int>>()
    fun visit(parentId: String?, depth: Int) {
        byParent[parentId].orEmpty().sortedBy { it.order }.forEach { group ->
            output += group to depth
            visit(group.id, depth + 1)
        }
    }
    visit(null, 0)
    return output
}

internal fun visibleTopicGroups(
    groups: List<DesktopTopicGroup>,
    expandedIds: Set<String>
): List<Pair<DesktopTopicGroup, Int>> {
    val byParent = groups.groupBy { it.parentId }
    val output = mutableListOf<Pair<DesktopTopicGroup, Int>>()
    fun visit(parentId: String?, depth: Int) {
        byParent[parentId].orEmpty().sortedBy { it.order }.forEach { group ->
            output += group to depth
            if (group.id in expandedIds) visit(group.id, depth + 1)
        }
    }
    visit(null, 0)
    return output
}

private fun resolveTarget(snapshot: BackupSnapshot, item: DesktopTopicItem): File? {
    val target = item.target ?: return null
    val direct = File(snapshot.libraryRoot, target)
    if (direct.exists()) return direct
    return snapshot.extractedRoot.walkTopDown().firstOrNull { it.isFile && it.name == File(target).name }
}

private fun chooseBackup(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Open Rider Notes full backup"
        fileFilter = FileNameExtensionFilter("Rider Notes ZIP backups", "zip")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun chooseAttachment(): File? {
    val chooser = JFileChooser().apply { dialogTitle = "Add file to this topic" }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun openFile(file: File) = runCatching {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
}

private fun openUrl(url: String) = runCatching {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI(url))
}

private val topicColors = listOf("WHITE", "RED", "YELLOW", "GREEN", "BLUE", "PURPLE")

private fun topicColor(name: String): Color = when (name.uppercase(Locale.ROOT)) {
    "RED" -> Color(0xFFFFE5E5)
    "YELLOW" -> Color(0xFFFFF6CC)
    "GREEN" -> Color(0xFFE5F6E5)
    "BLUE" -> Color(0xFFE4F0FF)
    "PURPLE" -> Color(0xFFF1E7FF)
    else -> Color(0xFFF5F5F5)
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(epochMs))

private fun formatPosition(positionMs: Long): String {
    val totalSeconds = positionMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun formatPositionInput(positionMs: Long): String = formatPosition(positionMs)

private fun parsePositionInput(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val parts = trimmed.split(':')
    val totalSeconds = when (parts.size) {
        1 -> parts.single().toDoubleOrNull() ?: return null
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].toDoubleOrNull() ?: return null
            minutes * 60.0 + seconds
        }
        3 -> {
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            hours * 3_600.0 + minutes * 60.0 + seconds
        }
        else -> return null
    }
    return (totalSeconds * 1_000.0).toLong().takeIf { it >= 0L }
}

private fun noteBrowseUrl(note: DesktopNote): String? {
    DesktopYouTubeLinks.atPosition(
        note.sourceApp,
        note.sourceUrl,
        note.playbackPositionMs
    )?.let { return it }
    val query = java.net.URLEncoder.encode(
        listOfNotNull(note.title, note.author).joinToString(" "),
        Charsets.UTF_8
    )
    return when {
        note.sourceApp.contains("YouTube Music", ignoreCase = true) ->
            "https://music.youtube.com/search?q=$query"
        note.sourceApp.contains("YouTube", ignoreCase = true) ->
            "https://www.youtube.com/results?search_query=$query"
        else -> null
    }
}
