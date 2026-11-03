package com.praveen.ridernotes.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SharedMediaLinkStoreTest {
    @Test
    fun sameIdentity_normalizesWhitespaceAndCase() {
        assertEquals(
            SharedMediaLinkStore.identityKey(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                "Does AI Need Advaita Vedanta?",
                "Hyper Quest"
            ),
            SharedMediaLinkStore.identityKey(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                "  DOES AI   NEED ADVAITA VEDANTA? ",
                "hyper quest"
            )
        )
    }

    @Test
    fun autoplayTitleChange_neverMatchesPreviousVideo() {
        assertNotEquals(
            SharedMediaLinkStore.identityKey(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                "First video",
                "Channel"
            ),
            SharedMediaLinkStore.identityKey(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                "Second autoplay video",
                "Channel"
            )
        )
    }

    @Test
    fun sameTitleDifferentChannel_neverMatches() {
        assertNotEquals(
            SharedMediaLinkStore.identityKey(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                "Consciousness explained",
                "Channel A"
            ),
            SharedMediaLinkStore.identityKey(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                "Consciousness explained",
                "Channel B"
            )
        )
    }
}
