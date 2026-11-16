package com.praveen.ridernotes.desktop

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

class DesktopAudioRecorder {
    private var line: TargetDataLine? = null
    private var output: File? = null
    private var writer: Thread? = null

    val isRecording: Boolean get() = line?.isOpen == true

    fun start(): File {
        check(!isRecording) { "A topic recording is already active" }
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val target = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
        target.open(format)
        val file = File(
            File(System.getProperty("user.home"), "RiderNotesPC/recordings").apply { mkdirs() },
            "RiderTopic_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.wav"
        )
        line = target
        output = file
        target.start()
        writer = Thread({
            runCatching {
                AudioInputStream(target).use { stream ->
                    AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
                }
            }
        }, "rider-notes-topic-recorder").apply { isDaemon = true; start() }
        return file
    }

    fun stop(): File? {
        val target = line ?: return null
        target.stop()
        target.close()
        writer?.join(2_000)
        line = null
        writer = null
        return output.also { output = null }
    }
}
