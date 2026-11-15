package com.praveen.ridernotes.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DesktopPlaybackState(
    val file: File? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val error: String? = null
)

/** Windows in-app audio playback backed by JavaFX Media Foundation codecs. */
class DesktopAudioPlayer : AutoCloseable {
    var state by mutableStateOf(DesktopPlaybackState())
        private set

    private var player: MediaPlayer? = null

    init {
        JavaFxRuntime.start()
    }

    fun playOrPause(file: File) {
        if (state.file?.absolutePath == file.absolutePath && player != null) {
            Platform.runLater {
                if (state.playing && !state.paused) {
                    player?.pause()
                    state = state.copy(paused = true)
                } else {
                    player?.play()
                    state = state.copy(playing = true, paused = false, error = null)
                }
            }
            return
        }
        Platform.runLater {
            disposePlayer()
            runCatching {
                val next = MediaPlayer(Media(file.toURI().toString()))
                player = next
                state = DesktopPlaybackState(file = file, playing = true)
                next.setOnReady {
                    state = state.copy(durationMs = next.totalDuration.toMillis().toLong().coerceAtLeast(0L))
                }
                next.currentTimeProperty().addListener { _, _, value ->
                    state = state.copy(positionMs = value.toMillis().toLong().coerceAtLeast(0L))
                }
                next.setOnEndOfMedia {
                    state = state.copy(
                        positionMs = state.durationMs,
                        playing = false,
                        paused = false
                    )
                }
                next.setOnError {
                    state = state.copy(
                        playing = false,
                        paused = false,
                        error = next.error?.message ?: "Windows could not play this recording"
                    )
                }
                next.play()
            }.onFailure {
                state = DesktopPlaybackState(
                    file = file,
                    error = it.message ?: "Windows could not open this recording"
                )
            }
        }
    }

    fun seek(positionMs: Long) {
        Platform.runLater {
            player?.seek(Duration.millis(positionMs.coerceAtLeast(0L).toDouble()))
        }
    }

    fun stop() {
        Platform.runLater {
            player?.stop()
            state = state.copy(positionMs = 0L, playing = false, paused = false)
        }
    }

    override fun close() {
        Platform.runLater { disposePlayer() }
    }

    private fun disposePlayer() {
        player?.stop()
        player?.dispose()
        player = null
    }
}

private object JavaFxRuntime {
    @Volatile private var started = false

    @Synchronized
    fun start() {
        if (started) return
        val ready = CountDownLatch(1)
        runCatching {
            Platform.startup {
                Platform.setImplicitExit(false)
                ready.countDown()
            }
            ready.await(5, TimeUnit.SECONDS)
        }.onFailure {
            // Platform.startup throws when another component already initialized JavaFX.
            if (it !is IllegalStateException) throw it
        }
        started = true
    }
}
