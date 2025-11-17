package com.example.savr_library

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Recorder(val callback: AudioCallback) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private var isListening = false

    private var sampleRate: Int = 0
    private var frameSize: Int = 0
    private var audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    fun start(sampleRate: Int, frameSize: Int, audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
        this.sampleRate = sampleRate
        this.frameSize = frameSize
        this.audioSource = audioSource
        stop()

        audioRecord = createAudioRecord()
        if (audioRecord != null) {
            isListening = true
            audioRecord?.startRecording()

            thread = Thread(ProcessVoice())
            thread?.start()
        }
    }

    fun stop() {
        isListening = false
        thread?.interrupt()
        thread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        try {
            val minBufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                2 * frameSize
            )

            val audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )

            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                return audioRecord
            } else {
                audioRecord.release()
            }
        } catch (e: IllegalArgumentException) {
            Log.e("Recorder", "Error can't create AudioRecord ", e)
        }
        return null
    }

    private inner class ProcessVoice : Runnable {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            while (!Thread.interrupted() && isListening) {
                val buffer = ShortArray(frameSize)
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    callback.onAudio(buffer)
                }
            }
        }
    }

    interface AudioCallback {
        fun onAudio(audioData: ShortArray)
    }
}
