package com.praveen.ridernotes.media

import android.content.Context
import java.util.Locale

/**
 * Holds the one temporary link explicitly shared for each YouTube service.
 *
 * A shared link starts pending. The first newly playing item in the matching
 * service binds it to that title/channel. Other media apps never affect it.
 * Once YouTube or YouTube Music exposes another item, the temporary link is
 * cleared. Voice-note metadata already copied from this cache is independent
 * and is never modified here.
 */
object SharedMediaLinkStore {
    private const val PREFS_NAME = "shared_media_links"
    private const val FIELD_URL = "url"
    private const val FIELD_BLOCKED_IDENTITY = "blocked_identity"
    private const val FIELD_ACTIVE_IDENTITY = "active_identity"
    private const val FIELD_DISPLAY_TITLE = "display_title"
    private const val IDENTITY_SEPARATOR = "\u001F"

    internal data class CacheState(
        val sourceUrl: String,
        val blockedIdentity: String?,
        val activeIdentity: String?,
        val displayTitle: String? = null
    )

    data class CachedLink(
        val packageName: String,
        val sourceUrl: String,
        val isBoundToCurrentItem: Boolean,
        val title: String?
    )

    internal enum class Observation {
        PENDING,
        BOUND,
        ACTIVE,
        CLEARED
    }

    internal data class Transition(
        val state: CacheState?,
        val observation: Observation
    )

    /** Queue a link shared before playback begins. */
    fun stage(
        context: Context,
        packageName: String,
        sourceUrl: String,
        currentlyPlayingTitle: String? = null,
        currentlyPlayingAuthor: String? = null,
        sharedTitle: String? = null
    ) {
        val blockedIdentity = currentlyPlayingTitle
            ?.takeIf { it.isNotBlank() }
            ?.let { identityKey(packageName, it, currentlyPlayingAuthor) }
        write(
            context,
            packageName,
            CacheState(
                sourceUrl = sourceUrl,
                blockedIdentity = blockedIdentity,
                activeIdentity = null,
                displayTitle = sharedTitle?.trim()?.takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * Observe the matching service. A pending link binds only while an item is
     * actually playing; an already-bound link clears as soon as the identity
     * changes, even if the replacement item is still paused or buffering.
     */
    internal fun observe(
        context: Context,
        packageName: String,
        title: String?,
        author: String?,
        isPlaying: Boolean
    ): Observation? {
        val usableTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val previous = read(context, packageName) ?: return null
        val transition = transition(
            previous,
            identityKey(packageName, usableTitle, author),
            isPlaying
        ).withDisplayTitle(usableTitle)
        applyTransition(context, packageName, previous, transition)
        return transition.observation
    }

    /**
     * A recording request is definitive evidence that the current item is the
     * one the user wants to annotate. Bind a still-pending shared link here so
     * it is copied into the recording metadata before the temporary cache can
     * be cleared by a later autoplay item.
     */
    internal fun bindForRecording(
        context: Context,
        packageName: String,
        title: String?,
        author: String?
    ): Observation? {
        val usableTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val previous = read(context, packageName) ?: return null
        val transition = transition(
            state = previous,
            currentIdentity = identityKey(packageName, usableTitle, author),
            isPlaying = true,
            allowBlockedBinding = true
        ).withDisplayTitle(usableTitle)
        applyTransition(context, packageName, previous, transition)
        return transition.observation
    }

    private fun applyTransition(
        context: Context,
        packageName: String,
        previous: CacheState,
        transition: Transition
    ) {
        if (transition.state == null) {
            clear(context, packageName)
        } else if (transition.state != previous) {
            write(context, packageName, transition.state)
        }
    }

    /** Return the link only for the item to which the temporary cache bound. */
    fun find(
        context: Context,
        packageName: String,
        title: String?,
        author: String?
    ): String? {
        val usableTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val state = read(context, packageName) ?: return null
        val currentIdentity = identityKey(packageName, usableTitle, author)
        return state.sourceUrl.takeIf {
            state.activeIdentity?.let { active -> sameIdentity(active, currentIdentity) } == true
        }
    }

    /** Snapshot used only for displaying the real temporary-cache status. */
    fun currentLinks(context: Context): List<CachedLink> =
        listOf(
            YouTubeSourceLink.YOUTUBE_PACKAGE,
            YouTubeSourceLink.YOUTUBE_MUSIC_PACKAGE
        ).mapNotNull { packageName ->
            read(context, packageName)?.let { state ->
                CachedLink(
                    packageName = packageName,
                    sourceUrl = state.sourceUrl,
                    isBoundToCurrentItem = state.activeIdentity != null,
                    title = state.displayTitle
                )
            }
        }

    fun clear(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(packageName, FIELD_URL))
            .remove(key(packageName, FIELD_BLOCKED_IDENTITY))
            .remove(key(packageName, FIELD_ACTIVE_IDENTITY))
            .remove(key(packageName, FIELD_DISPLAY_TITLE))
            .apply()
    }

    internal fun transition(
        state: CacheState,
        currentIdentity: String,
        isPlaying: Boolean,
        allowBlockedBinding: Boolean = false
    ): Transition {
        val activeIdentity = state.activeIdentity
        if (activeIdentity != null) {
            return if (sameIdentity(activeIdentity, currentIdentity)) {
                Transition(
                    state.copy(
                        activeIdentity = richerIdentity(activeIdentity, currentIdentity)
                    ),
                    Observation.ACTIVE
                )
            } else {
                Transition(null, Observation.CLEARED)
            }
        }

        val blockedIdentity = state.blockedIdentity
        if (!isPlaying ||
            (!allowBlockedBinding &&
                blockedIdentity != null &&
                sameIdentity(blockedIdentity, currentIdentity))
        ) {
            return Transition(state, Observation.PENDING)
        }

        return Transition(
            state.copy(blockedIdentity = null, activeIdentity = currentIdentity),
            Observation.BOUND
        )
    }

    internal fun identityKey(packageName: String, title: String, author: String?): String =
        listOf(packageName, normalize(title), normalize(author.orEmpty()))
            .joinToString(IDENTITY_SEPARATOR)

    private fun sameIdentity(first: String, second: String): Boolean {
        val firstParts = first.split(IDENTITY_SEPARATOR, limit = 3)
        val secondParts = second.split(IDENTITY_SEPARATOR, limit = 3)
        if (firstParts.size != 3 || secondParts.size != 3) return first == second
        return firstParts[0] == secondParts[0] &&
            firstParts[1] == secondParts[1] &&
            (firstParts[2].isBlank() ||
                secondParts[2].isBlank() ||
                firstParts[2] == secondParts[2])
    }

    private fun richerIdentity(first: String, second: String): String {
        val firstParts = first.split(IDENTITY_SEPARATOR, limit = 3)
        val secondParts = second.split(IDENTITY_SEPARATOR, limit = 3)
        return if (firstParts.size == 3 && secondParts.size == 3 &&
            firstParts[2].isBlank() && secondParts[2].isNotBlank()
        ) {
            second
        } else {
            first
        }
    }

    private fun read(context: Context, packageName: String): CacheState? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sourceUrl = preferences.getString(key(packageName, FIELD_URL), null) ?: return null
        return CacheState(
            sourceUrl = sourceUrl,
            blockedIdentity = preferences.getString(
                key(packageName, FIELD_BLOCKED_IDENTITY),
                null
            ),
            activeIdentity = preferences.getString(key(packageName, FIELD_ACTIVE_IDENTITY), null),
            displayTitle = preferences.getString(key(packageName, FIELD_DISPLAY_TITLE), null)
        )
    }

    private fun write(context: Context, packageName: String, state: CacheState) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(packageName, FIELD_URL), state.sourceUrl)
        if (state.blockedIdentity == null) {
            editor.remove(key(packageName, FIELD_BLOCKED_IDENTITY))
        } else {
            editor.putString(key(packageName, FIELD_BLOCKED_IDENTITY), state.blockedIdentity)
        }
        if (state.activeIdentity == null) {
            editor.remove(key(packageName, FIELD_ACTIVE_IDENTITY))
        } else {
            editor.putString(key(packageName, FIELD_ACTIVE_IDENTITY), state.activeIdentity)
        }
        if (state.displayTitle == null) {
            editor.remove(key(packageName, FIELD_DISPLAY_TITLE))
        } else {
            editor.putString(key(packageName, FIELD_DISPLAY_TITLE), state.displayTitle)
        }
        editor.apply()
    }

    private fun Transition.withDisplayTitle(title: String): Transition =
        if (state == null || observation == Observation.PENDING) {
            this
        } else {
            copy(state = state.copy(displayTitle = title))
        }

    private fun key(packageName: String, field: String): String = "$packageName.$field"

    private fun normalize(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
}
