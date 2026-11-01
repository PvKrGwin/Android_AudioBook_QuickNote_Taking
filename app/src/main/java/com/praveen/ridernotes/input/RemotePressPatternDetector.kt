package com.praveen.ridernotes.input

/**
 * Converts Beauty-R1's alternating volume-key clicks into logical gestures.
 * Volume Up and Volume Down are treated as the same physical button.
 */
class RemotePressPatternDetector(
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    enum class Gesture {
        SINGLE,
        DOUBLE,
        TRIPLE,
        QUADRUPLE
    }

    private var firstPressAt: Long? = null
    private var pressCount = 0

    init {
        require(windowMs > 0) { "windowMs must be positive" }
    }

    /**
     * Records one physical press. Four presses resolve immediately. One to
     * three presses wait for the window to close so triple can be distinguished
     * from quadruple.
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
        return if (pressCount >= 4) {
            reset()
            Gesture.QUADRUPLE
        } else {
            null
        }
    }

    @Synchronized
    fun resolve(atMillis: Long): Gesture? {
        val startedAt = firstPressAt ?: return null
        if (atMillis - startedAt < windowMs) return null

        val completed = gestureFor(pressCount)
        reset()
        return completed
    }

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
        3 -> Gesture.TRIPLE
        else -> Gesture.QUADRUPLE
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 800L
    }
}
