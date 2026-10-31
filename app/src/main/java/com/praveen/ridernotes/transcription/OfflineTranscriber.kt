package com.praveen.ridernotes.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-cost transcription using the speech model installed on the phone.
 *
 * Android 13 (API 33) added reliable injection of an already-recorded audio
 * source into SpeechRecognizer. The AAC/M4A note is decoded to PCM and streamed
 * through a pipe, so no microphone is reopened and no API key is involved.
 */
object OfflineTranscriber {
    private val busy = AtomicBoolean(false)

    fun enqueue(context: Context, audioFile: File) {
        val appContext = context.applicationContext
        val metadataFile = metadataFileFor(audioFile)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            updateMetadata(metadataFile, "UNAVAILABLE", error = "Offline transcription requires Android 13 or newer")
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            updateMetadata(metadataFile, "MODEL_REQUIRED", error = "No on-device speech model is installed")
            return
        }

        if (!busy.compareAndSet(false, true)) {
            updateMetadata(metadataFile, "QUEUED")
            return
        }

        updateMetadata(metadataFile, "TRANSCRIBING")

        var recognizer: SpeechRecognizer? = null
        var readPipe: ParcelFileDescriptor? = null
        var writePipe: ParcelFileDescriptor? = null

        fun finish(status: String, transcript: String? = null, error: String? = null) {
            updateMetadata(metadataFile, status, transcript, error)
            if (!transcript.isNullOrBlank()) {
                File(audioFile.parentFile, audioFile.nameWithoutExtension + ".txt").writeText(transcript)
            }
            try {
                readPipe?.close()
                writePipe?.close()
            } catch (_: Exception) {
                // Already closed by the decoder or recognizer.
            }
            recognizer?.destroy()
            busy.set(false)
        }

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            readPipe = pipe[0]
            writePipe = pipe[1]

            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    finish("FAILED", error = readableError(error))
                }

                override fun onResults(results: Bundle?) {
                    val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = alternatives?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) {
                        finish("NO_SPEECH", error = "No clear speech was recognised")
                    } else {
                        finish("COMPLETED", transcript = text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 44_100)
            }

            recognizer.startListening(recognitionIntent)

            val writer = writePipe
            Thread(
                {
                    try {
                        if (writer == null) throw IllegalStateException("Audio pipe unavailable")
                        FileOutputStream(writer.fileDescriptor).use { output ->
                            decodeM4aToPcm(audioFile, output)
                        }
                    } catch (failure: Exception) {
                        appContext.mainExecutor.execute {
                            finish("FAILED", error = "Could not decode recording: " + failure.javaClass.simpleName)
                        }
                    } finally {
                        try {
                            writer?.close()
                        } catch (_: Exception) {
                            // The recognizer may already have closed the pipe.
                        }
                    }
                },
                "RiderNotesAudioDecoder"
            ).start()
        } catch (failure: Exception) {
            finish("FAILED", error = "Offline recognizer could not start: " + failure.javaClass.simpleName)
        }
    }

    private fun decodeM4aToPcm(audioFile: File, output: FileOutputStream) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Missing audio format")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Missing decoder input")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outputBuffer.get(bytes)
                        output.write(bytes)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            output.flush()
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
                // Decoder may have failed before starting.
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun metadataFileFor(audioFile: File) =
        File(audioFile.parentFile, audioFile.nameWithoutExtension + ".json")

    @Synchronized
    private fun updateMetadata(
        metadataFile: File,
        status: String,
        transcript: String? = null,
        error: String? = null
    ) {
        try {
            val json = if (metadataFile.exists()) JSONObject(metadataFile.readText()) else JSONObject()
            json.put("transcriptionStatus", status)
            if (transcript != null) json.put("transcript", transcript)
            if (error != null) json.put("transcriptionError", error) else json.remove("transcriptionError")
            metadataFile.writeText(json.toString(2))
        } catch (_: Exception) {
            // The audio remains safe even if its optional transcript metadata fails.
        }
    }

    private fun readableError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The recognizer could not read the audio"
        SpeechRecognizer.ERROR_CLIENT -> "The recognition request was cancelled"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Speech recognition permission missing"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Selected language is not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Offline language model is not downloaded"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline model unavailable"
        SpeechRecognizer.ERROR_NO_MATCH -> "No clear speech was recognised"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy; try Refresh later"
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "On-device recognizer unavailable"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech recognition error " + error
    }

    private const val CODEC_TIMEOUT_US = 10_000L
}
