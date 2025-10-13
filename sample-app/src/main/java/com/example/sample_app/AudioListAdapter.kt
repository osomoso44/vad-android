package com.example.sample_app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AudioListAdapter(
    private val audioFiles: List<AudioFile>,
    private val onItemClick: (AudioFile) -> Unit
) : RecyclerView.Adapter<AudioListAdapter.AudioViewHolder>() {

    class AudioViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.audioNameText)
        val dateText: TextView = itemView.findViewById(R.id.audioDateText)
        val sizeText: TextView = itemView.findViewById(R.id.audioSizeText)
        val durationText: TextView = itemView.findViewById(R.id.audioDurationText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_file, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val audioFile = audioFiles[position]
        
        holder.nameText.text = audioFile.name
        holder.dateText.text = audioFile.getFormattedDate()
        holder.sizeText.text = audioFile.getFormattedSize()
        holder.durationText.text = audioFile.getFormattedDuration()
        
        holder.itemView.setOnClickListener {
            onItemClick(audioFile)
        }
    }

    override fun getItemCount(): Int = audioFiles.size
}
