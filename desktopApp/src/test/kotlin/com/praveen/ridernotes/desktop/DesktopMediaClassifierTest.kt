package com.praveen.ridernotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMediaClassifierTest {
    @Test
    fun `chrome session inherits existing Udemy heading and canonical title`() {
        val existing = note(
            title = "AUTOSAR Architecture and Integration",
            sourceApp = "Udemy"
        )
        val captured = CapturedMedia(
            sourceApp = "Chrome media",
            sourcePackage = "chrome.exe",
            title = "Autosar Architecture & Integration",
            author = "Instructor",
            playbackPositionMs = 62_000L,
            chapter = "Communication stack"
        )

        val resolved = DesktopMediaClassifier.resolve(captured, listOf(existing))

        assertEquals("Udemy", resolved.sourceApp)
        assertEquals("AUTOSAR Architecture and Integration", resolved.title)
        assertEquals("Communication stack", resolved.chapter)
    }

    @Test
    fun `known source is never overwritten by title fallback`() {
        val captured = CapturedMedia(
            sourceApp = "Coursera",
            sourcePackage = "chrome.exe",
            title = "Shared course title",
            author = null,
            playbackPositionMs = null
        )

        val resolved = DesktopMediaClassifier.resolve(
            captured,
            listOf(note("Shared course title", "Udemy"))
        )

        assertEquals("Coursera", resolved.sourceApp)
    }

    private fun note(title: String, sourceApp: String) = DesktopNote(
        id = "existing",
        title = title,
        sourceApp = sourceApp,
        module = null,
        chapter = null,
        author = null,
        recordedAtEpochMs = 100L,
        playbackPositionMs = null,
        sourceUrl = null,
        transcriptionStatus = "COMPLETED",
        transcript = null,
        transcriptSegments = emptyList(),
        audioFile = null,
        topicOnly = false
    )
}
