package com.example.sample_app

import java.io.File

data class AudioFile(
    val file: File,
    val name: String,
    val path: String,
    val size: Long,
    val duration: Long = 0L,
    val dateCreated: Long = file.lastModified()
) {
    fun getFormattedSize(): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
    
    fun getFormattedDuration(): String {
        val seconds = duration / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }
    
    fun getFormattedDate(): String {
        val date = java.util.Date(dateCreated)
        val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        return formatter.format(date)
    }
}
