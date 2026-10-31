package com.praveen.ridernotes.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePressPatternDetectorTest {

    @Test
    fun singlePressResolvesAfterWindow() {
        val detector = RemotePressPatternDetector(windowMs = 800)

        assertNull(detector.onPress(1_000))
        assertNull(detector.resolve(1_799))
        assertEquals(
            RemotePressPatternDetector.Gesture.SINGLE,
            detector.resolve(1_800)
        )
    }

    @Test
    fun twoPressesResolveAsDouble() {
        val detector = RemotePressPatternDetector(windowMs = 800)

        assertNull(detector.onPress(1_000))
        assertNull(detector.onPress(1_400))
        assertEquals(
            RemotePressPatternDetector.Gesture.DOUBLE,
            detector.resolve(1_800)
        )
    }

    @Test
    fun threePressesResolveImmediatelyAsTriple() {
        val detector = RemotePressPatternDetector(windowMs = 800)

        assertNull(detector.onPress(1_000))
        assertNull(detector.onPress(1_250))
        assertEquals(
            RemotePressPatternDetector.Gesture.TRIPLE,
            detector.onPress(1_500)
        )
        assertNull(detector.resolve(2_500))
    }

    @Test
    fun alternatingBeautyKeyCodesDoNotAffectClassification() {
        // The detector intentionally receives physical presses, not key codes:
        // Beauty-R1 alternates Volume Down and Volume Up for the same button.
        val detector = RemotePressPatternDetector(windowMs = 800)

        assertNull(detector.onPress(10_000)) // Volume Down
        assertNull(detector.onPress(10_300)) // Volume Up
        assertEquals(
            RemotePressPatternDetector.Gesture.DOUBLE,
            detector.resolve(10_800)
        )
    }

    @Test
    fun lateSecondPressStartsANewPattern() {
        val detector = RemotePressPatternDetector(windowMs = 800)

        assertNull(detector.onPress(1_000))
        assertEquals(
            RemotePressPatternDetector.Gesture.SINGLE,
            detector.onPress(1_900)
        )
        assertEquals(
            RemotePressPatternDetector.Gesture.SINGLE,
            detector.resolve(2_700)
        )
    }
}
