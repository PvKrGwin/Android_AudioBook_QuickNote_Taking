package com.praveen.ridernotes.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeltaSyncProtocolTest {
    @Test
    fun descendantRevisionUploadsRegardlessOfClockTime() {
        val remote = revision("remote", parent = null, modifiedAt = 9_999L, hash = "old")
        val local = revision("local", parent = "remote", modifiedAt = 1L, hash = "new")

        assertEquals(
            DeltaSyncDecision.UPLOAD_LOCAL,
            DeltaSyncPlanner.decide(local, remote)
        )
    }

    @Test
    fun divergentEditsAreConflictInsteadOfNewestTimestampWinning() {
        val local = revision("local", parent = "common", modifiedAt = 20_000L, hash = "a")
        val remote = revision("remote", parent = "common", modifiedAt = 30_000L, hash = "b")

        assertEquals(DeltaSyncDecision.CONFLICT, DeltaSyncPlanner.decide(local, remote))
    }

    @Test
    fun equalContentFromTwoDevicesIsIdentical() {
        val local = revision("local", parent = "left", modifiedAt = 1L, hash = "same")
        val remote = revision("remote", parent = "right", modifiedAt = 2L, hash = "same")

        assertEquals(DeltaSyncDecision.IDENTICAL, DeltaSyncPlanner.decide(local, remote))
    }

    @Test
    fun sha256DetectsSameLengthContentChanges() {
        assertNotEquals(DeltaContentHash.sha256("abcd"), DeltaContentHash.sha256("abce"))
    }

    private fun revision(
        id: String,
        parent: String?,
        modifiedAt: Long,
        hash: String
    ) = DeltaEntityRevision(
        entityId = "topic-item:1",
        kind = DeltaEntityKind.TOPIC_ITEM,
        revisionId = id,
        parentRevisionId = parent,
        modifiedAtEpochMs = modifiedAt,
        deviceId = id,
        deviceName = id,
        deviceType = "TEST",
        operation = DeltaOperation.UPSERT,
        sizeBytes = 4,
        sha256 = hash
    )
}
