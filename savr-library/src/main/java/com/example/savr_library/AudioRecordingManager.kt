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
                            private var recordSpeechOnly: Boolean = true,
                            vadMinimumSilenceDurationMs: Int = 300,
                            vadMinimumSpeechDurationMs: Int = 30,
                            vadMode: Int = 2,
                            private var silenceDurationMs: Int = 1500,
                            private var maxRecordingDurationMs: Int = 60000) : Recorder.AudioCallback {
    private var isRecording: Boolean = false
    private var isVadActive: Boolean = false
    private var currentAudioFile: File? = null
    private var lastRecordedFile: File? = null
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
    private var currentFileData = mutableListOf<Short>()

    interface RecordingResultListener {
        fun onRecordingComplete(audioFilePath: String)
        fun onRecordingError(errorMessage: String)
        fun onStatusUpdate(status: String, isSpeech: Boolean, silenceTime: Long, recordingTime: Long)
        fun onNewRecordingStarted(audioFilePath: String)
    }

    @SuppressLint("MissingPermission")
    fun startVadDetection() {
        if (isVadActive) {
            return
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            listener.onRecordingError("External storage is not available")
            return
        }

        try {
            isVadActive = true
            hasSpoken = false
            speechData.clear()
            currentFileData.clear()
            silenceStartTime = 0
            recordingStartTime = System.currentTimeMillis()

            recorder.start(vad.sampleRate.value, vad.frameSize.value)
        } catch (e: IOException) {
            listener.onRecordingError("Failed to start VAD detection: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        startVadDetection()
    }

    private fun startSilenceDetection() {
        recordingStartTime = System.currentTimeMillis()
        recorder.start(vad.sampleRate.value, vad.frameSize.value)
    }

    fun stopRecording() {
        if (!isRecording && !isVadActive) {
            return
        }

        try {
            if (isRecording && currentAudioFile != null) {
                saveCurrentRecording()
            }
            isRecording = false
            isVadActive = false
            recorder.stop()
        } catch (e: RuntimeException) {
            listener.onRecordingError("Failed to stop recording: ${e.message}")
        }
    }

    fun stopVadDetection() {
        stopRecording()
    }

    fun isRecording(): Boolean {
        return isRecording
    }

    private fun playBeep() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 500)
    }

    override fun onAudio(audioData: ShortArray) {
        if (!isVadActive) {
            return
        }

        val totalRecordingTime = System.currentTimeMillis() - recordingStartTime
        val isSpeech = vad.isSpeech(audioData)

        if (isSpeech) {
            if (!isRecording) {
                startNewRecording()
            }
            hasSpoken = true
            silenceStartTime = 0
            currentFileData.addAll(audioData.toList())
            speechData.addAll(audioData.toList())
        } else {
            if (isRecording) {
                if (silenceStartTime == 0L) {
                    silenceStartTime = System.currentTimeMillis()
                } else {
                    val elapsedTime = System.currentTimeMillis() - silenceStartTime
                    if (elapsedTime >= silenceDurationMs) {
                        completeCurrentRecording()
                    } else {
                        if (!recordSpeechOnly) {
                            currentFileData.addAll(audioData.toList())
                        }
                    }
                }
            } else {
                if (!recordSpeechOnly && hasSpoken) {
                    currentFileData.addAll(audioData.toList())
                }
            }
        }

        val currentSilenceTime = if (isRecording && silenceStartTime > 0) {
            System.currentTimeMillis() - silenceStartTime
        } else 0L

        val status = when {
            !isVadActive -> "Stopped"
            !isRecording && !hasSpoken -> "Listening for speech..."
            isRecording && isSpeech -> "Recording speech"
            isRecording -> "Silence detected (${currentSilenceTime}ms)"
            else -> "Listening for speech..."
        }

        listener.onStatusUpdate(status, isSpeech, currentSilenceTime, totalRecordingTime)

        if (isRecording && totalRecordingTime >= maxRecordingDurationMs) {
            completeCurrentRecording()
        }
    }

    private fun startNewRecording() {
        try {
            val audioDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (audioDirectory != null) {
                val fileName = "recording_${System.currentTimeMillis()}.wav"
                currentAudioFile = File(audioDirectory, fileName)
                currentFileData.clear()
                isRecording = true
                hasSpoken = true
                recordingStartTime = System.currentTimeMillis()
                silenceStartTime = 0

                listener.onNewRecordingStarted(currentAudioFile!!.absolutePath)
            }
        } catch (e: IOException) {
            listener.onRecordingError("Failed to create new recording file: ${e.message}")
        }
    }

    private fun completeCurrentRecording() {
        if (currentAudioFile != null && currentFileData.isNotEmpty()) {
            saveCurrentRecording()
        }
        isRecording = false
        currentAudioFile = null
        currentFileData.clear()
        silenceStartTime = 0
    }

    private fun saveCurrentRecording() {
        if (currentAudioFile == null || currentFileData.isEmpty()) {
            return
        }

        try {
            val outputStream = RandomAccessFile(currentAudioFile, "rw")
            try {
                writeWavHeader(outputStream, AudioFormat.CHANNEL_IN_MONO, vad.sampleRate.value)
                outputStream.write(shortArrayToByteArray(currentFileData.toShortArray()))
                updateWavHeader(outputStream)
                lastRecordedFile = currentAudioFile
                listener.onRecordingComplete(currentAudioFile!!.absolutePath)
            } finally {
                outputStream.close()
            }
        } catch (e: IOException) {
            listener.onRecordingError("Failed to save recording: ${e.message}")
        }
    }

    fun getLastRecordedFile(): File? {
        return lastRecordedFile
    }

    fun isVadActive(): Boolean {
        return isVadActive
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

    fun updateVadMode(mode: Int) {
        val wasActive = isVadActive
        val wasRecording = isRecording
        val currentSilenceDuration = vad.silenceDurationMs
        val currentSpeechDuration = vad.speechDurationMs

        if (wasRecording && currentAudioFile != null) {
            saveCurrentRecording()
            isRecording = false
            currentAudioFile = null
            currentFileData.clear()
        }

        if (wasActive) {
            recorder.stop()
        }

        vad.close()
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.entries.find { it.value == mode }!!)
            .setSilenceDurationMs(currentSilenceDuration)
            .setSpeechDurationMs(currentSpeechDuration)
            .build()

        if (wasActive) {
            recorder.start(vad.sampleRate.value, vad.frameSize.value)
        }
    }

    fun updateVadMinimumSilenceDurationMs(duration: Int) {
        val wasActive = isVadActive
        val wasRecording = isRecording
        val currentMode = vad.mode.value
        val currentSpeechDuration = vad.speechDurationMs

        if (wasRecording && currentAudioFile != null) {
            saveCurrentRecording()
            isRecording = false
            currentAudioFile = null
            currentFileData.clear()
        }

        if (wasActive) {
            recorder.stop()
        }

        vad.close()
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.entries.find { it.value == currentMode }!!)
            .setSilenceDurationMs(duration)
            .setSpeechDurationMs(currentSpeechDuration)
            .build()

        if (wasActive) {
            recorder.start(vad.sampleRate.value, vad.frameSize.value)
        }
    }

    fun updateVadMinimumSpeechDurationMs(duration: Int) {
        val wasActive = isVadActive
        val wasRecording = isRecording
        val currentMode = vad.mode.value
        val currentSilenceDuration = vad.silenceDurationMs

        if (wasRecording && currentAudioFile != null) {
            saveCurrentRecording()
            isRecording = false
            currentAudioFile = null
            currentFileData.clear()
        }

        if (wasActive) {
            recorder.stop()
        }

        vad.close()
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.entries.find { it.value == currentMode }!!)
            .setSilenceDurationMs(currentSilenceDuration)
            .setSpeechDurationMs(duration)
            .build()

        if (wasActive) {
            recorder.start(vad.sampleRate.value, vad.frameSize.value)
        }
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