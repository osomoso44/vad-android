package com.example.sample_app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.savr_library.AudioRecordingManager
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(), AudioRecordingManager.RecordingResultListener {
    private lateinit var audioRecordingManager: AudioRecordingManager
    private var mediaPlayer: MediaPlayer? = null

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var speechIndicator: TextView
    private lateinit var silenceTimeText: TextView
    private lateinit var recordingTimeText: TextView
    private lateinit var vadModeSpinner: Spinner
    private lateinit var silenceDurationEdit: EditText
    private lateinit var maxDurationEdit: EditText
    private lateinit var recordSpeechOnlySwitch: Switch
    private lateinit var vadMinSilenceEdit: EditText
    private lateinit var vadMinSpeechEdit: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // Audio Player Components
    private lateinit var audioListRecyclerView: RecyclerView
    private lateinit var currentAudioName: TextView
    private lateinit var audioProgressBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playPauseButton: Button
    private lateinit var playerStopButton: Button

    // Audio Management
    private var audioFiles = mutableListOf<AudioFile>()
    private var audioListAdapter: AudioListAdapter? = null
    private var currentAudioFile: AudioFile? = null
    private var isPlaying = false
    private var isUserSeeking = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    companion object {
        const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupVadModeSpinner()
        setupAudioRecordingManager()
        setupClickListeners()
        setupAudioPlayer()
        setupAudioList()
        loadAudioFiles()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        speechIndicator = findViewById(R.id.speechIndicator)
        silenceTimeText = findViewById(R.id.silenceTimeText)
        recordingTimeText = findViewById(R.id.recordingTimeText)
        vadModeSpinner = findViewById(R.id.vadModeSpinner)
        silenceDurationEdit = findViewById(R.id.silenceDurationEdit)
        maxDurationEdit = findViewById(R.id.maxDurationEdit)
        recordSpeechOnlySwitch = findViewById(R.id.recordSpeechOnlySwitch)
        vadMinSilenceEdit = findViewById(R.id.vadMinSilenceEdit)
        vadMinSpeechEdit = findViewById(R.id.vadMinSpeechEdit)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Audio Player Views
        audioListRecyclerView = findViewById(R.id.audioListRecyclerView)
        currentAudioName = findViewById(R.id.currentAudioName)
        audioProgressBar = findViewById(R.id.audioProgressBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        playPauseButton = findViewById(R.id.playPauseButton)
        playerStopButton = findViewById(R.id.playerStopButton)
    }

    private fun setupVadModeSpinner() {
        val vadModes = arrayOf("Mode 1 (50% confidence)", "Mode 2 (80% confidence)", "Mode 3 (95% confidence)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, vadModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vadModeSpinner.adapter = adapter
        vadModeSpinner.setSelection(0) // Default to mode 1
    }

    private fun setupAudioRecordingManager() {
        audioRecordingManager = AudioRecordingManager(
            context = this,
            listener = this,
            recordSpeechOnly = recordSpeechOnlySwitch.isChecked,
            vadMinimumSilenceDurationMs = vadMinSilenceEdit.text.toString().toIntOrNull() ?: 300,
            vadMinimumSpeechDurationMs = vadMinSpeechEdit.text.toString().toIntOrNull() ?: 30,
            vadMode = vadModeSpinner.selectedItemPosition + 1,
            silenceDurationMs = silenceDurationEdit.text.toString().toIntOrNull() ?: 5000,
            maxRecordingDurationMs = maxDurationEdit.text.toString().toIntOrNull() ?: 60000
        )
    }

    private fun setupClickListeners() {
        startButton.setOnClickListener {
            updateAudioRecordingManager()
            requestAudioPermissions()
        }

        stopButton.setOnClickListener {
            audioRecordingManager.stopRecording()
        }

        vadModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (::audioRecordingManager.isInitialized) {
                    audioRecordingManager.updateVadMode(position + 1)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateAudioRecordingManager() {
        if (::audioRecordingManager.isInitialized) {
            audioRecordingManager.updateVadMode(vadModeSpinner.selectedItemPosition + 1)
            audioRecordingManager.updateVadMinimumSilenceDurationMs(vadMinSilenceEdit.text.toString().toIntOrNull() ?: 300)
            audioRecordingManager.updateVadMinimumSpeechDurationMs(vadMinSpeechEdit.text.toString().toIntOrNull() ?: 30)
            audioRecordingManager.updateSilenceDurationMs(silenceDurationEdit.text.toString().toIntOrNull() ?: 5000)
            audioRecordingManager.updateMaxRecordingDurationMs(maxDurationEdit.text.toString().toIntOrNull() ?: 60000)
            audioRecordingManager.updateRecordSpeechOnly(recordSpeechOnlySwitch.isChecked)
        }
    }

    private fun requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION)
        } else {
            // Permission has already been granted
            audioRecordingManager.startRecording()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            runOnUiThread {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission was granted, start recording
                    audioRecordingManager.startRecording()
                } else {
                    // Permission denied, inform the user and possibly disable functionality
                    Toast.makeText(this@MainActivity, "Permission denied to record audio", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRecordingComplete(audioFilePath: String) {
        runOnUiThread {
            // Reload audio files list
            loadAudioFiles()
            
            // Auto-select the newly recorded file
            val newFile = File(audioFilePath)
            val newAudioFile = AudioFile(
                file = newFile,
                name = newFile.name,
                path = newFile.absolutePath,
                size = newFile.length()
            )
            selectAudio(newAudioFile)
            
            // Auto-play the new recording
            playAudio()
            
            Toast.makeText(this@MainActivity, "Recording complete. Playing audio...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRecordingError(errorMessage: String) {
        runOnUiThread {
            Log.e("MainActivity", "Recording error: $errorMessage")
            Toast.makeText(this@MainActivity, "Recording error: $errorMessage", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStatusUpdate(status: String, isSpeech: Boolean, silenceTime: Long, recordingTime: Long) {
        runOnUiThread {
            statusText.text = "Status: $status"
            speechIndicator.text = "Speech: ${if (isSpeech) "Yes" else "No"}"
            speechIndicator.setBackgroundColor(if (isSpeech) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
            silenceTimeText.text = "Silence Time: ${silenceTime}ms"
            recordingTimeText.text = "Recording Time: ${recordingTime}ms"
        }
    }

    private fun setupAudioPlayer() {
        playPauseButton.setOnClickListener {
            if (currentAudioFile != null) {
                if (isPlaying) {
                    pauseAudio()
                } else {
                    playAudio()
                }
            }
        }

        playerStopButton.setOnClickListener {
            stopAudio()
        }

        audioProgressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    isUserSeeking = true
                    val duration = mediaPlayer!!.duration
                    val newPosition = (progress * duration) / 100
                    mediaPlayer!!.seekTo(newPosition)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
            }
        })
    }

    private fun setupAudioList() {
        audioListRecyclerView.layoutManager = LinearLayoutManager(this)
        audioListAdapter = AudioListAdapter(audioFiles) { audioFile ->
            selectAudio(audioFile)
        }
        audioListRecyclerView.adapter = audioListAdapter
    }

    private fun loadAudioFiles() {
        audioFiles.clear()
        val audioDirectory = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        if (audioDirectory != null && audioDirectory.exists()) {
            val files = audioDirectory.listFiles { file ->
                file.isFile && file.name.endsWith(".wav")
            }
            files?.forEach { file ->
                val audioFile = AudioFile(
                    file = file,
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length()
                )
                audioFiles.add(audioFile)
            }
            audioFiles.sortByDescending { it.dateCreated }
        }
        audioListAdapter?.notifyDataSetChanged()
    }

    private fun selectAudio(audioFile: AudioFile) {
        currentAudioFile = audioFile
        currentAudioName.text = audioFile.name
        
        // Stop current playback
        stopAudio()
        
        // Load new audio
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.path)
                prepare()
                
                setOnPreparedListener {
                    this@MainActivity.totalTimeText.text = formatTime(duration)
                    this@MainActivity.audioProgressBar.max = 100
                }
                
                setOnCompletionListener {
                    this@MainActivity.isPlaying = false
                    this@MainActivity.playPauseButton.text = "▶"
                    this@MainActivity.audioProgressBar.progress = 0
                    this@MainActivity.currentTimeText.text = "00:00"
                    this@MainActivity.progressHandler.removeCallbacks(progressRunnable)
                }
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Error loading audio: ${e.message}")
            Toast.makeText(this, "Error loading audio file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAudio() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                isPlaying = true
                playPauseButton.text = "⏸"
                progressHandler.post(progressRunnable)
            }
        }
    }

    private fun pauseAudio() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                playPauseButton.text = "▶"
                progressHandler.removeCallbacks(progressRunnable)
            }
        }
    }

    private fun stopAudio() {
        mediaPlayer?.let { player ->
            player.stop()
            player.seekTo(0)
            isPlaying = false
            playPauseButton.text = "▶"
            audioProgressBar.progress = 0
            currentTimeText.text = "00:00"
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun updateProgress() {
        mediaPlayer?.let { player ->
            if (player.isPlaying && !isUserSeeking) {
                val currentPosition = player.currentPosition
                val duration = player.duration
                
                if (duration > 0) {
                    val progress = (currentPosition * 100) / duration
                    audioProgressBar.progress = progress
                    currentTimeText.text = formatTime(currentPosition)
                }
            }
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecordingManager.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
