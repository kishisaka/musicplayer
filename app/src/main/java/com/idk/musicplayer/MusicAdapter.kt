package com.idk.musicplayer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MusicAdapter(var onCLickCallBack: MusicActivity.OnClickCallBack):
    RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    private var musicList:List<Album> = listOf()
    private var selectedPos = RecyclerView.NO_POSITION

    class MusicViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        var musicDirectory: TextView = itemView.findViewById(R.id.name)
        var trackCount: TextView = itemView.findViewById(R.id.track_count)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): MusicViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.music_directory_cell,
            parent, false)
        return MusicViewHolder(view)
    }

    @SuppressLint("RecyclerView")
    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        holder.itemView.isSelected = (selectedPos == position)
        holder.musicDirectory.text = musicList[position].name
        holder.trackCount.text = musicList[position].trackCount.toString()
        holder.itemView.setOnClickListener {
            onCLickCallBack.onClick(musicList[position])
            selectedPos = position
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return musicList.size
    }

    fun setMusicDirectories(musicList:List<Album>) {
        this.musicList = musicList

    }
}