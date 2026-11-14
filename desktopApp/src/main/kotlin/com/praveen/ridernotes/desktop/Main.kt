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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Rider Notes PC"
    ) {
        MaterialTheme {
            RiderNotesDesktopApp()
        }
    }
}

@Composable
private fun RiderNotesDesktopApp() {
    var snapshot by remember { mutableStateOf<BackupSnapshot?>(null) }
    var status by remember { mutableStateOf("Open RiderNotes_Full_Backup.zip to begin.") }
    var selectedTab by remember { mutableStateOf(0) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Rider Notes", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Windows companion · read-only backup viewer")
                }
                Button(onClick = {
                    chooseBackup()?.let { zip ->
                        runCatching { BackupImporter.import(zip) }
                            .onSuccess {
                                snapshot = it
                                status = "Loaded ${it.notes.size} notes and ${it.groups.size} topic folders from ${zip.name}"
                            }
                            .onFailure { error ->
                                status = "Import failed: ${error.message ?: error.javaClass.simpleName}"
                            }
                    }
                }) {
                    Text("Open backup ZIP")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(status, color = MaterialTheme.colorScheme.primary)
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
                TopicsScreen(loaded)
            }
        }
    }
}

@Composable
private fun NotesScreen(notes: List<DesktopNote>) {
    var expandedSources by remember(notes) { mutableStateOf(emptySet<String>()) }
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
                items(sourceNotes, key = { it.id }) { note -> NoteCard(note) }
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
            if (expanded) {
                note.module?.let { Text("Module: $it") }
                note.chapter?.let { Text("Chapter / episode: $it") }
                note.author?.let { Text("Author / channel: $it") }
                note.playbackPositionMs?.let { Text("Media position: ${formatPosition(it)}") }
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
private fun TopicsScreen(snapshot: BackupSnapshot) {
    val orderedGroups = remember(snapshot) { flattenGroups(snapshot.groups) }
    var selectedGroupId by remember(snapshot) {
        mutableStateOf(orderedGroups.firstOrNull()?.first?.id)
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.width(280.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Text("Topic folders", fontWeight = FontWeight.Bold)
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
            Text(selected?.name ?: "Select a topic", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            snapshot.colorLegend.takeIf { it.isNotEmpty() }?.let { legend ->
                Text(legend.entries.joinToString(" · ") { "${it.key}: ${it.value}" }, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(placements, key = { it.id }) { placement ->
                    itemById[placement.itemId]?.let { item ->
                        TopicPointCard(snapshot, item, placement)
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
    placement: DesktopTopicPlacement
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
