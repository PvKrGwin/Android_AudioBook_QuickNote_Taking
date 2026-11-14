package com.praveen.ridernotes.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupEditingTest {
    @Test
    fun editsTopicsAddsTypedNoteAndPublishesRevision() {
        val temp = createTempDir(prefix = "rider-notes-pc-edit-")
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
        }

        var snapshot = BackupImporter.import(zip)
        snapshot = BackupEditor.createGroup(snapshot, "Biology", null)
        val group = snapshot.groups.single()
        snapshot = BackupEditor.addTextPoint(snapshot, group.id, "Cells", "Mitochondria")
        snapshot = BackupEditor.addTypedMediaNote(
            snapshot,
            CapturedMedia("Coursera", "coursera", "Cell structure", "University", 12_000L),
            "Compare prokaryotic and eukaryotic cells"
        )
        val published = BackupPublisher.publish(snapshot)

        assertEquals("Biology", published.groups.single().name)
        assertEquals("Cells", published.items.single().title)
        assertTrue(published.notes.single().textOnly)
        assertNotNull(published.syncManifest)
        assertTrue(File(temp, SyncManifestCodec.SIBLING_FILE_NAME).isFile)
        ZipFile(zip).use { archive ->
            assertNotNull(archive.getEntry(SyncManifestCodec.FILE_NAME))
            assertNotNull(archive.getEntry("recordings/${published.notes.single().id}.json"))
        }
    }

    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
