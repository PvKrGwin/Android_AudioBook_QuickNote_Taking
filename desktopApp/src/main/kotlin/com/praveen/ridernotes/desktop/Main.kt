package com.praveen.ridernotes.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
    var syncObservation by remember { mutableStateOf<DesktopSyncObservation?>(null) }
    var showCloudChangeDialog by remember { mutableStateOf(false) }
    var dismissedCloudRevision by remember { mutableStateOf<String?>(null) }
    var showOfflineEditWarning by remember { mutableStateOf(false) }
    var pendingOfflineSnapshot by remember { mutableStateOf<BackupSnapshot?>(null) }
    var offlineEditAcknowledged by remember { mutableStateOf(false) }
    var startupEnabled by remember { mutableStateOf(WindowsStartupManager.isEnabled()) }

    fun loadBackup(zip: File) {
        runCatching { BackupImporter.import(zip) }
            .onSuccess {
                snapshot = it
                baseSnapshot = runCatching { BackupImporter.import(zip) }.getOrDefault(it)
                dirty = false
                offlineEditAcknowledged = false
                syncObservation = DesktopSyncMonitor.inspect(it)
                DesktopDeviceIdentity.rememberBackupPath(zip.absolutePath)
                status = "Loaded ${it.notes.size} notes and ${it.groups.size} topic folders from ${zip.name}"
            }
            .onFailure { error ->
                status = "Import failed: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    fun markChanged(updated: BackupSnapshot) {
        val health = syncObservation?.health
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
        mediaPosition = captured?.playbackPositionMs?.div(1_000.0)?.toString().orEmpty()
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
                    OutlinedTextField(mediaPosition, { mediaPosition = it }, label = { Text("Position in seconds") })
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
                            sourceUrl = mediaDraft?.sourceUrl,
                            playbackPositionMs = mediaPosition.toDoubleOrNull()?.let { (it * 1_000).toLong() }
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
                if (dirty) {
                    Button(onClick = { publishNow() }) { Text("Sync now — recommended") }
                }
                Button(onClick = { chooseBackup()?.let(::loadBackup) }) {
                    Text("Open backup ZIP")
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
            }
            Spacer(Modifier.height(10.dp))

            val loaded = snapshot
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Rider Notes backup loaded")
                }
            } else if (selectedTab == 0) {
                NotesScreen(loaded.notes.filterNot { it.topicOnly })
            } else {
                TopicsScreen(loaded, ::markChanged)
            }
        }
    }
}

@Composable
private fun NotesScreen(notes: List<DesktopNote>) {
    var expandedSources by remember(notes) { mutableStateOf(emptySet<String>()) }
    var expandedTitles by remember(notes) { mutableStateOf(emptySet<String>()) }
    val grouped = notes.groupBy { it.sourceApp }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grouped.forEach { (source, sourceNotes) ->
            item(key = "source:$source") {
                val expanded = source in expandedSources
                OutlinedButton(
                    onClick = {
                        expandedSources = if (expanded) {
                            expandedSources - source
                        } else {
                            expandedSources + source
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${if (expanded) "▼" else "▶"} $source (${sourceNotes.size})")
                }
            }
            if (source in expandedSources) {
                val titleGroups = sourceNotes.groupBy { it.title }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                titleGroups.forEach { (title, titleNotes) ->
                    val titleKey = "$source::$title"
                    item(key = "title:$titleKey") {
                        val expanded = titleKey in expandedTitles
                        OutlinedButton(
                            onClick = {
                                expandedTitles = if (expanded) expandedTitles - titleKey
                                else expandedTitles + titleKey
                            },
                            modifier = Modifier.fillMaxWidth().padding(start = 18.dp)
                        ) {
                            Text("${if (expanded) "▼" else "▶"} $title (${titleNotes.size})")
                        }
                    }
                    if (titleKey in expandedTitles) {
                        items(titleNotes.sortedByDescending { it.recordedAtEpochMs }, key = { it.id }) {
                            note -> NoteCard(note)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: DesktopNote) {
    var expanded by remember(note.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(note.title, fontWeight = FontWeight.Bold)
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
                note.transcript?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Transcript", fontWeight = FontWeight.SemiBold)
                    Text(it)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    note.audioFile?.takeIf(File::exists)?.let { audio ->
                        Button(onClick = { openFile(audio) }) { Text("Play audio") }
                    }
                    note.sourceUrl?.let { url ->
                        OutlinedButton(onClick = { openUrl(url) }) { Text("Open source") }
                    }
                }
            } else {
                Text("Click to expand", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TopicsScreen(
    snapshot: BackupSnapshot,
    onChanged: (BackupSnapshot) -> Unit
) {
    val orderedGroups = remember(snapshot) { flattenGroups(snapshot.groups) }
    var selectedGroupId by remember(snapshot.sourceZip.absolutePath) {
        mutableStateOf(orderedGroups.firstOrNull()?.first?.id)
    }
    var groupDialogParent by remember { mutableStateOf<String?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var pointDialogMode by remember { mutableStateOf<String?>(null) }
    var pointTitle by remember { mutableStateOf("") }
    var pointBody by remember { mutableStateOf("") }
    val recorder = remember { DesktopAudioRecorder() }
    var recording by remember { mutableStateOf(false) }

    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text(if (groupDialogParent == null) "New topic folder" else "New subfolder") },
            text = { OutlinedTextField(groupName, { groupName = it }, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(enabled = groupName.isNotBlank(), onClick = {
                    val updated = BackupEditor.createGroup(snapshot, groupName, groupDialogParent)
                    onChanged(updated)
                    if (selectedGroupId == null) selectedGroupId = updated.groups.lastOrNull()?.id
                    groupName = ""
                    showGroupDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showGroupDialog = false }) { Text("Cancel") } }
        )
    }

    pointDialogMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pointDialogMode = null },
            title = { Text(if (mode == "TEXT") "New text point" else "New link point") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pointTitle, { pointTitle = it }, label = { Text("Title") })
                    OutlinedTextField(
                        pointBody,
                        { pointBody = it },
                        label = { Text(if (mode == "TEXT") "Text" else "Web link") }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pointBody.isNotBlank() && selectedGroupId != null, onClick = {
                    val groupId = selectedGroupId ?: return@TextButton
                    val updated = if (mode == "TEXT") {
                        BackupEditor.addTextPoint(snapshot, groupId, pointTitle, pointBody)
                    } else {
                        BackupEditor.addLinkPoint(snapshot, groupId, pointTitle, pointBody)
                    }
                    onChanged(updated)
                    pointTitle = ""
                    pointBody = ""
                    pointDialogMode = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pointDialogMode = null }) { Text("Cancel") } }
        )
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.width(280.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Topic folders", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        groupDialogParent = null
                        showGroupDialog = true
                    }) { Text("+ Folder") }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    orderedGroups.forEach { (group, depth) ->
                        val label = "  ".repeat(depth) +
                            (if (depth == 0) "📁 " else "↳ ") + group.name
                        if (selectedGroupId == group.id) {
                            Button(
                                onClick = { selectedGroupId = group.id },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { selectedGroupId = group.id },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) { Text(label) }
                        }
                    }
                }
            }
        }

        val selected = snapshot.groups.firstOrNull { it.id == selectedGroupId }
        val itemById = snapshot.items.associateBy { it.id }
        val placements = snapshot.placements
            .filter { it.groupId == selectedGroupId }
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
                    TextButton(onClick = { pointDialogMode = "TEXT" }) { Text("+ Text") }
                    TextButton(onClick = { pointDialogMode = "LINK" }) { Text("+ Link") }
                    TextButton(onClick = {
                        chooseAttachment()?.let { onChanged(BackupEditor.addAttachment(snapshot, group.id, it)) }
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
                    TextButton(onClick = {
                        onChanged(BackupEditor.deleteGroup(snapshot, group.id))
                        selectedGroupId = snapshot.groups.firstOrNull { it.id != group.id }?.id
                    }) { Text("Delete folder") }
                }
            }
            snapshot.colorLegend.takeIf { it.isNotEmpty() }?.let { legend ->
                Text(legend.entries.joinToString(" · ") { "${it.key}: ${it.value}" }, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(placements, key = { it.id }) { placement ->
                    itemById[placement.itemId]?.let { item ->
                        TopicPointCard(
                            snapshot = snapshot,
                            item = item,
                            placement = placement,
                            onChanged = onChanged
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
    onChanged: (BackupSnapshot) -> Unit
) {
    var expanded by remember(placement.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = topicColor(placement.color))
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (placement.important) Text("★", color = Color(0xFFFFA000), fontSize = 20.sp)
            }
            if (expanded) {
                Text("Type: ${item.type}", fontSize = 12.sp)
                item.text?.let { Text(it) }
                resolveTarget(snapshot, item)?.let { target ->
                    Button(onClick = { openFile(target) }) {
                        Text(if (item.type == "RECORDING") "Play recording" else "Open attachment")
                    }
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
                    TextButton(onClick = {
                        val colors = listOf("WHITE", "RED", "YELLOW", "GREEN", "BLUE", "PURPLE")
                        val next = colors[(colors.indexOf(placement.color).coerceAtLeast(0) + 1) % colors.size]
                        onChanged(BackupEditor.updatePlacement(snapshot, placement.id, color = next))
                    }) { Text("Color") }
                    TextButton(onClick = { onChanged(BackupEditor.removePlacement(snapshot, placement.id)) }) { Text("Remove") }
                }
            } else {
                Text("Click to expand", fontSize = 12.sp)
            }
        }
    }
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
