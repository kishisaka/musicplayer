package com.idk.musicplayer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(var onCLickSongCallBack: MusicActivity.OnClickSongCallBack):
RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songList:List<Song> = listOf()
    private var selectedPos = RecyclerView.NO_POSITION

    class SongViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        var songName: TextView = itemView.findViewById(R.id.track_name)
        var artistName: TextView = itemView.findViewById(R.id.artist_name)
    }
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.track_cell,
            parent, false)
        return SongViewHolder(view)
    }

    @SuppressLint("RecyclerView")
    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.itemView.isSelected = (selectedPos == position)
        holder.songName.text = songList[position].title
        holder.artistName.text = songList[position].artist
        holder.itemView.setOnClickListener {
            onCLickSongCallBack.onClick(position)
            selectedPos = position

            // reset all selected items in the list!
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return songList.size
    }

    fun setSongList(songList: List<Song>) {
        this.songList = songList
    }

    fun setSongIndex(index: Int) {
        selectedPos = index
        notifyDataSetChanged()
    }
}