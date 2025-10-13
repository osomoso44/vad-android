package com.example.savr_library

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Environment
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecordingManager(private val context: Context,
                            private val listener: RecordingResultListener,
                            private var recordSpeechOnly: Boolean = false,
                            vadMinimumSilenceDurationMs: Int = 300,
                            vadMinimumSpeechDurationMs: Int = 30,
                            vadMode: Int = 1,
                            private var silenceDurationMs: Int = 5000,
                            private var maxRecordingDurationMs: Int = 60000) : Recorder.AudioCallback {
    private var isRecording: Boolean = false
    private lateinit var audioFile: File
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private var vad: VadSilero = Vad.builder()
        .setContext(context)
        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
        .setFrameSize(FrameSize.FRAME_SIZE_512)
        .setMode(Mode.entries.find { it.value == vadMode}!!)
        .setSilenceDurationMs(vadMinimumSilenceDurationMs)
        .setSpeechDurationMs(vadMinimumSpeechDurationMs)
        .build();
    private var recorder: Recorder = Recorder(this)
    private var silenceStartTime: Long = 0
    private var hasSpoken: Boolean = false
    private var recordingStartTime: Long = 0
    private val speechData = mutableListOf<Short>()

    interface RecordingResultListener {
        fun onRecordingComplete(audioFilePath: String)
        fun onRecordingError(errorMessage: String)
        fun onStatusUpdate(status: String, isSpeech: Boolean, silenceTime: Long, recordingTime: Long)
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) {
            return
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            listener.onRecordingError("External storage is not available")
            return
        }

        try {
            val audioDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (audioDirectory != null) {
                val fileName = "recording_${System.currentTimeMillis()}.wav"
                audioFile = File(audioDirectory, fileName)

                isRecording = true
                hasSpoken = false
                speechData.clear()

                playBeep()
                startSilenceDetection()
            } else {
                listener.onRecordingError("Failed to access audio directory")
            }
        } catch (e: IOException) {
            listener.onRecordingError("Failed to start recording: ${e.message}")
        }
    }

    private fun startSilenceDetection() {
        recordingStartTime = System.currentTimeMillis()
        recorder.start(vad.sampleRate.value, vad.frameSize.value)
    }

    fun stopRecording() {
        if (!isRecording) {
            return
        }

        try {
            isRecording = false

            recorder.stop()
        } catch (e: RuntimeException) {
            listener.onRecordingError("Failed to stop recording: ${e.message}")
        }
    }

    fun isRecording(): Boolean {
        return isRecording
    }

    private fun playBeep() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 500)
    }

    override fun onAudio(audioData: ShortArray) {
        val totalRecordingTime = System.currentTimeMillis() - recordingStartTime
        val isSpeech = vad.isSpeech(audioData)

        if (isSpeech) {
            hasSpoken = true
            silenceStartTime = 0
            speechData.addAll(audioData.toList())
        } else {
            if (hasSpoken) {
                if (silenceStartTime == 0L) {
                    silenceStartTime = System.currentTimeMillis()
                } else {
                    val elapsedTime = System.currentTimeMillis() - silenceStartTime
                    if (elapsedTime >= silenceDurationMs) {
                        completeRecording()
                    }
                }

                if (!recordSpeechOnly) {
                    speechData.addAll(audioData.toList())
                }
            }
        }

        // Update status
        val currentSilenceTime = if (hasSpoken && silenceStartTime > 0) {
            System.currentTimeMillis() - silenceStartTime
        } else 0L

        val status = when {
            !isRecording -> "Stopped"
            !hasSpoken -> "Listening for speech..."
            isSpeech -> "Recording speech"
            else -> "Silence detected (${currentSilenceTime}ms)"
        }

        listener.onStatusUpdate(status, isSpeech, currentSilenceTime, totalRecordingTime)

        if (totalRecordingTime >= maxRecordingDurationMs) {
            completeRecording()
        }
    }

    private fun completeRecording() {
        playBeep()
        stopRecording()

        // Write speech data to the output file
        val outputStream = RandomAccessFile(audioFile, "rw")
        try {
            writeWavHeader(outputStream, AudioFormat.CHANNEL_IN_MONO, vad.sampleRate.value)
            outputStream.write(shortArrayToByteArray(speechData.toShortArray()))
            updateWavHeader(outputStream)
        } finally {
            outputStream.close()
        }

        listener.onRecordingComplete(audioFile.absolutePath)
    }

    private fun writeWavHeader(file: RandomAccessFile?, channelConfig: Int, sampleRate: Int) {
        val byteRate = sampleRate * 2
        val blockAlign = 2
        val bitsPerSample = 16
        val dataSize = 0 // Placeholder, update after recording
        val subChunk2Size = dataSize * channelConfig * bitsPerSample / 8
        val chunkSize = 36 + subChunk2Size

        val header = ByteBuffer.allocate(44)
        header.order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // Audio format (PCM)
        header.putShort(1) // Number of channels
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(subChunk2Size)

        file?.write(header.array())
    }

    private fun updateWavHeader(file: RandomAccessFile?) {
        file?.let {
            it.seek(4)  // Move to file size position
            val fileSize = it.length()
            it.writeInt((fileSize - 8).toInt())  // Update file size

            it.seek(40)  // Move to data chunk size position
            it.writeInt((fileSize - 44).toInt())  // Update data chunk size
        }
    }

    private fun shortArrayToByteArray(shortArray: ShortArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(shortArray.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in shortArray) {
            byteBuffer.putShort(sample)
        }
        return byteBuffer.array()
    }

    fun onDestroy() {
        recorder.stop()
        vad.close()
    }

    // Getters for current parameters
    fun getVadMode(): Int = vad.mode.value
    fun getSilenceDurationMs(): Int = silenceDurationMs
    fun getMaxRecordingDurationMs(): Int = maxRecordingDurationMs
    fun getRecordSpeechOnly(): Boolean = recordSpeechOnly
    fun getVadMinimumSilenceDurationMs(): Int = vad.silenceDurationMs
    fun getVadMinimumSpeechDurationMs(): Int = vad.speechDurationMs

    // Setters for parameters (requires restart to take effect)
    fun updateVadMode(mode: Int) {
        vad.close()
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.entries.find { it.value == mode }!!)
            .setSilenceDurationMs(vad.silenceDurationMs)
            .setSpeechDurationMs(vad.speechDurationMs)
            .build()
    }

    fun updateVadMinimumSilenceDurationMs(duration: Int) {
        vad.close()
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.entries.find { it.value == vad.mode.value }!!)
            .setSilenceDurationMs(duration)
            .setSpeechDurationMs(vad.speechDurationMs)
            .build()
    }

    fun updateVadMinimumSpeechDurationMs(duration: Int) {
        vad.close()
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.entries.find { it.value == vad.mode.value }!!)
            .setSilenceDurationMs(vad.silenceDurationMs)
            .setSpeechDurationMs(duration)
            .build()
    }

    fun updateSilenceDurationMs(duration: Int) {
        silenceDurationMs = duration
    }

    fun updateMaxRecordingDurationMs(duration: Int) {
        maxRecordingDurationMs = duration
    }

    fun updateRecordSpeechOnly(recordOnly: Boolean) {
        recordSpeechOnly = recordOnly
    }
}