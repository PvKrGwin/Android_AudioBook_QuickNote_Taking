package com.praveen.ridernotes.topics

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.praveen.ridernotes.recording.SavedVoiceNote

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopicLibraryScreen(
    savedNotes: List<SavedVoiceNote>,
    onPlayRecording: (SavedVoiceNote) -> Unit,
    onStartRecording: (String, Int?) -> Unit,
    onRefreshNotes: () -> Unit
) {
    val context = LocalContext.current
    var library by remember { mutableStateOf(TopicLibraryRepository.load(context)) }
    var currentGroupId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var inputTitle by remember { mutableStateOf("") }
    var inputBody by remember { mutableStateOf("") }
    var insertionIndex by remember { mutableIntStateOf(-1) }
    var actionPlacement by remember { mutableStateOf<TopicPlacement?>(null) }
    var linkItemId by remember { mutableStateOf<String?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<TopicGroup?>(null) }
    var expandedPlacements by remember { mutableStateOf<Set<String>>(emptySet()) }
    var colorPlacement by remember { mutableStateOf<TopicPlacement?>(null) }
    var legendExpanded by remember { mutableStateOf(false) }
    var legendColor by remember { mutableStateOf<String?>(null) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }

    fun refresh() { library = TopicLibraryRepository.load(context) }
    fun resetInput() { inputTitle = ""; inputBody = ""; insertionIndex = -1; dialog = null }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val groupId = currentGroupId
        if (uri != null && groupId != null) {
            TopicLibraryRepository.addAttachment(context, groupId, uri, insertionIndex.takeIf { it >= 0 })
            refresh()
        }
        insertionIndex = -1
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreStatus = TopicLibraryRepository.restoreBackup(context, uri)
            onRefreshNotes()
            refresh()
        }
    }

    val currentGroup = library.groups.firstOrNull { it.id == currentGroupId }
    val childGroups = library.groups.filter { it.parentId == currentGroupId }.sortedBy { it.order }
    val placements = currentGroupId?.let { id -> library.placements.filter { it.groupId == id }.sortedBy { it.order } }.orEmpty()
    val itemById = library.items.associateBy { it.id }

    legendColor?.let { color ->
        InputDialog(
            title = "Meaning of ${color.lowercase().replaceFirstChar { it.uppercase() }}",
            label = "Legend meaning (blank clears it)",
            value = inputBody,
            onChange = { inputBody = it },
            onCancel = { legendColor = null; inputBody = "" }
        ) {
            TopicLibraryRepository.setLegendMeaning(context, color, inputBody)
            legendColor = null; inputBody = ""; refresh()
        }
    }

    if (dialog == "group") InputDialog("New group", "Group name", inputTitle, { inputTitle = it }, ::resetInput) {
        if (inputTitle.isNotBlank()) TopicLibraryRepository.createGroup(context, inputTitle, currentGroupId)
        resetInput(); refresh()
    }
    if (dialog == "text") TwoInputDialog("New text point", "Title", "Text", inputTitle, inputBody, { inputTitle = it }, { inputBody = it }, ::resetInput) {
        currentGroupId?.let { TopicLibraryRepository.addText(context, it, inputTitle, inputBody, insertionIndex.takeIf { value -> value >= 0 }) }
        resetInput(); refresh()
    }
    if (dialog == "link") TwoInputDialog("New web link", "Title", "https://…", inputTitle, inputBody, { inputTitle = it }, { inputBody = it }, ::resetInput) {
        currentGroupId?.let { TopicLibraryRepository.addLink(context, it, inputTitle, inputBody, insertionIndex.takeIf { value -> value >= 0 }) }
        resetInput(); refresh()
    }
    if (dialog == "recordings") {
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Add recording") }, text = {
            LazyColumn { items(savedNotes, key = { it.audioFile.absolutePath }) { note ->
                TextButton(onClick = {
                    currentGroupId?.let { TopicLibraryRepository.addRecording(context, it, note.audioFile.name, note.title, insertionIndex.takeIf { value -> value >= 0 }) }
                    dialog = null; insertionIndex = -1; refresh()
                }) { Text("${note.sourceApp}: ${note.title}") }
            } }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
    }
    if (linkItemId != null) {
        AlertDialog(onDismissRequest = { linkItemId = null }, title = { Text("Link point to another group") }, text = {
            LazyColumn { items(library.groups, key = { it.id }) { group ->
                TextButton(onClick = {
                    TopicLibraryRepository.linkItem(context, linkItemId!!, group.id)
                    linkItemId = null; refresh()
                }) { Text(groupPath(library, group)) }
            } }
        }, confirmButton = { TextButton(onClick = { linkItemId = null }) { Text("Cancel") } })
    }
    actionPlacement?.let { placement ->
        val pointIndex = placements.indexOfFirst { it.id == placement.id }
        AlertDialog(onDismissRequest = { actionPlacement = null }, title = { Text("Point options") }, text = {
            Column {
                TextButton(onClick = { TopicLibraryRepository.setImportant(context, placement.id, !placement.important); actionPlacement = null; refresh() }) {
                    Text(if (placement.important) "Remove important star" else "Mark as important")
                }
                TextButton(onClick = { colorPlacement = placement; actionPlacement = null }) { Text("Set colour") }
                TextButton(onClick = { TopicLibraryRepository.move(context, placement.id, -1); actionPlacement = null; refresh() }) { Text("Move up") }
                TextButton(onClick = { TopicLibraryRepository.move(context, placement.id, 1); actionPlacement = null; refresh() }) { Text("Move down") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; dialog = "text"; actionPlacement = null }) { Text("Insert text after this point") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; dialog = "link"; actionPlacement = null }) { Text("Insert web link after this point") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; actionPlacement = null; filePicker.launch(arrayOf("*/*")) }) { Text("Insert file after this point") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; dialog = "recordings"; actionPlacement = null }) { Text("Insert existing recording after this point") }
                TextButton(onClick = { onStartRecording(placement.groupId, pointIndex + 1); actionPlacement = null }) { Text("Record after this point") }
                TextButton(onClick = { linkItemId = placement.itemId; actionPlacement = null }) { Text("Link this point to another group") }
                TextButton(onClick = { TopicLibraryRepository.removePlacement(context, placement.id); actionPlacement = null; refresh() }) { Text("Remove from this group") }
            }
        }, confirmButton = { TextButton(onClick = { actionPlacement = null }) { Text("Close") } })
    }
    colorPlacement?.let { placement ->
        AlertDialog(
            onDismissRequest = { colorPlacement = null },
            title = { Text("Point colour") },
            text = {
                Column {
                    topicColors.forEach { color ->
                        TextButton(onClick = {
                            TopicLibraryRepository.setColor(context, placement.id, color)
                            colorPlacement = null; refresh()
                        }) { Text(if (color == "WHITE") "White — nothing special" else color.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorPlacement = null }) { Text("Cancel") } }
        )
    }
    deleteGroupTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text("Delete ${group.name}?") },
            text = { Text("This removes the group, its subgroups, and their placements. Shared points and original recordings remain available elsewhere.") },
            confirmButton = { TextButton(onClick = { TopicLibraryRepository.deleteGroup(context, group.id); deleteGroupTarget = null; refresh() }) { Text("Delete group") } },
            dismissButton = { TextButton(onClick = { deleteGroupTarget = null }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (currentGroup != null) OutlinedButton(onClick = { currentGroupId = currentGroup.parentId }) { Text("← Back") }
            Text(
                currentGroup?.let { groupPath(library, it) } ?: "Topic groups",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp).weight(1f)
            )
            if (currentGroup != null) TextButton(onClick = { legendExpanded = !legendExpanded }) { Text("🎨 Legend") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { inputTitle = ""; dialog = "group" }) { Text(if (currentGroup == null) "+ Group" else "+ Subgroup") }
            OutlinedButton(onClick = { onRefreshNotes(); refresh() }) { Text("Refresh") }
            if (currentGroup == null) OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore backup") }
            if (currentGroup != null) {
                OutlinedButton(onClick = { dialog = "text" }) { Text("+ Text") }
                OutlinedButton(onClick = { dialog = "link" }) { Text("+ Link") }
            }
        }
        if (currentGroup != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("+ File") }
            OutlinedButton(onClick = { dialog = "recordings" }) { Text("+ Existing recording") }
            OutlinedButton(onClick = { onStartRecording(currentGroup.id, null) }) { Text("+ Record") }
        }
        restoreStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (currentGroup != null && legendExpanded) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Colour legend", fontWeight = FontWeight.Bold)
                    topicColors.forEach { color ->
                        val meaning = if (color == "WHITE") "Nothing special" else library.colorLegend[color] ?: "Not defined"
                        if (color == "WHITE") Text("White: $meaning", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        else TextButton(onClick = { legendColor = color; inputBody = library.colorLegend[color].orEmpty() }) {
                            Text("${color.lowercase().replaceFirstChar { it.uppercase() }}: $meaning")
                        }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(childGroups, key = { "group-${it.id}" }) { group ->
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { currentGroupId = group.id }, onLongClick = { deleteGroupTarget = group })) {
                    Text("📁 ${group.name}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
                }
            }
            items(placements, key = { it.id }) { placement ->
                val item = itemById[placement.itemId] ?: return@items
                val note = if (item.type == TopicLibraryRepository.TYPE_RECORDING) savedNotes.firstOrNull { it.audioFile.name == item.target } else null
                val attachment = if (item.type == TopicLibraryRepository.TYPE_ATTACHMENT) TopicLibraryRepository.attachmentFile(context, item) else null
                val originalMissing = when (item.type) {
                    TopicLibraryRepository.TYPE_RECORDING -> note == null || note.audioFile.exists().not()
                    TopicLibraryRepository.TYPE_ATTACHMENT -> attachment?.exists() != true
                    else -> false
                }
                val expanded = placement.id in expandedPlacements
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = topicCardColor(placement.color),
                        contentColor = if (placement.color == "WHITE") MaterialTheme.colorScheme.onSurface else Color(0xFF1B1B1B)
                    ),
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { expandedPlacements = if (expanded) expandedPlacements - placement.id else expandedPlacements + placement.id },
                    onLongClick = { actionPlacement = placement }
                )) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${placement.order + 1}. ${item.title}",
                                fontWeight = FontWeight.Bold,
                                textDecoration = if (originalMissing) TextDecoration.LineThrough else TextDecoration.None,
                                modifier = Modifier.weight(1f)
                            )
                            if (placement.important) Text("★", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                        }
                        if (originalMissing) Text("Original content is missing — restore it from backup to reactivate this point.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        if (expanded) {
                            Text(item.type.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
                            item.text?.let { Text(it) }
                            item.target?.takeIf { item.type == TopicLibraryRepository.TYPE_LINK }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            note?.let {
                                Text("Source: ${it.sourceApp}")
                                it.module?.let { value -> Text("Module: $value") }
                                it.chapter?.let { value -> Text("Chapter: $value") }
                                it.author?.let { value -> Text("Author / creator: $value") }
                                it.playbackPositionLabel?.let { value -> Text("Media time: $value") }
                                Text("Recorded: ${it.recordedAtLabel}")
                                Text(it.transcript ?: "Transcript not available")
                            }
                            when (item.type) {
                                TopicLibraryRepository.TYPE_RECORDING -> Button(enabled = note != null && !originalMissing, onClick = { note?.let(onPlayRecording) }) { Text("Play recording") }
                                TopicLibraryRepository.TYPE_LINK -> Button(onClick = { item.target?.let { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) } }) { Text("Open link") }
                                TopicLibraryRepository.TYPE_ATTACHMENT -> Button(enabled = !originalMissing, onClick = {
                                    attachment?.let { file ->
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    }
                                }) { Text("Open file") }
                            }
                        }
                        Text(if (expanded) "Tap to collapse · Long-press for options" else "Tap to expand · Long-press for options", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (currentGroup == null && childGroups.isEmpty()) item { Text("Create your first topic group, for example Biology.") }
        }
    }
}

private val topicColors = listOf("WHITE", "RED", "YELLOW", "GREEN", "BLUE", "PURPLE")

@Composable
private fun topicCardColor(color: String): Color = when (color) {
    "RED" -> Color(0xFFFFE5E5)
    "YELLOW" -> Color(0xFFFFF6CC)
    "GREEN" -> Color(0xFFE2F4E5)
    "BLUE" -> Color(0xFFE2EEFF)
    "PURPLE" -> Color(0xFFF0E5FF)
    else -> MaterialTheme.colorScheme.surface
}

@Composable private fun InputDialog(title: String, label: String, value: String, onChange: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) =
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = { OutlinedTextField(value, onChange, label = { Text(label) }) }, confirmButton = { TextButton(onClick = onSave) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })

@Composable private fun TwoInputDialog(title: String, firstLabel: String, secondLabel: String, first: String, second: String, onFirst: (String) -> Unit, onSecond: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) =
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = { Column { OutlinedTextField(first, onFirst, label = { Text(firstLabel) }); Spacer(Modifier.height(8.dp)); OutlinedTextField(second, onSecond, label = { Text(secondLabel) }) } }, confirmButton = { TextButton(onClick = onSave) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })

private fun groupPath(library: TopicLibrary, group: TopicGroup): String {
    val names = mutableListOf(group.name)
    var parent = group.parentId
    while (parent != null) {
        val value = library.groups.firstOrNull { it.id == parent } ?: break
        names.add(0, value.name); parent = value.parentId
    }
    return names.joinToString(" › ")
}
