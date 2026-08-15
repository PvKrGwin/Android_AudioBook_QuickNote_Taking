package com.praveen.ridernotes.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonMeaningTest {
    @Test
    fun playPauseKey_hasReadableMeaning() {
        assertEquals(
            "Media play/pause",
            ButtonMeaning.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
    }

    @Test
    fun unknownKey_isKeptForInvestigation() {
        assertEquals(
            "Unmapped—keep this result",
            ButtonMeaning.forKeyCode(KeyEvent.KEYCODE_UNKNOWN)
        )
    }
}
