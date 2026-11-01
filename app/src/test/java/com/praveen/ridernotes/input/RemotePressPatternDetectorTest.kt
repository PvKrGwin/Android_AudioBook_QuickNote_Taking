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
        assertEquals(RemotePressPatternDetector.Gesture.SINGLE, detector.resolve(1_800))
    }

    @Test
    fun twoPressesResolveAsDouble() {
        val detector = RemotePressPatternDetector(windowMs = 800)
        assertNull(detector.onPress(1_000))
        assertNull(detector.onPress(1_400))
        assertEquals(RemotePressPatternDetector.Gesture.DOUBLE, detector.resolve(1_800))
    }

    @Test
    fun threePressesWaitAndResolveAsTriple() {
        val detector = RemotePressPatternDetector(windowMs = 800)
        assertNull(detector.onPress(1_000))
        assertNull(detector.onPress(1_250))
        assertNull(detector.onPress(1_500))
        assertNull(detector.resolve(1_799))
        assertEquals(RemotePressPatternDetector.Gesture.TRIPLE, detector.resolve(1_800))
    }

    @Test
    fun fourPressesResolveImmediatelyAsQuadruple() {
        val detector = RemotePressPatternDetector(windowMs = 800)
        assertNull(detector.onPress(1_000))
        assertNull(detector.onPress(1_150))
        assertNull(detector.onPress(1_300))
        assertEquals(
            RemotePressPatternDetector.Gesture.QUADRUPLE,
            detector.onPress(1_450)
        )
        assertNull(detector.resolve(2_500))
    }

    @Test
    fun alternatingBeautyKeyCodesDoNotAffectClassification() {
        val detector = RemotePressPatternDetector(windowMs = 800)
        assertNull(detector.onPress(10_000)) // Volume Down
        assertNull(detector.onPress(10_300)) // Volume Up
        assertEquals(RemotePressPatternDetector.Gesture.DOUBLE, detector.resolve(10_800))
    }

    @Test
    fun lateSecondPressStartsANewPattern() {
        val detector = RemotePressPatternDetector(windowMs = 800)
        assertNull(detector.onPress(1_000))
        assertEquals(RemotePressPatternDetector.Gesture.SINGLE, detector.onPress(1_900))
        assertEquals(RemotePressPatternDetector.Gesture.SINGLE, detector.resolve(2_700))
    }
}
