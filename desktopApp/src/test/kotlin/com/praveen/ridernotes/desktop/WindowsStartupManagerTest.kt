package com.praveen.ridernotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsStartupManagerTest {
    @Test
    fun `startup launcher quotes executable paths and preserves percent signs`() {
        assertEquals(
            "@echo off\r\nstart \"\" \"C:\\Apps\\Rider Notes 100%%\\RiderNotesPC.exe\"\r\n",
            WindowsStartupManager.createBatchLauncher(
                "C:\\Apps\\Rider Notes 100%\\RiderNotesPC.exe"
            )
        )
    }
}
