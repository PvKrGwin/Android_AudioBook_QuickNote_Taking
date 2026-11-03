package com.praveen.ridernotes.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSourceLinkTest {
    @Test
    fun youtubeWatchUrl_isCanonicalized() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            YouTubeSourceLink.fromMetadata(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                listOf("android.media.metadata.MEDIA_URI" to
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=example")
            )
        )
    }

    @Test
    fun youtubeMusic_usesMusicHostAndTimestamp() {
        val source = YouTubeSourceLink.fromMetadata(
            YouTubeSourceLink.YOUTUBE_MUSIC_PACKAGE,
            listOf("videoId" to "dQw4w9WgXcQ")
        )

        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&t=83s",
            YouTubeSourceLink.atPosition(source, 83_999L)
        )
    }

    @Test
    fun unrelatedTitle_isNeverTurnedIntoALink() {
        assertNull(
            YouTubeSourceLink.fromMetadata(
                YouTubeSourceLink.YOUTUBE_PACKAGE,
                listOf("android.media.metadata.TITLE" to "A very interesting video")
            )
        )
    }

    @Test
    fun nonYouTubePackage_isRejected() {
        assertNull(
            YouTubeSourceLink.fromMetadata(
                "com.example.player",
                listOf("videoId" to "dQw4w9WgXcQ")
            )
        )
    }

    @Test
    fun youtubeTitle_hasSearchFallback() {
        assertEquals(
            "https://www.youtube.com/results?search_query=A+Brief+History+of+Intelligence",
            YouTubeSourceLink.searchUrl("YouTube", "A Brief History of Intelligence")
        )
    }

    @Test
    fun nonYouTubeTitle_hasNoSearchFallback() {
        assertNull(YouTubeSourceLink.searchUrl("Audible", "A Brief History of Intelligence"))
    }

    @Test
    fun youtubeMusicTitle_usesMusicSearch() {
        assertEquals(
            "https://music.youtube.com/search?q=Consciousness+podcast",
            YouTubeSourceLink.searchUrl("YouTube Music", "Consciousness podcast")
        )
    }

    @Test
    fun sharedShortLink_usesCurrentlyPlayingApp() {
        assertEquals(
            YouTubeSourceLink.SharedSource(
                YouTubeSourceLink.YOUTUBE_MUSIC_PACKAGE,
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
            ),
            YouTubeSourceLink.fromSharedText(
                "https://youtu.be/dQw4w9WgXcQ",
                YouTubeSourceLink.YOUTUBE_MUSIC_PACKAGE
            )
        )
    }
}
