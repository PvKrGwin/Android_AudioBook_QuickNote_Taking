package com.praveen.ridernotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeltaSyncProtocolTest {
    @Test
    fun descendantRevisionDownloadsWhenCloudDescendsFromLocal() {
        val local = revision("local", null, 50_000L, "old")
        val remote = revision("remote", "intermediate", 1L, "new", listOf("intermediate", "local"))

        assertEquals(DeltaSyncDecision.DOWNLOAD_REMOTE, DeltaSyncPlanner.decide(local, remote))
    }

    @Test
    fun unrelatedRevisionsConflictEvenWhenOneTimestampIsNewer() {
        val local = revision("local", "base", 1L, "left")
        val remote = revision("remote", "base", 99_999L, "right")

        assertEquals(DeltaSyncDecision.CONFLICT, DeltaSyncPlanner.decide(local, remote))
    }

    @Test
    fun sha256IsStableAndContentSensitive() {
        assertEquals(DeltaContentHash.sha256("same"), DeltaContentHash.sha256("same"))
        assertNotEquals(DeltaContentHash.sha256("same"), DeltaContentHash.sha256("diff"))
    }

    private fun revision(
        id: String,
        parent: String?,
        time: Long,
        hash: String,
        ancestors: List<String> = parent?.let(::listOf).orEmpty()
    ) =
        DeltaEntityRevision(
            entityId = "recording-metadata:1",
            kind = DeltaEntityKind.RECORDING_METADATA,
            revisionId = id,
            parentRevisionId = parent,
            ancestorRevisionIds = ancestors,
            modifiedAtEpochMs = time,
            deviceId = id,
            deviceName = id,
            deviceType = "TEST",
            operation = DeltaOperation.UPSERT,
            sizeBytes = 4,
            sha256 = hash
        )
}
