package com.idk.musicplayer

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import android.os.IBinder
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MusicActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback {

    companion object {
        private const val PERMISSION_REQUEST_READ_EXTERNAL = 0

        // music repeat states
        private const val SINGLE = "single"
        private const val REPEAT = "repeat"

        const val COM_IDK_PAUSE_SONG = "com.idk.pause_song"
        const val COM_IDK_PLAY_SONG = "com.idk.play_song"
        const val COM_IDK_PLAY_PAUSE_SONG = "com.idk.play_pause_song"
        const val COM_IDK_MUSIC_DONE = "com.idk.music_done"
        const val COM_IDK_PLAY_NEXT_SONG = "com.idk.play_next_song"
        const val COM_IDK_TIME_UPDATE = "com.idk.time_update"
        const val COM_IDK_MUSIC_SEEK_START = "com.idk.music_seek_start"
        const val COM_IDK_MUSIC_SEEK_STOP = "com.idk.music_seek_stop"
        const val SONG_ID = "song_id"
        const val DURATION = "duration"
        const val CURRENT_TIME = "current_time"
        const val SEEK_VALUE = "seek_value"
    }

    var player: MediaPlayerService? = null
    var adapter: MusicAdapter? = null
    var songViewAdapter: SongAdapter? = null
    var serviceBound = false
    var count = 1
    var currentSongList: List<Song>? = null
    var musicTitle: TextView? = null
    var repeatButtonState: String = REPEAT
    var seekBar: SeekBar? = null
    var songView: RecyclerView? = null
    var musicDuration: TextView? = null
    var playPauseButton: ImageButton? = null

    // remember our receivers for clean out when playing new directory
    val broadcastReceivers = Stack<BroadcastReceiver>()

    val serviceConnection: ServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaPlayerService.LocalBinder
            player = binder.getService()
            serviceBound = true
            Toast.makeText(this@MusicActivity, "Service Bound", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    /**
     * clear all current broadcast receivers that were previously set, we need to do this when
     * user hits next/previous buttons so the previously queued song is not played, only
     * the newly queued song after the next (now current) song is done.
     */
    private fun clearBroadcastReceivers() {
        while (broadcastReceivers.isNotEmpty()) {
            val broadcastReceiver = broadcastReceivers.pop()
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (e: Exception) {
                println("kurt_test ignore, receiver already unregistered")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        songView = findViewById(R.id.song_view)
        songViewAdapter = SongAdapter(object: OnClickSongCallBack {
            override fun onClick(songIndex: Int) {
                count = songIndex
                currentSongList?.let {
                    playMusic(it)
                }
            }
        })
        songView?.let {
            it.adapter = songViewAdapter
            it.layoutManager =
                SnappingLinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        }

        adapter = MusicAdapter(object : OnClickCallBack {
            override fun onClick(album: Album) {
                //initialize count to first song
                count = 0
                currentSongList = getTracksForAlbum(album.id)
                // clear out broadcast receivers so we don't inadvertently play previous song lists.
                playMusic(getTracksForAlbum(album.id))
                currentSongList?.let {
                    songViewAdapter?.setSongList(it)
                    songViewAdapter?.notifyDataSetChanged()
                }
            }
        })

        val recyclerView: RecyclerView = findViewById(R.id.music_directory_list)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        musicTitle = findViewById<TextView>(R.id.music_title)
        musicTitle?.isSelected = true
        musicDuration = findViewById(R.id.music_duration)
        setupButtonCLickListeners()
        requestPermission()

        seekBar = findViewById(R.id.music_status)
        seekBar?.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                sendBroadcast(Intent(COM_IDK_MUSIC_SEEK_START))
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val intent = Intent(COM_IDK_MUSIC_SEEK_STOP)
                intent.putExtra(SEEK_VALUE, seekBar?.progress)
                sendBroadcast(intent)
            }

        })

        registerReceiver(object: BroadcastReceiver(){
            override fun onReceive(context: Context?, intent: Intent?) {
                playPauseButton?.setImageResource(R.drawable.ic_play_arrow_fill0_wght400_grad0_opsz48)
            }
        }, IntentFilter(COM_IDK_PAUSE_SONG))

        registerReceiver(object: BroadcastReceiver(){
            override fun onReceive(context: Context?, intent: Intent?) {
                playPauseButton?.setImageResource(R.drawable.ic_pause_fill0_wght400_grad0_opsz48)
            }
        }, IntentFilter(COM_IDK_PLAY_SONG))
    }


    private fun setupButtonCLickListeners() {
        val repeatButton: ImageButton = findViewById(R.id.music_repeat)
        // initialize repeat button state
        repeatButton.setImageResource(R.drawable.ic_repeat_fill0_wght400_grad0_opsz48)
        repeatButtonState = REPEAT
        repeatButton.setOnClickListener(object: View.OnClickListener{
            override fun onClick(v: View?) {
                if (repeatButtonState.equals(SINGLE)) {
                    repeatButton.setImageResource(R.drawable.ic_repeat_fill0_wght400_grad0_opsz48)
                    repeatButtonState = REPEAT
                } else {
                    repeatButton.setImageResource(R.drawable.ic_repeat_one_fill0_wght400_grad0_opsz48)
                    repeatButtonState = SINGLE
                }
            }
        })

        playPauseButton = findViewById(R.id.play_pause)
        playPauseButton?.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val playPauseIntent = Intent(COM_IDK_PLAY_PAUSE_SONG)
                sendBroadcast(playPauseIntent)
            }
        })

        val nextButton: ImageButton = findViewById(R.id.next)
        nextButton.setOnClickListener(object: View.OnClickListener {
            override fun onClick(v: View?) {
                currentSongList?.let{ songList ->
                    if (count + 1 > songList.size) {
                        count = 0
                    }
                    playMusic(songList)
                }
            }
        })

        val previousButton: ImageButton = findViewById(R.id.previous)
        previousButton.setOnClickListener(object: View.OnClickListener {
            override fun onClick(v: View?) {
                currentSongList?.let{ songList ->
                    if (count - 2 >= 0) {
                        count = count - 2
                    } else {
                        count = songList.size - 1
                    }
                    playMusic(songList)
                }
            }
        })
    }

    /**
     * This one is a bit weird, it will play the current song specified by the count variable and
     * prepare a broadcast receiver that will listen to a signal that specifies the end of the current song,
     * then play the next song. This is done by broadcast to the service. The
     * service will then call back this method via another broadcast to play the next song and
     * queue up the next song afterwards. This will continue till there are no more songs in the
     * list. All broadcast receivers are captured in a stack that will be unregistered
     * when a different folder is chosen to be played. See onCreate() in the MusicAdapter setup.
     */
    private fun playMusic(songList: List<Song>) {
        clearBroadcastReceivers()
        currentSongList = songList
        if (!serviceBound) {
            // first time setup of the music service
            songView?.smoothScrollToPosition(count)
            songViewAdapter?.setSongIndex(count)
            registerTimeCounter()
            registerMusicDoneListener(songList)
            val playerIntent = Intent(this, MediaPlayerService::class.java)
            playerIntent.putExtra(SONG_ID, songList[count].id)
            musicTitle?.text = songList[count].title
            startService(playerIntent)
            bindService(playerIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            count += 1
        } else {
            // music service already set, reset counter, set path and play set!
            songView?.smoothScrollToPosition(count)
            songViewAdapter?.setSongIndex(count)
            registerTimeCounter()
            queNextSong(songList)
            registerMusicDoneListener(songList)
        }
    }

    private fun registerTimeCounter() {
        val broadcastReceiver = object: BroadcastReceiver(){
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { timeInfo ->
                    val duration = timeInfo.getIntExtra(DURATION, 1).toDouble()
                    val currentTime = timeInfo.getIntExtra(CURRENT_TIME, 0).toDouble()
                    seekBar?.progress = (currentTime.div(duration) * 100).toInt()
                    musicDuration?.text = "${getTimeFromMillis(currentTime)} / ${getTimeFromMillis(duration)}"
                }
            }
        }
        broadcastReceivers.push(broadcastReceiver)
        registerReceiver(broadcastReceiver, IntentFilter(COM_IDK_TIME_UPDATE))
    }

    private fun getTimeFromMillis(millis: Double) = SimpleDateFormat("m:ss").format(millis.toLong())

    private fun registerMusicDoneListener(songList: List<Song>) {
        val broadcastReceiver = object: BroadcastReceiver(){
            override fun onReceive(context: Context?, intent: Intent?) {
                if (repeatButtonState == REPEAT) {
                    if (count >= songList.size) {
                        count = 0
                    }
                }
                if (count < songList.size) {
                    queNextSong(songList)
                }
            }
        }
        broadcastReceivers.push(broadcastReceiver)
        registerReceiver(broadcastReceiver, IntentFilter(COM_IDK_MUSIC_DONE))
    }

    private fun queNextSong(songList: List<Song>) {
        songView?.smoothScrollToPosition(count)
        songViewAdapter?.setSongIndex(count)
        val playNextSongIntent = Intent(COM_IDK_PLAY_NEXT_SONG)
        playNextSongIntent.putExtra(SONG_ID, songList[count].id)
        musicTitle?.text = songList[count].title
        sendBroadcast(playNextSongIntent)
        count += 1
    }

    /**
     * only used when directly reading music files from file system, use getAlbums() and
     * getTracksForAlbum() instead.
     */
    private fun cleanSongList(songList: List<File>): List<File> {
        val cleanList = mutableListOf<File>()
        for(song in songList) {
            if (song.isFile && (song.absolutePath.endsWith(".mp3")
                        || song.absolutePath.endsWith(".ogg")
                        || song.absolutePath.endsWith(".m4a")
                        || song.absolutePath.endsWith(".wav"))) {
                cleanList.add(song)
            }
        }
        return cleanList
    }

    /**
     * only used when directly reading music files from file system, use getAlbums() and
     * getTracksForAlbum() instead.
     */
    private fun getMusicDirectories(): List<File> {
        val musicDirectories = mutableListOf<File>()
        val externalStorageMusic = File("/storage/emulated/0/Music")
        if (externalStorageMusic.isDirectory) {
            for (directoryItem in externalStorageMusic.listFiles()) {
                if (directoryItem.isDirectory && directoryItem.listFiles().size > 0) {
                    musicDirectories.add(directoryItem)
                }
            }
        }
        return musicDirectories
    }

    /**
     * see https://stackoverflow.com/questions/18926633/list-of-albums-from-mediastore-to-adapter-listview,
     * https://stackoverflow.com/questions/16771636/where-clause-in-contentproviders-query-in-android
     * Google's content provider docs are wrong!
     */
    @SuppressLint("Range")
    private fun getAlbums(): List<Album> {
        val albums = mutableListOf<Album>()
        val resolver: ContentResolver = contentResolver
        val uri = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
        val columns:Array<String> = arrayOf(MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM)
        val order = "${MediaStore.Audio.Albums.ALBUM} ASC"
        val cursor = resolver.query(uri, columns, null, null, order, null)
        cursor?.let { cursor ->
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                albums.add(
                    Album(
                        cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM)),
                        cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Albums._ID)),
                        0
                    )
                )
                cursor.moveToNext()
            }
        }
        cursor?.close()
        // fill in the track counts here!
        for(album in albums) {
            album.trackCount = getTracksForAlbum(album.id).size
        }
        return albums
    }

    /**
     * see https://stackoverflow.com/questions/18926633/list-of-albums-from-mediastore-to-adapter-listview,
     * https://stackoverflow.com/questions/16771636/where-clause-in-contentproviders-query-in-android
     * Google's content provider docs are wrong!
     */
    @SuppressLint("Range")
    private fun getTracksForAlbum(albumId: String): List<Song> {
        val musicList = mutableListOf<Song>()
        val resolver: ContentResolver = contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val where = "${MediaStore.Audio.Media.ALBUM_ID} = $albumId"
        val columns:Array<String> = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val cursor = resolver.query(uri, columns, where, null, null, null)
        cursor?.let { cursor ->
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                musicList.add(
                    Song(
                        cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)),
                        cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)),
                        cursor.getLong(cursor.getColumnIndex(MediaStore.Audio.Media._ID))
                    )
                )
                cursor.moveToNext()
            }
        }
        cursor?.close()
        return musicList
    }

    private fun requestPermission() {
        if (ContextCompat.checkSelfPermission(this, READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_READ_EXTERNAL)
        } else {
            adapter?.setMusicDirectories(getAlbums())
            adapter?.notifyDataSetChanged()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when(requestCode) {
            PERMISSION_REQUEST_READ_EXTERNAL -> {
                if (grantResults.isNotEmpty() && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    adapter?.setMusicDirectories(getAlbums())
                    adapter?.notifyDataSetChanged()
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    interface OnClickCallBack {
        fun onClick(id: Album)
    }

    interface OnClickSongCallBack {
        fun onClick(songIndex: Int)
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putBoolean("ServiceState", serviceBound)
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        serviceBound = savedInstanceState.getBoolean("ServiceState")
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            player?.stopSelf()
        }
        clearBroadcastReceivers()
        super.onDestroy()
    }
}