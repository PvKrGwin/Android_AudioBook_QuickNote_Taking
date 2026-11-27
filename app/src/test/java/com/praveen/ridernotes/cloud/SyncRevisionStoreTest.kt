package com.praveen.ridernotes.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRevisionStoreTest {
    @Test
    fun clearsDirtyWhenUploadedRevisionIsStillCurrent() {
        assertTrue(
            SyncRevisionStore.shouldClearDirtyAfterUpload(
                dirty = true,
                pendingRevisionId = "revision-a",
                uploadedRevisionId = "revision-a"
            )
        )
    }

    @Test
    fun keepsDirtyWhenTranscriptionCreatesNewerChangeDuringUpload() {
        assertFalse(
            SyncRevisionStore.shouldClearDirtyAfterUpload(
                dirty = true,
                pendingRevisionId = null,
                uploadedRevisionId = "revision-a"
            )
        )
    }

    @Test
    fun keepsDirtyWhenAnotherRevisionReplacesUploadedRevision() {
        assertFalse(
            SyncRevisionStore.shouldClearDirtyAfterUpload(
                dirty = true,
                pendingRevisionId = "revision-b",
                uploadedRevisionId = "revision-a"
            )
        )
    }

    @Test
    fun cleanPhoneRemainsSynced() {
        assertTrue(
            SyncRevisionStore.shouldClearDirtyAfterUpload(
                dirty = false,
                pendingRevisionId = null,
                uploadedRevisionId = "revision-a"
            )
        )
    }
}
