package com.praveen.ridernotes.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun pendingLink_waitsUntilMatchingServiceActuallyPlays() {
        val state = SharedMediaLinkStore.CacheState(
            sourceUrl = "https://www.youtube.com/watch?v=video123",
            blockedIdentity = null,
            activeIdentity = null
        )
        val identity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Shared video",
            "Channel"
        )

        val paused = SharedMediaLinkStore.transition(state, identity, isPlaying = false)
        assertEquals(SharedMediaLinkStore.Observation.PENDING, paused.observation)
        assertEquals(state, paused.state)

        val playing = SharedMediaLinkStore.transition(state, identity, isPlaying = true)
        assertEquals(SharedMediaLinkStore.Observation.BOUND, playing.observation)
        assertEquals(identity, playing.state?.activeIdentity)
    }

    @Test
    fun currentlyPlayingOldVideo_isNotGivenNewlySharedLink() {
        val oldIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Old playing video",
            "Channel"
        )
        val state = SharedMediaLinkStore.CacheState(
            sourceUrl = "https://www.youtube.com/watch?v=newVideo",
            blockedIdentity = oldIdentity,
            activeIdentity = null
        )

        val oldVideoObservation = SharedMediaLinkStore.transition(
            state,
            oldIdentity,
            isPlaying = true
        )
        assertEquals(
            SharedMediaLinkStore.Observation.PENDING,
            oldVideoObservation.observation
        )

        val newIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "New shared video",
            "Another channel"
        )
        val newVideoObservation = SharedMediaLinkStore.transition(
            state,
            newIdentity,
            isPlaying = true
        )
        assertEquals(
            SharedMediaLinkStore.Observation.BOUND,
            newVideoObservation.observation
        )
        assertEquals(newIdentity, newVideoObservation.state?.activeIdentity)
    }

    @Test
    fun recordingRequest_bindsQueuedLinkEvenWhenPlaybackObservationWasBlocked() {
        val currentIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Shared video now being recorded",
            "Channel"
        )
        val pending = SharedMediaLinkStore.CacheState(
            sourceUrl = "https://www.youtube.com/watch?v=video123",
            blockedIdentity = currentIdentity,
            activeIdentity = null
        )

        val transition = SharedMediaLinkStore.transition(
            state = pending,
            currentIdentity = currentIdentity,
            isPlaying = true,
            allowBlockedBinding = true
        )

        assertEquals(SharedMediaLinkStore.Observation.BOUND, transition.observation)
        assertEquals(currentIdentity, transition.state?.activeIdentity)
    }

    @Test
    fun autoplayChange_clearsOnlyTemporaryCache() {
        val firstIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Shared video",
            "Channel"
        )
        val active = SharedMediaLinkStore.CacheState(
            sourceUrl = "https://www.youtube.com/watch?v=video123",
            blockedIdentity = null,
            activeIdentity = firstIdentity
        )
        val secondIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Autoplay video",
            "Channel"
        )

        val transition = SharedMediaLinkStore.transition(
            active,
            secondIdentity,
            isPlaying = true
        )

        assertEquals(SharedMediaLinkStore.Observation.CLEARED, transition.observation)
        assertNull(transition.state)
    }

    @Test
    fun lateChannelMetadata_doesNotClearSameVideo() {
        val titleOnlyIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Shared video",
            null
        )
        val active = SharedMediaLinkStore.CacheState(
            sourceUrl = "https://www.youtube.com/watch?v=video123",
            blockedIdentity = null,
            activeIdentity = titleOnlyIdentity
        )
        val enrichedIdentity = SharedMediaLinkStore.identityKey(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            "Shared video",
            "Channel now available"
        )

        val transition = SharedMediaLinkStore.transition(
            active,
            enrichedIdentity,
            isPlaying = true
        )

        assertEquals(SharedMediaLinkStore.Observation.ACTIVE, transition.observation)
        assertEquals(enrichedIdentity, transition.state?.activeIdentity)
    }
}
