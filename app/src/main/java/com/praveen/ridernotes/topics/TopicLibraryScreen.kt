package com.praveen.ridernotes.topics

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.praveen.ridernotes.recording.SavedVoiceNote

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopicLibraryScreen(
    savedNotes: List<SavedVoiceNote>,
    onPlayRecording: (SavedVoiceNote) -> Unit,
    onStartRecording: (String, Int?) -> Unit,
    onStopRecording: () -> Unit,
    isRecording: Boolean,
    recordingStartedAtElapsedMs: Long?,
    playingFileName: String?,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    playbackPaused: Boolean,
    onSeekRecording: (Long) -> Unit,
    onStopPlayback: () -> Unit,
    onRefreshNotes: () -> Unit,
    onGoToOriginalNote: (SavedVoiceNote) -> Unit
) {
    val context = LocalContext.current
    var library by remember { mutableStateOf(TopicLibraryRepository.load(context)) }
    var currentGroupId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var inputTitle by remember { mutableStateOf("") }
    var inputBody by remember { mutableStateOf("") }
    var inputNotes by remember { mutableStateOf("") }
    var pendingAttachmentNotes by remember { mutableStateOf<String?>(null) }
    var insertionIndex by remember { mutableIntStateOf(-1) }
    var actionPlacement by remember { mutableStateOf<TopicPlacement?>(null) }
    var linkItemId by remember { mutableStateOf<String?>(null) }
    var linkPickerGroupId by remember { mutableStateOf<String?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<TopicGroup?>(null) }
    var expandedPlacements by remember { mutableStateOf<Set<String>>(emptySet()) }
    var colorPlacement by remember { mutableStateOf<TopicPlacement?>(null) }
    var legendExpanded by remember { mutableStateOf(false) }
    var legendColor by remember { mutableStateOf<String?>(null) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var recordingElapsedSeconds by remember { mutableStateOf(0L) }
    var filterColor by remember { mutableStateOf<String?>(null) }
    var filterStarred by remember { mutableStateOf(false) }
    var showFilterColorDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var toolsExpanded by remember { mutableStateOf(false) }
    var selectedPlacementIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingPointDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }
    var editBody by remember { mutableStateOf(TextFieldValue("")) }
    var editBold by remember { mutableStateOf(false) }
    var editItalic by remember { mutableStateOf(false) }
    var editUnderline by remember { mutableStateOf(false) }
    var editStrike by remember { mutableStateOf(false) }
    var editBullets by remember { mutableStateOf(false) }
    var editFont by remember { mutableStateOf<FontFamily?>(null) }
    var editTextColor by remember { mutableStateOf(Color.Unspecified) }

    LaunchedEffect(isRecording, recordingStartedAtElapsedMs) {
        if (!isRecording || recordingStartedAtElapsedMs == null) {
            recordingElapsedSeconds = 0L
        } else {
            while (true) {
                recordingElapsedSeconds = (
                    SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs
                ).coerceAtLeast(0L) / 1_000L
                delay(250L)
            }
        }
    }

    LaunchedEffect(currentGroupId) {
        selectedPlacementIds = emptySet()
    }

    fun refresh() { library = TopicLibraryRepository.load(context) }
    fun resetInput() { inputTitle = ""; inputBody = ""; inputNotes = ""; editBody = TextFieldValue(""); insertionIndex = -1; dialog = null }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val groupId = currentGroupId
        if (uri != null && groupId != null) {
            TopicLibraryRepository.addAttachment(
                context,
                groupId,
                uri,
                insertionIndex.takeIf { it >= 0 },
                pendingAttachmentNotes
            )
            refresh()
        }
        pendingAttachmentNotes = null
        insertionIndex = -1
    }
    val replacementPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val itemId = editingItemId
        if (uri != null && itemId != null) {
            TopicLibraryRepository.replaceAttachment(context, itemId, uri)
            refresh()
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreStatus = TopicLibraryRepository.restoreBackup(context, uri)
            onRefreshNotes()
            refresh()
        }
    }

    val currentGroup = library.groups.firstOrNull { it.id == currentGroupId }
    val itemById = library.items.associateBy { it.id }
    val matchingPlacements = library.placements.filter { placement ->
        val item = itemById[placement.itemId]
        val linkedNote = item?.target?.let { target ->
            savedNotes.firstOrNull { it.audioFile.name == target }
        }
        (filterColor == null || placement.color == filterColor) &&
            (!filterStarred || placement.important) &&
            (searchQuery.isBlank() || topicSearchMatches(item, linkedNote, searchQuery))
    }
    val relevantGroupIds = matchingPlacements.mapTo(mutableSetOf()) { it.groupId }.apply {
        var changed: Boolean
        do {
            changed = false
            library.groups.filter { it.id in this }.mapNotNull { it.parentId }.forEach { parent ->
                if (add(parent)) changed = true
            }
        } while (changed)
    }
    val filtering = filterColor != null || filterStarred || searchQuery.isNotBlank()
    val childGroups = library.groups
        .filter { it.parentId == currentGroupId && (!filtering || it.id in relevantGroupIds) }
        .sortedBy { it.order }
    val allCurrentPlacements = currentGroupId?.let { id ->
        library.placements.filter { it.groupId == id }.sortedBy { it.order }
    }.orEmpty()
    val placements = if (searchQuery.isNotBlank()) {
        matchingPlacements.sortedByDescending { placement ->
            itemById[placement.itemId]?.createdAt ?: 0L
        }
    } else currentGroupId?.let { id ->
        matchingPlacements.filter { it.groupId == id }.sortedBy { it.order }
    }.orEmpty()

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

    editingItemId?.let { itemId ->
        val item = library.items.firstOrNull { it.id == itemId }
        if (item != null) {
            AlertDialog(
                onDismissRequest = { editingItemId = null },
                title = { Text("Edit Topic point") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text("Heading") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (item.type == TopicLibraryRepository.TYPE_LINK) {
                            OutlinedTextField(
                                value = editUrl,
                                onValueChange = { editUrl = it },
                                label = { Text("Link URL") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        MobileRichTextField(
                            value = editBody,
                            onValueChange = { editBody = it },
                            label = if (item.type == TopicLibraryRepository.TYPE_TEXT) "Text" else "Additional text"
                        )
                        if (item.type == TopicLibraryRepository.TYPE_ATTACHMENT) {
                            OutlinedButton(
                                onClick = { replacementPicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Replace attachment") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        TopicLibraryRepository.updateEditablePoint(
                            context = context,
                            itemId = item.id,
                            title = editTitle,
                            body = editBody.text,
                            linkUrl = editUrl,
                            richText = TopicRichText.encode(editBody)
                        )
                        editingItemId = null
                        refresh()
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editingItemId = null }) { Text("Cancel") } }
            )
        }
    }

    if (pendingPointDeleteIds.isNotEmpty()) {
        val deletesTopicRecording = pendingPointDeleteIds.any {
            TopicLibraryRepository.willDeleteTopicRecording(context, it)
        }
        AlertDialog(
            onDismissRequest = { pendingPointDeleteIds = emptySet() },
            title = { Text("Delete selected Topic point(s)?") },
            text = {
                Text(
                    "Remove ${pendingPointDeleteIds.size} selected point(s) from this Topic? " +
                        (if (deletesTopicRecording) {
                            "A Topic-only recording is permanently deleted when this is its final placement."
                        } else {
                            "Original media notes and points linked in other Topic folders remain available."
                        })
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TopicLibraryRepository.removePlacements(context, pendingPointDeleteIds)
                    selectedPlacementIds = selectedPlacementIds - pendingPointDeleteIds
                    pendingPointDeleteIds = emptySet()
                    onRefreshNotes()
                    refresh()
                }) { Text("Yes, delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPointDeleteIds = emptySet() }) { Text("No") }
            }
        )
    }

    if (dialog == "group") InputDialog("New group", "Group name", inputTitle, { inputTitle = it }, ::resetInput) {
        if (inputTitle.isNotBlank()) TopicLibraryRepository.createGroup(context, inputTitle, currentGroupId)
        resetInput(); refresh()
    }
    if (dialog == "text") {
        AlertDialog(
            onDismissRequest = ::resetInput,
            title = { Text("New text point") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    OutlinedTextField(
                        value = inputTitle,
                        onValueChange = { inputTitle = it },
                        label = { Text("Heading") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    MobileRichTextField(
                        value = editBody,
                        onValueChange = { editBody = it },
                        label = "Text"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editBody.text.isNotBlank(),
                    onClick = {
                        currentGroupId?.let {
                            TopicLibraryRepository.addText(
                                context = context,
                                groupId = it,
                                title = inputTitle,
                                text = editBody.text,
                                index = insertionIndex.takeIf { value -> value >= 0 },
                                richText = TopicRichText.encode(editBody)
                            )
                        }
                        resetInput()
                        refresh()
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = ::resetInput) { Text("Cancel") } }
        )
    }
    if (dialog == "link") ThreeInputDialog(
        "New web link", "Title", "https://…", "Notes about this link (optional)",
        inputTitle, inputBody, inputNotes,
        { inputTitle = it }, { inputBody = it }, { inputNotes = it }, ::resetInput
    ) {
        currentGroupId?.let {
            TopicLibraryRepository.addLink(
                context, it, inputTitle, inputBody,
                insertionIndex.takeIf { value -> value >= 0 }, inputNotes
            )
        }
        resetInput(); refresh()
    }
    if (dialog == "attachmentNote") InputDialog(
        "Attachment notes", "Notes about this file or image (optional)", inputNotes,
        { inputNotes = it }, ::resetInput
    ) {
        pendingAttachmentNotes = inputNotes
        dialog = null
        filePicker.launch(arrayOf("*/*"))
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
        val selectedFolder = library.groups.firstOrNull { it.id == linkPickerGroupId }
        val visibleFolders = library.groups
            .filter { it.parentId == linkPickerGroupId }
            .sortedBy { it.order }
        val alreadyLinkedHere = selectedFolder?.let { folder ->
            library.placements.any {
                it.itemId == linkItemId && it.groupId == folder.id
            }
        } == true
        AlertDialog(
            onDismissRequest = { linkItemId = null; linkPickerGroupId = null },
            title = {
                Text(selectedFolder?.let { groupPath(library, it) } ?: "Choose destination folder")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selectedFolder != null) {
                        Button(
                            onClick = {
                                TopicLibraryRepository.linkItem(
                                    context,
                                    linkItemId!!,
                                    selectedFolder.id
                                )
                                linkItemId = null
                                linkPickerGroupId = null
                                refresh()
                            },
                            enabled = !alreadyLinkedHere,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (alreadyLinkedHere) "Already linked here" else "Link to this folder")
                        }
                    }
                    LazyColumn {
                        items(visibleFolders, key = { it.id }) { group ->
                            TextButton(
                                onClick = { linkPickerGroupId = group.id },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("📁 ${group.name}") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { linkItemId = null; linkPickerGroupId = null }) {
                    Text("Cancel")
                }
            },
            dismissButton = {
                if (selectedFolder != null) {
                    TextButton(onClick = { linkPickerGroupId = selectedFolder.parentId }) {
                        Text("← Back")
                    }
                }
            }
        )
    }
    actionPlacement?.let { placement ->
        val pointIndex = allCurrentPlacements.indexOfFirst { it.id == placement.id }
        val actionItem = itemById[placement.itemId]
        val originalNote = actionItem?.target?.let { target ->
            savedNotes.firstOrNull { it.audioFile.name == target && !it.topicOnly }
        }
        AlertDialog(onDismissRequest = { actionPlacement = null }, title = { Text("Point options") }, text = {
            Column {
                TextButton(onClick = { TopicLibraryRepository.setImportant(context, placement.id, !placement.important); actionPlacement = null; refresh() }) {
                    Text(if (placement.important) "Remove important star" else "Mark as important")
                }
                TextButton(onClick = { colorPlacement = placement; actionPlacement = null }) { Text("Set colour") }
                TextButton(onClick = { TopicLibraryRepository.move(context, placement.id, -1); actionPlacement = null; refresh() }) { Text("Move up") }
                TextButton(onClick = { TopicLibraryRepository.move(context, placement.id, 1); actionPlacement = null; refresh() }) { Text("Move down") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; editBody = TextFieldValue(""); dialog = "text"; actionPlacement = null }) { Text("Insert text after this point") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; dialog = "link"; actionPlacement = null }) { Text("Insert web link after this point") }
                TextButton(onClick = {
                    insertionIndex = pointIndex + 1
                    inputNotes = ""
                    dialog = "attachmentNote"
                    actionPlacement = null
                }) { Text("Insert file after this point") }
                TextButton(onClick = { insertionIndex = pointIndex + 1; dialog = "recordings"; actionPlacement = null }) { Text("Insert existing recording after this point") }
                TextButton(onClick = { onStartRecording(placement.groupId, pointIndex + 1); actionPlacement = null }) { Text("Record after this point") }
                TextButton(onClick = {
                    linkPickerGroupId = null
                    linkItemId = placement.itemId
                    actionPlacement = null
                }) { Text("Link this point to another group") }
                TextButton(onClick = {
                    val editable = actionItem ?: return@TextButton
                    editingItemId = editable.id
                    editTitle = editable.title
                    editUrl = editable.target.takeIf { editable.type == TopicLibraryRepository.TYPE_LINK }.orEmpty()
                    val body = if (editable.type == TopicLibraryRepository.TYPE_TEXT) editable.text.orEmpty()
                    else editable.additionalNotes ?: editable.text.orEmpty()
                    editBody = TopicRichText.decode(body, editable.richText)
                    editBold = false; editItalic = false; editUnderline = false; editStrike = false
                    editBullets = false; editFont = null; editTextColor = Color.Unspecified
                    actionPlacement = null
                }) {
                    Text(
                        when (actionItem?.type) {
                            TopicLibraryRepository.TYPE_LINK -> "Edit heading, link and text"
                            TopicLibraryRepository.TYPE_ATTACHMENT -> "Edit heading, text or attachment"
                            else -> "Edit heading and text"
                        }
                    )
                }
                TextButton(
                    enabled = originalNote != null,
                    onClick = {
                        originalNote?.let(onGoToOriginalNote)
                        actionPlacement = null
                    }
                ) { Text("Go to original note") }
                val deletesTopicRecording = TopicLibraryRepository.willDeleteTopicRecording(
                    context,
                    placement.id
                )
                TextButton(onClick = {
                    pendingPointDeleteIds = setOf(placement.id)
                    actionPlacement = null
                }) {
                    Text(
                        if (deletesTopicRecording) {
                            "Delete this topic recording permanently"
                        } else {
                            "Remove from this group"
                        }
                    )
                }
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

    if (showFilterColorDialog) {
        AlertDialog(
            onDismissRequest = { showFilterColorDialog = false },
            title = { Text("Filter Topics by colour") },
            text = {
                LazyColumn {
                    items(topicColors) { color ->
                        val meaning = if (color == "WHITE") "Nothing special"
                        else library.colorLegend[color] ?: "Not defined"
                        TextButton(onClick = {
                            filterColor = color
                            currentGroupId = null
                            showFilterColorDialog = false
                        }) {
                            Text("${color.lowercase().replaceFirstChar { it.uppercase() }} — $meaning")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterColorDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (currentGroup != null) OutlinedButton(onClick = { currentGroupId = currentGroup.parentId }) { Text("← Back", fontSize = 11.sp) }
            Text(
                currentGroup?.let { groupPath(library, it) } ?: "Topic groups",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { toolsExpanded = !toolsExpanded }) {
                Text(if (toolsExpanded) "Hide topic tools ▲" else "Topic tools ▼", fontSize = 11.sp)
            }
            if (!toolsExpanded && selectedPlacementIds.isNotEmpty()) {
                Text(
                    "${selectedPlacementIds.size} selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!toolsExpanded && filtering) {
                Text(
                    "Filter active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (toolsExpanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search Topics (* wildcard)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { inputTitle = ""; dialog = "group" }) {
                            Text(if (currentGroup == null) "+ Group" else "+ Subgroup", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { onRefreshNotes(); refresh() }) {
                            Text("Refresh", fontSize = 11.sp)
                        }
                        if (currentGroup == null) {
                            OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                                Text("Restore backup", fontSize = 11.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { addMenuExpanded = true }) {
                                Text("＋ Add point", fontSize = 11.sp)
                            }
                        }
                    }
                    if (currentGroup != null) {
                        TextButton(onClick = { legendExpanded = true }) {
                            Text("🎨 Legend", fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showFilterColorDialog = true }) {
                            Text(
                                filterColor?.let { "🎨 ${it.lowercase().replaceFirstChar { value -> value.uppercase() }}" }
                                    ?: "🎨 Filter colour",
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(onClick = {
                            filterStarred = !filterStarred
                            currentGroupId = null
                        }) {
                            Text(if (filterStarred) "★ Starred only" else "☆ Filter starred", fontSize = 11.sp)
                        }
                    }
                    if (filtering) {
                        TextButton(onClick = {
                            filterColor = null
                            filterStarred = false
                            currentGroupId = null
                        }) { Text("Clear filters", fontSize = 11.sp) }
                        Text(
                            "Filtered view: folders show only matching points. Edits apply immediately.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (currentGroup != null) {
                        Text(
                            "Selected: ${selectedPlacementIds.size}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                enabled = placements.isNotEmpty() && selectedPlacementIds.size != placements.size,
                                onClick = { selectedPlacementIds = placements.mapTo(mutableSetOf()) { it.id } }
                            ) { Text("Select all", fontSize = 11.sp) }
                            OutlinedButton(
                                enabled = selectedPlacementIds.isNotEmpty(),
                                onClick = { selectedPlacementIds = emptySet() }
                            ) { Text("Clear", fontSize = 11.sp) }
                            Button(
                                enabled = selectedPlacementIds.isNotEmpty(),
                                onClick = { pendingPointDeleteIds = selectedPlacementIds }
                            ) { Text("Delete selected", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        if (currentGroup != null && isRecording) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE1E1))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (recordingStartedAtElapsedMs == null) {
                            "🔴 🎙 Preparing topic recording…"
                        } else {
                            "🔴 🎙 RECORDING  ${formatTopicTime(recordingElapsedSeconds * 1_000L)}"
                        },
                        color = Color(0xFFB00020),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = onStopRecording, modifier = Modifier.fillMaxWidth()) {
                        Text("■ Stop topic recording")
                    }
                }
            }
        }
        restoreStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(childGroups, key = { "group-${it.id}" }) { group ->
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { currentGroupId = group.id }, onLongClick = { deleteGroupTarget = group })) {
                    Text("📁 ${group.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
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
                if (searchQuery.isNotBlank()) {
                    val group = library.groups.firstOrNull { it.id == placement.groupId }
                    TextButton(onClick = {
                        currentGroupId = placement.groupId
                        searchQuery = ""
                    }) {
                        Text("Go to folder: ${group?.let { groupPath(library, it) }.orEmpty()}", fontSize = 11.sp)
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = topicCardColor(placement.color),
                        contentColor = if (placement.color == "WHITE") MaterialTheme.colorScheme.onSurface else Color(0xFF1B1B1B)
                    ),
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { expandedPlacements = if (expanded) expandedPlacements - placement.id else expandedPlacements + placement.id },
                    onLongClick = { actionPlacement = placement }
                )) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = placement.id in selectedPlacementIds,
                                onCheckedChange = { checked ->
                                    selectedPlacementIds = if (checked) {
                                        selectedPlacementIds + placement.id
                                    } else {
                                        selectedPlacementIds - placement.id
                                    }
                                }
                            )
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${placement.order + 1}. ${item.title}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    textDecoration = if (originalMissing) TextDecoration.LineThrough else TextDecoration.None,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (placement.important) Text("★", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                        }
                        if (originalMissing) Text("Original content is missing — restore it from backup to reactivate this point.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        if (expanded) {
                            val displayBody = if (item.type == TopicLibraryRepository.TYPE_TEXT) item.text
                            else item.additionalNotes ?: item.text
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("Topic heading", item.title))
                                }) { Text("Copy heading", fontSize = 11.sp) }
                                displayBody?.takeIf(String::isNotBlank)?.let { body ->
                                    OutlinedButton(onClick = {
                                        context.getSystemService(android.content.ClipboardManager::class.java)
                                            .setPrimaryClip(android.content.ClipData.newPlainText("Topic text", body))
                                    }) { Text("Copy text", fontSize = 11.sp) }
                                }
                            }
                            Text(item.type.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
                            displayBody?.takeIf(String::isNotBlank)?.let { body ->
                                if (item.type != TopicLibraryRepository.TYPE_TEXT) {
                                Text("Additional notes", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                                SelectionContainer {
                                    Text(TopicRichText.decode(body, item.richText).annotatedString, fontSize = 12.sp)
                                }
                            }
                            item.target?.takeIf { item.type == TopicLibraryRepository.TYPE_LINK }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            if (item.type == TopicLibraryRepository.TYPE_ATTACHMENT && attachment?.exists() == true && isImageFile(attachment)) {
                                val bitmap = remember(attachment.absolutePath, attachment.lastModified()) {
                                    decodeThumbnail(attachment)
                                }
                                bitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = item.title,
                                        modifier = Modifier.size(96.dp)
                                    )
                                }
                            }
                            note?.let {
                                Text("Source: ${it.sourceApp}", fontSize = 12.sp)
                                it.module?.let { value -> Text("Module: $value", fontSize = 12.sp) }
                                it.chapter?.let { value -> Text("Chapter: $value", fontSize = 12.sp) }
                                it.author?.let { value -> Text("Author / creator: $value", fontSize = 12.sp) }
                                it.playbackPositionLabel?.let { value -> Text("Media time: $value", fontSize = 12.sp) }
                                Text("Recorded: ${it.recordedAtLabel}", fontSize = 12.sp)
                                Text(it.transcript ?: "Transcript not available", fontSize = 12.sp)
                            }
                            when (item.type) {
                                TopicLibraryRepository.TYPE_RECORDING -> {
                                    val isPlaying = note?.audioFile?.name == playingFileName
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(enabled = note != null && !originalMissing, onClick = { note?.let(onPlayRecording) }) {
                                            Text(if (!isPlaying) "Play recording" else if (playbackPaused) "Resume" else "Pause")
                                        }
                                        if (isPlaying) OutlinedButton(onClick = onStopPlayback) { Text("Stop") }
                                    }
                                    if (isPlaying && playbackDurationMs > 0L) {
                                        Slider(
                                            value = playbackPositionMs.toFloat(),
                                            onValueChange = { onSeekRecording(it.toLong()) },
                                            valueRange = 0f..playbackDurationMs.toFloat()
                                        )
                                        Text("${formatTopicTime(playbackPositionMs)} / ${formatTopicTime(playbackDurationMs)}")
                                    }
                                }
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
            if (currentGroup == null && childGroups.isEmpty()) item {
                Text(if (filtering) "No Topic points match this filter." else "Create your first topic group, for example Biology.")
            }
        }
    }

    if (addMenuExpanded && currentGroup != null) {
        AlertDialog(
            onDismissRequest = { addMenuExpanded = false },
            title = { Text("Add point") },
            text = {
                Column {
                    TextButton(onClick = { addMenuExpanded = false; editBody = TextFieldValue(""); dialog = "text" }) { Text("Text") }
                    TextButton(onClick = { addMenuExpanded = false; dialog = "link" }) { Text("Web link") }
                    TextButton(onClick = {
                        addMenuExpanded = false
                        inputNotes = ""
                        dialog = "attachmentNote"
                    }) { Text("File or image") }
                    TextButton(onClick = { addMenuExpanded = false; dialog = "recordings" }) { Text("Existing recording") }
                    TextButton(onClick = { addMenuExpanded = false; onStartRecording(currentGroup.id, null) }) { Text("New recording") }
                }
            },
            confirmButton = { TextButton(onClick = { addMenuExpanded = false }) { Text("Cancel") } }
        )
    }

    if (legendExpanded) {
        AlertDialog(
            onDismissRequest = { legendExpanded = false },
            title = { Text("Colour legend") },
            text = {
                LazyColumn {
                    items(topicColors) { color ->
                        val meaning = if (color == "WHITE") "Nothing special" else library.colorLegend[color] ?: "Not defined"
                        if (color == "WHITE") Text("White: $meaning", modifier = Modifier.padding(12.dp))
                        else TextButton(onClick = {
                            legendExpanded = false
                            legendColor = color
                            inputBody = library.colorLegend[color].orEmpty()
                        }) { Text("${color.lowercase().replaceFirstChar { it.uppercase() }}: $meaning") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { legendExpanded = false }) { Text("Close") } }
        )
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


@Composable
private fun MobileRichTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String
) {
    var bold by remember { mutableStateOf(false) }
    var italic by remember { mutableStateOf(false) }
    var underline by remember { mutableStateOf(false) }
    var strike by remember { mutableStateOf(false) }
    var bullets by remember { mutableStateOf(false) }
    var font by remember { mutableStateOf<FontFamily?>(null) }
    var textColor by remember { mutableStateOf(Color.Unspecified) }

    OutlinedTextField(
        value = value,
        onValueChange = { proposed ->
            val hasTypingStyle = bold || italic || underline || strike ||
                font != null || textColor != Color.Unspecified
            val decoration = when {
                underline && strike -> TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                )
                underline -> TextDecoration.Underline
                strike -> TextDecoration.LineThrough
                else -> null
            }
            val typingStyle = if (hasTypingStyle) {
                SpanStyle(
                    fontWeight = FontWeight.Bold.takeIf { bold },
                    fontStyle = FontStyle.Italic.takeIf { italic },
                    textDecoration = decoration,
                    fontFamily = font,
                    color = textColor
                )
            } else null
            var next = TopicRichText.reconcileEdit(value, proposed, typingStyle)
            next = TopicRichText.continueBullets(value, next, bullets)
            onValueChange(next)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
    Text("Select text, then format:", style = MaterialTheme.typography.labelSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = {
            val enabled = if (value.selection.collapsed) !bold else !TopicRichText.selectionIsBold(value)
            if (!value.selection.collapsed) onValueChange(TopicRichText.setBold(value, enabled))
            bold = enabled
        }) { Text(if (bold) "✓B" else "B", fontWeight = FontWeight.Bold) }
        TextButton(onClick = {
            val enabled = if (value.selection.collapsed) !italic else !TopicRichText.selectionIsItalic(value)
            if (!value.selection.collapsed) onValueChange(TopicRichText.setItalic(value, enabled))
            italic = enabled
        }) { Text(if (italic) "✓I" else "I", fontStyle = FontStyle.Italic) }
        TextButton(onClick = {
            val enabled = if (value.selection.collapsed) !underline else !TopicRichText.selectionIsUnderlined(value)
            if (!value.selection.collapsed) onValueChange(TopicRichText.setUnderline(value, enabled))
            underline = enabled
        }) { Text(if (underline) "✓U" else "U", textDecoration = TextDecoration.Underline) }
        TextButton(onClick = {
            val enabled = if (value.selection.collapsed) !strike else !TopicRichText.selectionIsStruck(value)
            if (!value.selection.collapsed) onValueChange(TopicRichText.setStrike(value, enabled))
            strike = enabled
        }) { Text(if (strike) "✓S" else "S", textDecoration = TextDecoration.LineThrough) }
        TextButton(onClick = {
            bullets = !bullets
            if (bullets) onValueChange(TopicRichText.bullets(value))
        }) { Text(if (bullets) "✓•" else "•") }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TextButton(onClick = {
            font = FontFamily.Serif
            onValueChange(TopicRichText.apply(value, SpanStyle(fontFamily = font)))
        }) { Text(if (font == FontFamily.Serif) "✓S" else "S", fontFamily = FontFamily.Serif) }
        TextButton(onClick = {
            font = FontFamily.Monospace
            onValueChange(TopicRichText.apply(value, SpanStyle(fontFamily = font)))
        }) { Text(if (font == FontFamily.Monospace) "✓M" else "M", fontFamily = FontFamily.Monospace) }
        TextButton(onClick = {
            font = FontFamily.Cursive
            onValueChange(TopicRichText.apply(value, SpanStyle(fontFamily = font)))
        }) { Text(if (font == FontFamily.Cursive) "✓𝒮" else "𝒮", fontFamily = FontFamily.Cursive) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            Color.Black,
            Color(0xFFC62828),
            Color(0xFF1565C0),
            Color(0xFF2E7D32),
            Color(0xFF6A1B9A)
        ).forEach { color ->
            Surface(
                onClick = {
                    textColor = color
                    onValueChange(TopicRichText.apply(value, SpanStyle(color = color)))
                },
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = color,
                border = BorderStroke(2.dp, Color.White)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (textColor == color) Text("✓", color = Color.White)
                }
            }
        }
    }
}

@Composable private fun InputDialog(title: String, label: String, value: String, onChange: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) =
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = { OutlinedTextField(value, onChange, label = { Text(label) }) }, confirmButton = { TextButton(onClick = onSave) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })

@Composable private fun TwoInputDialog(title: String, firstLabel: String, secondLabel: String, first: String, second: String, onFirst: (String) -> Unit, onSecond: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) =
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = { Column { OutlinedTextField(first, onFirst, label = { Text(firstLabel) }); Spacer(Modifier.height(8.dp)); OutlinedTextField(second, onSecond, label = { Text(secondLabel) }) } }, confirmButton = { TextButton(onClick = onSave) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })

@Composable private fun ThreeInputDialog(
    title: String,
    firstLabel: String,
    secondLabel: String,
    thirdLabel: String,
    first: String,
    second: String,
    third: String,
    onFirst: (String) -> Unit,
    onSecond: (String) -> Unit,
    onThird: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(title) },
    text = {
        Column {
            OutlinedTextField(first, onFirst, label = { Text(firstLabel) })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(second, onSecond, label = { Text(secondLabel) })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(third, onThird, label = { Text(thirdLabel) })
        }
    },
    confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
    dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
)

private fun groupPath(library: TopicLibrary, group: TopicGroup): String {
    val names = mutableListOf(group.name)
    var parent = group.parentId
    while (parent != null) {
        val value = library.groups.firstOrNull { it.id == parent } ?: break
        names.add(0, value.name); parent = value.parentId
    }
    return names.joinToString(" › ")
}

private fun isImageFile(file: java.io.File): Boolean =
    file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

private fun decodeThumbnail(file: java.io.File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    )
}

private fun formatTopicTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun topicSearchMatches(item: TopicItem?, note: SavedVoiceNote?, query: String): Boolean {
    val searchable = listOfNotNull(
        item?.title,
        item?.text,
        item?.additionalNotes,
        item?.target,
        note?.sourceApp,
        note?.title,
        note?.module,
        note?.chapter,
        note?.author,
        note?.transcript
    ).joinToString("\n")
    return wildcardSearchMatches(query, searchable)
}

private fun wildcardSearchMatches(query: String, text: String): Boolean {
    val parts = query.trim().split('*').filter(String::isNotBlank)
    if (parts.isEmpty()) return true
    var from = 0
    parts.forEach { part ->
        val found = text.indexOf(part, startIndex = from, ignoreCase = true)
        if (found < 0) return false
        from = found + part.length
    }
    return true
}
