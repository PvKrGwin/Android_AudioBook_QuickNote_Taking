package com.praveen.ridernotes.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class BackupEditingTest {
    @Test
    fun publishMirrorsFullZipAndRevisionToSecondaryCloud() {
        val temp = createTempDirectory("rider-notes-dual-cloud-").toFile()
        val primary = File(temp, "primary/RiderNotes_Full_Backup.zip")
        primary.parentFile.mkdirs()
        ZipOutputStream(primary.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
        }
        val secondary = File(temp, "secondary/RiderNotes_Full_Backup.zip")
        val previousSecondary = DesktopDeviceIdentity.secondaryBackupPath()
        try {
            DesktopDeviceIdentity.rememberSecondaryBackupPath(secondary.absolutePath)
            val result = BackupPublisher.publishDetailed(BackupImporter.import(primary))

            assertTrue(result.secondaryMirrored)
            assertEquals(null, result.secondaryError)
            assertTrue(secondary.isFile)
            assertTrue(File(secondary.parentFile, SyncManifestCodec.SIBLING_FILE_NAME).isFile)
            ZipFile(secondary).use { archive ->
                assertNotNull(archive.getEntry(SyncManifestCodec.FILE_NAME))
                assertNotNull(archive.getEntry("library.json"))
            }
        } finally {
            DesktopDeviceIdentity.rememberSecondaryBackupPath(previousSecondary)
        }
    }

    @Test
    fun duplicateFolderNamesAreRejectedOnlyAmongSiblings() {
        val temp = createTempDirectory("rider-notes-folder-duplicates-").toFile()
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry("library.json", """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}""")
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
        }
        var snapshot = BackupImporter.import(zip)
        snapshot = BackupEditor.createGroup(snapshot, "Biology", null)
        val biology = snapshot.groups.single()
        snapshot = BackupEditor.createGroup(snapshot, "Cells", biology.id)
        snapshot = BackupEditor.createGroup(snapshot, "Plants", biology.id)
        val plants = snapshot.groups.first { it.name == "Plants" }

        assertFailsWith<IllegalArgumentException> {
            BackupEditor.createGroup(snapshot, "  biology  ", null)
        }
        assertFailsWith<IllegalArgumentException> {
            BackupEditor.renameGroup(snapshot, plants.id, "CELLS")
        }

        snapshot = BackupEditor.createGroup(snapshot, "Research", null)
        val research = snapshot.groups.first { it.name == "Research" }
        snapshot = BackupEditor.createGroup(snapshot, "Cells", research.id)
        assertEquals(2, snapshot.groups.count { it.name == "Cells" })
    }

    @Test
    fun renamingFolderPreservesIdsPlacementsAndNestedLinks() {
        val temp = createTempDirectory("rider-notes-folder-rename-").toFile()
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
        val parent = snapshot.groups.single()
        snapshot = BackupEditor.createGroup(snapshot, "Cells", parent.id)
        val child = snapshot.groups.first { it.parentId == parent.id }
        snapshot = BackupEditor.addTextPoint(snapshot, child.id, "Mitochondria", "Powerhouse")
        val placement = snapshot.placements.single()

        snapshot = BackupEditor.renameGroup(snapshot, parent.id, "Life sciences")
        snapshot = BackupEditor.renameGroup(snapshot, child.id, "Cell biology")

        assertEquals(parent.id, snapshot.groups.first { it.name == "Life sciences" }.id)
        assertEquals(parent.id, snapshot.groups.first { it.name == "Cell biology" }.parentId)
        assertEquals(child.id, snapshot.placements.single().groupId)
        assertEquals(placement.itemId, snapshot.placements.single().itemId)
    }

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
    fun `youtube live link discards old timestamp and uses each note position`() {
        val canonical = DesktopYouTubeLinks.canonicalUrl(
            "YouTube",
            "https://www.youtube.com/live/AbCdEf12345?si=shared&t=125s"
        )

        assertEquals("https://www.youtube.com/watch?v=AbCdEf12345", canonical)
        assertEquals(
            "https://www.youtube.com/watch?v=AbCdEf12345&t=310s",
            DesktopYouTubeLinks.atPosition("YouTube", canonical, 310_000L)
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

    @Test
    fun topicTreeOnlyShowsChildrenOfExpandedFolders() {
        val groups = listOf(
            DesktopTopicGroup("biology", "Biology", null, 0),
            DesktopTopicGroup("plants", "Plants", "biology", 0),
            DesktopTopicGroup("cells", "Plant cells", "plants", 0),
            DesktopTopicGroup("history", "History", null, 1)
        )

        assertEquals(
            listOf("biology", "history"),
            visibleTopicGroups(groups, emptySet()).map { it.first.id }
        )
        assertEquals(
            listOf("biology", "plants", "history"),
            visibleTopicGroups(groups, setOf("biology")).map { it.first.id }
        )
        assertEquals(
            listOf("biology", "plants", "cells", "history"),
            visibleTopicGroups(groups, setOf("biology", "plants")).map { it.first.id }
        )
    }

    @Test
    fun deleteNotesRemovesCompanionFilesAndUndoRestoresThem() {
        val temp = createTempDirectory("rider-notes-delete-").toFile()
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
            output.entry(
                "recordings/delete-me.json",
                """{"recordedAtEpochMs":1,"title":"Video","sourceApp":"YouTube"}"""
            )
            output.entry("recordings/delete-me.m4a", "audio")
            output.entry("recordings/delete-me.txt", "transcript")
        }

        var snapshot = BackupImporter.import(zip)
        assertEquals(1, snapshot.notes.size)
        snapshot = BackupEditor.deleteNotes(snapshot, setOf("delete-me"))
        assertTrue(snapshot.notes.isEmpty())
        assertTrue(snapshot.extractedRoot.walkTopDown().none { it.name.startsWith("delete-me.") })

        snapshot = DesktopUndoStore.restore(snapshot)
        assertEquals(1, snapshot.notes.size)
        assertTrue(snapshot.extractedRoot.walkTopDown().any { it.name == "delete-me.m4a" })
        assertTrue(snapshot.extractedRoot.walkTopDown().any { it.name == "delete-me.txt" })
    }

    @Test
    fun topicPointsDefaultWhiteAndSupportBulkColorStarAndRemoval() {
        val temp = createTempDirectory("rider-notes-topic-bulk-").toFile()
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
        val groupId = snapshot.groups.single().id
        snapshot = BackupEditor.addTextPoint(snapshot, groupId, "Cells", "Cell note")
        snapshot = BackupEditor.addTextPoint(snapshot, groupId, "Plants", "Plant note")
        val ids = snapshot.placements.mapTo(mutableSetOf()) { it.id }
        assertTrue(snapshot.placements.all { it.color == "WHITE" && !it.important })

        snapshot = BackupEditor.updatePlacements(snapshot, ids, important = true, color = "GREEN")
        assertTrue(snapshot.placements.all { it.color == "GREEN" && it.important })
        snapshot = BackupEditor.updateLegend(snapshot, "GREEN", "Verified")
        assertEquals("Verified", snapshot.colorLegend["GREEN"])

        snapshot = BackupEditor.removePlacements(snapshot, ids)
        assertTrue(snapshot.placements.isEmpty())
        assertTrue(snapshot.items.isEmpty())
        snapshot = DesktopUndoStore.restore(snapshot)
        assertEquals(2, snapshot.placements.size)
        assertTrue(snapshot.placements.all { it.color == "GREEN" && it.important })
        assertEquals("Verified", snapshot.colorLegend["GREEN"])
    }

    @Test
    fun linkingPointSharesOneItemAndSurvivesRemovingEitherPlacement() {
        val temp = createTempDirectory("rider-notes-topic-link-").toFile()
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
        snapshot = BackupEditor.createGroup(snapshot, "Research", null)
        val biology = snapshot.groups.first { it.name == "Biology" }
        val research = snapshot.groups.first { it.name == "Research" }
        snapshot = BackupEditor.addTextPoint(snapshot, biology.id, "Cells", "Shared note")
        val original = snapshot.placements.single()

        snapshot = BackupEditor.linkPlacementToGroup(snapshot, original.id, research.id)
        assertEquals(1, snapshot.items.size)
        assertEquals(2, snapshot.placements.size)
        assertEquals(1, snapshot.placements.map { it.itemId }.distinct().size)
        assertEquals(setOf(biology.id, research.id), snapshot.placements.map { it.groupId }.toSet())

        val duplicateAttempt = BackupEditor.linkPlacementToGroup(snapshot, original.id, research.id)
        assertEquals(2, duplicateAttempt.placements.size)

        snapshot = BackupEditor.removePlacement(snapshot, original.id)
        assertEquals(1, snapshot.placements.size)
        assertEquals(1, snapshot.items.size)
        assertEquals(research.id, snapshot.placements.single().groupId)

        snapshot = DesktopUndoStore.restore(snapshot)
        assertEquals(2, snapshot.placements.size)
        assertEquals(1, snapshot.items.size)
    }

    @Test
    fun deletingTopicFolderRemovesItsTreeButKeepsItemsLinkedElsewhere() {
        val temp = createTempDirectory("rider-notes-topic-folder-delete-").toFile()
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
        val biology = snapshot.groups.single()
        snapshot = BackupEditor.createGroup(snapshot, "Cells", biology.id)
        val cells = snapshot.groups.first { it.name == "Cells" }
        snapshot = BackupEditor.createGroup(snapshot, "Research", null)
        val research = snapshot.groups.first { it.name == "Research" }
        snapshot = BackupEditor.addTextPoint(snapshot, cells.id, "Mitochondria", "Shared point")
        val originalPlacement = snapshot.placements.single()
        snapshot = BackupEditor.linkPlacementToGroup(snapshot, originalPlacement.id, research.id)

        snapshot = BackupEditor.deleteGroup(snapshot, biology.id)

        assertEquals(listOf("Research"), snapshot.groups.map { it.name })
        assertEquals(1, snapshot.items.size)
        assertEquals(1, snapshot.placements.size)
        assertEquals(research.id, snapshot.placements.single().groupId)
        snapshot = DesktopUndoStore.restore(snapshot)
        assertEquals(setOf("Biology", "Cells", "Research"), snapshot.groups.map { it.name }.toSet())
        assertEquals(2, snapshot.placements.size)
    }

    @Test
    fun topicFiltersCombineColorAndStarAndKeepFolderAncestors() {
        val groups = listOf(
            DesktopTopicGroup("science", "Science", null, 0),
            DesktopTopicGroup("biology", "Biology", "science", 0),
            DesktopTopicGroup("cells", "Cells", "biology", 0),
            DesktopTopicGroup("history", "History", null, 1)
        )
        val placements = listOf(
            DesktopTopicPlacement("p1", "cells", "i1", 0, true, "GREEN"),
            DesktopTopicPlacement("p2", "history", "i2", 0, false, "GREEN"),
            DesktopTopicPlacement("p3", "history", "i3", 1, true, "RED")
        )

        val greenStarred = placements.filter { topicPlacementMatches(it, "GREEN", true) }
        assertEquals(listOf("p1"), greenStarred.map { it.id })
        assertEquals(
            setOf("science", "biology", "cells"),
            topicRelevantGroupIds(groups, greenStarred)
        )
        assertTrue(topicPlacementMatches(placements[2], null, true))
        assertTrue(!topicPlacementMatches(placements[1], "GREEN", true))
    }

    @Test
    fun linkAndAttachmentPointsKeepTheirOptionalNotes() {
        val temp = createTempDirectory("rider-notes-point-notes-").toFile()
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry("rider_notes_backup.json", """{"formatVersion":1,"backupType":"FULL"}""")
        }
        val attachment = File(temp, "diagram.png").apply { writeText("image") }

        var snapshot = BackupImporter.import(zip)
        snapshot = BackupEditor.createGroup(snapshot, "Research", null)
        val groupId = snapshot.groups.single().id
        snapshot = BackupEditor.addLinkPoint(
            snapshot, groupId, "Reference", "https://example.com", "Read section four"
        )
        snapshot = BackupEditor.addAttachment(
            snapshot, groupId, attachment, "Diagram from the experiment"
        )

        assertEquals("Read section four", snapshot.items.first { it.type == "LINK" }.additionalNotes)
        assertEquals("Diagram from the experiment", snapshot.items.first { it.type == "ATTACHMENT" }.additionalNotes)
    }

    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
