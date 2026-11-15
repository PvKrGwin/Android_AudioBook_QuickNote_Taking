package com.praveen.ridernotes.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One safe, session-only checkpoint for the most recent PC edit. */
object DesktopUndoStore {
    private var checkpoint: File? = null
    private var sourceRoot: String? = null

    fun capture(snapshot: BackupSnapshot) {
        clear()
        val target = Files.createTempDirectory("rider-notes-undo-").toFile()
        copyTree(snapshot.extractedRoot, target, preferLinks = true)
        checkpoint = target
        sourceRoot = snapshot.extractedRoot.absolutePath
    }

    fun canUndo(snapshot: BackupSnapshot?): Boolean =
        snapshot != null && sourceRoot == snapshot.extractedRoot.absolutePath && checkpoint?.isDirectory == true

    fun restore(snapshot: BackupSnapshot): BackupSnapshot {
        require(canUndo(snapshot)) { "There is no PC edit to undo" }
        val saved = requireNotNull(checkpoint)
        snapshot.extractedRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        copyTree(saved, snapshot.extractedRoot, preferLinks = false)
        clear()
        return BackupImporter.reload(snapshot)
    }

    fun clear() {
        checkpoint?.deleteRecursively()
        checkpoint = null
        sourceRoot = null
    }

    private fun copyTree(source: File, target: File, preferLinks: Boolean) {
        source.walkTopDown().forEach { input ->
            val relative = input.relativeTo(source).path
            val output = if (relative.isEmpty()) target else File(target, relative)
            if (input.isDirectory) {
                output.mkdirs()
            } else {
                output.parentFile?.mkdirs()
                val mutableMetadata = input.extension.lowercase() in setOf("json", "txt", "vtt", "srt")
                val linked = preferLinks && !mutableMetadata && runCatching {
                    Files.createLink(output.toPath(), input.toPath())
                    true
                }.getOrDefault(false)
                if (!linked) {
                    Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
