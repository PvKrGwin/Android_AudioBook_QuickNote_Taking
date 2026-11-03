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
    private const val IDENTITY_SEPARATOR = "\u001F"

    internal data class CacheState(
        val sourceUrl: String,
        val blockedIdentity: String?,
        val activeIdentity: String?
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
        currentlyPlayingAuthor: String? = null
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
                activeIdentity = null
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
        )
        if (transition.state == null) {
            clear(context, packageName)
        } else if (transition.state != previous) {
            write(context, packageName, transition.state)
        }
        return transition.observation
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

    fun clear(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(packageName, FIELD_URL))
            .remove(key(packageName, FIELD_BLOCKED_IDENTITY))
            .remove(key(packageName, FIELD_ACTIVE_IDENTITY))
            .apply()
    }

    internal fun transition(
        state: CacheState,
        currentIdentity: String,
        isPlaying: Boolean
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
            (blockedIdentity != null && sameIdentity(blockedIdentity, currentIdentity))
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
            activeIdentity = preferences.getString(key(packageName, FIELD_ACTIVE_IDENTITY), null)
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
        editor.apply()
    }

    private fun key(packageName: String, field: String): String = "$packageName.$field"

    private fun normalize(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
}
