package com.praveen.ridernotes.desktop

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDeltaCloudTransportTest {
    @Test
    fun ordinaryPublishLeavesCheckpointUntouchedAndReloadsDeltaOverlay() {
        val cloud = createTempDirectory("rider-delta-cloud-").toFile()
        val zip = File(cloud, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[{"id":"g1","name":"Old name","parentId":null,"order":0}],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry(
                "recordings/n1.json",
                """{"recordedAtEpochMs":1,"title":"Test","sourceApp":"Standalone"}"""
            )
            output.entry("recordings/n1.m4a", "audio")
        }
        val checkpointHash = sha256(zip)
        val opened = BackupImporter.import(zip)
        val changed = BackupEditor.saveLibrary(
            opened,
            groups = opened.groups.map { it.copy(name = "New name") }
        )

        BackupPublisher.publishDetailed(changed)

        assertEquals(checkpointHash, sha256(zip), "ordinary sync must not rewrite the full ZIP")
        assertTrue(File(cloud, DesktopDeltaCloudTransport.INDEX_FILE).isFile)
        assertTrue(
            File(cloud, DesktopDeltaCloudTransport.OBJECT_DIRECTORY)
                .listFiles().orEmpty().isEmpty(),
            "the initial index must reuse identical files already in the checkpoint"
        )
        val reloaded = BackupImporter.import(zip)
        assertEquals("New name", reloaded.groups.single().name)
        assertTrue(reloaded.notes.single().audioFile?.isFile == true)
        val metadataOnlyChange = BackupEditor.saveLibrary(
            reloaded,
            groups = reloaded.groups.map { it.copy(name = "Newest name") }
        )
        BackupPublisher.publishDetailed(metadataOnlyChange)
        assertTrue(
            File(cloud, DesktopDeltaCloudTransport.OBJECT_DIRECTORY)
                .listFiles().orEmpty().isEmpty(),
            "a metadata-only edit must not duplicate baseline recordings"
        )
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
