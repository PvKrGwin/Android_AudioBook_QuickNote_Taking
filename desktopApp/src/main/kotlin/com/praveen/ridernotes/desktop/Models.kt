package com.praveen.ridernotes.desktop

import java.io.File

data class DesktopNote(
    val id: String,
    val title: String,
    val sourceApp: String,
    val module: String?,
    val chapter: String?,
    val author: String?,
    val recordedAtEpochMs: Long,
    val playbackPositionMs: Long?,
    val sourceUrl: String?,
    val transcriptionStatus: String,
    val transcript: String?,
    val audioFile: File?,
    val topicOnly: Boolean,
    val textOnly: Boolean = false,
    val typedNote: String? = null
)

data class SyncManifest(
    val schemaVersion: Int = 1,
    val revisionId: String,
    val parentRevisionId: String?,
    val modifiedAtEpochMs: Long,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String
)

data class DesktopTopicGroup(
    val id: String,
    val name: String,
    val parentId: String?,
    val order: Int
)

data class DesktopTopicItem(
    val id: String,
    val type: String,
    val title: String,
    val text: String?,
    val target: String?,
    val createdAt: Long
)

data class DesktopTopicPlacement(
    val id: String,
    val groupId: String,
    val itemId: String,
    val order: Int,
    val important: Boolean,
    val color: String
)

data class BackupSnapshot(
    val sourceZip: File,
    val extractedRoot: File,
    val libraryRoot: File,
    val notes: List<DesktopNote>,
    val groups: List<DesktopTopicGroup>,
    val items: List<DesktopTopicItem>,
    val placements: List<DesktopTopicPlacement>,
    val colorLegend: Map<String, String>,
    val syncManifest: SyncManifest?
)
