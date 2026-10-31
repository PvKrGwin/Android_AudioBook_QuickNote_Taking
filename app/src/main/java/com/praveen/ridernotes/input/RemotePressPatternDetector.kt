package com.praveen.ridernotes.input

/**
 * Converts Beauty-R1's alternating volume-key clicks into logical gestures.
 *
 * KEYCODE_VOLUME_UP and KEYCODE_VOLUME_DOWN are deliberately treated as the
 * same physical button. The caller supplies one press for each ACTION_DOWN and
 * calls [resolve] after [windowMs] has elapsed.
 */
class RemotePressPatternDetector(
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    enum class Gesture {
        SINGLE,
        DOUBLE,
        TRIPLE
    }

    private var firstPressAt: Long? = null
    private var pressCount = 0

    init {
        require(windowMs > 0) { "windowMs must be positive" }
    }

    /**
     * Records one physical press. A triple press resolves immediately.
     *
     * If a new press arrives after the existing window, the older pattern is
     * returned and the new press becomes the beginning of the next pattern.
     */
    @Synchronized
    fun onPress(atMillis: Long): Gesture? {
        val startedAt = firstPressAt
        if (startedAt == null) {
            startPattern(atMillis)
            return null
        }

        if (atMillis - startedAt > windowMs) {
            val completed = gestureFor(pressCount)
            startPattern(atMillis)
            return completed
        }

        pressCount += 1
        return if (pressCount >= 3) {
            reset()
            Gesture.TRIPLE
        } else {
            null
        }
    }

    /**
     * Resolves a pending single or double press once its recognition window
     * has elapsed. Calling this too early leaves the pattern pending.
     */
    @Synchronized
    fun resolve(atMillis: Long): Gesture? {
        val startedAt = firstPressAt ?: return null
        if (atMillis - startedAt < windowMs) return null

        val completed = gestureFor(pressCount)
        reset()
        return completed
    }

    /** Uptime at which the current pattern can be resolved by the caller. */
    @Synchronized
    fun resolutionDeadlineMillis(): Long? = firstPressAt?.plus(windowMs)

    @Synchronized
    fun cancel() = reset()

    private fun startPattern(atMillis: Long) {
        firstPressAt = atMillis
        pressCount = 1
    }

    private fun reset() {
        firstPressAt = null
        pressCount = 0
    }

    private fun gestureFor(count: Int): Gesture = when (count) {
        1 -> Gesture.SINGLE
        2 -> Gesture.DOUBLE
        else -> Gesture.TRIPLE
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 800L
    }
}
