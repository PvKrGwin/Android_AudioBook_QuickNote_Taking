package com.praveen.ridernotes.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class BackupEditingTest {
    @Test
    fun editsTopicsAddsTypedNoteAndPublishesRevision() {
        val temp = createTempDirectory("rider-notes-pc-edit-").toFile()
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
            CapturedMedia(
                sourceApp = "Coursera",
                sourcePackage = "coursera",
                title = "Cell Biology",
                author = "University",
                playbackPositionMs = 12_000L,
                module = "Module 2",
                chapter = "Cell structure"
            ),
            "Compare prokaryotic and eukaryotic cells"
        )
        val published = BackupPublisher.publish(snapshot)

        assertEquals("Biology", published.groups.single().name)
        assertEquals("Cells", published.items.single().title)
        assertTrue(published.notes.single().textOnly)
        assertEquals("Cell Biology", published.notes.single().title)
        assertEquals("Module 2", published.notes.single().module)
        assertEquals("Cell structure", published.notes.single().chapter)
        assertEquals(DesktopDeviceIdentity.name(), published.notes.single().originDeviceName)
        assertNotNull(published.syncManifest)
        assertTrue(File(temp, SyncManifestCodec.SIBLING_FILE_NAME).isFile)
        ZipFile(zip).use { archive ->
            assertNotNull(archive.getEntry(SyncManifestCodec.FILE_NAME))
            assertNotNull(archive.getEntry("recordings/${published.notes.single().id}.json"))
            assertNotNull(archive.getEntry("recordings/${published.notes.single().id}.m4a"))
        }
    }

    @Test
    fun youtubeLinkBackfillsOlderNotesAndIsInheritedByFutureNotes() {
        val temp = createTempDirectory("rider-notes-youtube-links-").toFile()
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
            output.entry(
                "recordings/old.json",
                """{"recordedAtEpochMs":1,"title":"  Shared   Video ","sourceApp":"youtube","author":"My Channel","playbackPositionMs":10000}"""
            )
            output.entry("recordings/old.m4a", "")
        }

        var snapshot = BackupImporter.import(zip)
        snapshot = BackupEditor.addTypedMediaNote(
            snapshot,
            CapturedMedia(
                sourceApp = "YouTube",
                sourcePackage = "com.google.android.youtube",
                title = "Shared Video",
                author = "my channel",
                playbackPositionMs = 20_000L,
                sourceUrl = "https://youtu.be/AbCdEf12345?t=20"
            ),
            "Linked note"
        )

        assertEquals(2, snapshot.notes.size)
        assertTrue(snapshot.notes.all {
            it.sourceUrl == "https://www.youtube.com/watch?v=AbCdEf12345"
        })

        snapshot = BackupEditor.addTypedMediaNote(
            snapshot,
            CapturedMedia(
                sourceApp = "YouTube",
                sourcePackage = "com.google.android.youtube",
                title = "Shared Video",
                author = "My Channel",
                playbackPositionMs = 30_000L
            ),
            "Future note"
        )

        assertEquals(3, snapshot.notes.size)
        assertTrue(snapshot.notes.all {
            it.sourceUrl == "https://www.youtube.com/watch?v=AbCdEf12345"
        })
        assertEquals(
            "https://www.youtube.com/watch?v=AbCdEf12345&t=30s",
            DesktopYouTubeLinks.atPosition(
                snapshot.notes.first { it.typedNote == "Future note" }.sourceApp,
                snapshot.notes.first { it.typedNote == "Future note" }.sourceUrl,
                snapshot.notes.first { it.typedNote == "Future note" }.playbackPositionMs
            )
        )
    }

    @Test
    fun undoRestoresPreviousTopicEditAndLegend() {
        val temp = createTempDirectory("rider-notes-undo-").toFile()
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
        }

        var snapshot = BackupImporter.import(zip)
        snapshot = BackupEditor.createGroup(snapshot, "Physics", null)
        assertEquals("Physics", snapshot.groups.single().name)
        snapshot = DesktopUndoStore.restore(snapshot)
        assertTrue(snapshot.groups.isEmpty())

        snapshot = BackupEditor.createGroup(snapshot, "Biology", null)
        snapshot = BackupEditor.updateLegend(snapshot, "GREEN", "Verified")
        assertEquals("Verified", snapshot.colorLegend["GREEN"])
        snapshot = DesktopUndoStore.restore(snapshot)
        assertTrue(snapshot.colorLegend.isEmpty())
        assertEquals("Biology", snapshot.groups.single().name)
    }

    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
