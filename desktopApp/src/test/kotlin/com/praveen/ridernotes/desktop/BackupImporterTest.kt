package com.praveen.ridernotes.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BackupImporterTest {
    @Test
    fun importsNotesAndTopicsFromFullBackup() {
        val temp = createTempDir(prefix = "rider-notes-pc-test-")
        val zip = File(temp, "RiderNotes_Full_Backup.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.entry(
                "library.json",
                """{"version":2,"groups":[{"id":"g1","name":"Biology","parentId":null,"order":0}],"items":[],"placements":[],"colorLegend":{}}"""
            )
            output.entry(
                "recordings/note1.json",
                """{"recordedAtEpochMs":1234,"title":"Cell structure","sourceApp":"Coursera","transcriptionStatus":"COMPLETED","transcript":"A cell has..."}"""
            )
            output.entry("recordings/note1.m4a", "audio")
        }

        val result = BackupImporter.import(zip)

        assertEquals(1, result.groups.size)
        assertEquals("Biology", result.groups.single().name)
        assertEquals(1, result.notes.size)
        assertEquals("Cell structure", result.notes.single().title)
        assertNotNull(result.notes.single().audioFile)
    }

    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
