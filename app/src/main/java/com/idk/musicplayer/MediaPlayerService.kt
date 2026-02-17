package com.idk.musicplayer

import android.app.Service
import android.content.*
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.os.Binder
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import java.util.*

/**
 * check out https://www.sitepoint.com/a-step-by-step-guide-to-building-an-android-audio-player-app/
 * how to use id in content resolver to get a playable URI:
 * https://stackoverflow.com/questions/57093479/get-real-path-from-uri-data-is-deprecated-in-android-q/57093905#57093905
 */
class MediaPlayerService: Service(), MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener,
    MediaPlayer.OnInfoListener, MediaPlayer.OnBufferingUpdateListener, AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var resumePosition = 0
    private var audioManager: AudioManager? = null
    private var mediaSession: MediaSession? = null
    private var songId: Long? = null
    private var songTitle: String? = null
    private var songArtist: String? = null
    private var songAlbum: String?  = null
    private var isSeeking = false
    private var seekBarTimer: Timer? = null
    private val broadcastReceivers = mutableListOf<BroadcastReceiver>()
    private var receiversRegistered = false

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("MediaPlayerService", "onStartCommand called")
        try {
            //An audio id is passed to the service through putExtra();
            val songId = intent.extras?.getLong(MusicActivity.SONG_ID)
            songId?.let {
                this.songId = it
                Log.d("MediaPlayerService", "Received song ID from intent: $it")
            }
        } catch (e: NullPointerException) {
            Log.e("MediaPlayerService", "Error getting song ID from intent", e)
            stopSelf()
        }

        // Only register receivers once per service instance
        if (!receiversRegistered) {
            registerBroadcastReceivers()
            receiversRegistered = true
        } else {
            Log.d("MediaPlayerService", "Receivers already registered, skipping")
        }

        //Request audio focus
        if (requestAudioFocus() == false) {
            //Could not gain focus
            Log.w("MediaPlayerService", "Failed to get audio focus")
            stopSelf()
        }

        if (songId != null)
            initMediaPlayer()

        return super.onStartCommand(intent, flags, startId)
    }

    private fun registerBroadcastReceivers() {
        val seekStartReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isSeeking = true
            }
        }
        broadcastReceivers.add(seekStartReceiver)
        registerReceiver(seekStartReceiver, IntentFilter(MusicActivity.COM_IDK_MUSIC_SEEK_START), Context.RECEIVER_NOT_EXPORTED)

        val seekStopReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isSeeking = false
                intent?.let {
                    seekMedia(intent.getIntExtra(MusicActivity.SEEK_VALUE, 0))
                }
            }
        }
        broadcastReceivers.add(seekStopReceiver)
        registerReceiver(seekStopReceiver, IntentFilter(MusicActivity.COM_IDK_MUSIC_SEEK_STOP), Context.RECEIVER_NOT_EXPORTED)

        val playNextReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                songId = intent?.extras?.getLong(MusicActivity.SONG_ID)
                songTitle = intent?.extras?.getString(MusicActivity.SONG_TITLE)
                songArtist = intent?.extras?.getString(MusicActivity.SONG_ARTIST)
                songAlbum = intent?.extras?.getString(MusicActivity.SONG_ALBUM)
                playNextTrack()
            }
        }
        broadcastReceivers.add(playNextReceiver)
        registerReceiver(playNextReceiver, IntentFilter(MusicActivity.COM_IDK_PLAY_NEXT_SONG), Context.RECEIVER_NOT_EXPORTED)

        val playPauseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pauseMedia()
            }
        }
        broadcastReceivers.add(playPauseReceiver)
        registerReceiver(playPauseReceiver, IntentFilter(MusicActivity.COM_IDK_PLAY_PAUSE_SONG), Context.RECEIVER_NOT_EXPORTED)
    }

    fun initMediaPlayer() {
        mediaPlayer = MediaPlayer()
        songId?.let {id ->
            mediaPlayer?.let { mp ->
                mp.setOnErrorListener(this)
                mp.setOnPreparedListener(this)
                mp.setOnBufferingUpdateListener(this)
                mp.setOnSeekCompleteListener(this)
                mp.setOnInfoListener(this)
                mp.reset()
                mp.setDataSource(applicationContext, getContentUri(id))
                mp.setOnCompletionListener {
                    val intent = Intent(MusicActivity.COM_IDK_MUSIC_DONE)
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                }
                mp.prepareAsync()
            }
        }
        startUpdatingSeekBar()
        mediaSession = MediaSession(this, "Music Player").apply {
            isActive = true
        }
    }

    private fun startUpdatingSeekBar() {
        seekBarTimer = Timer()
        seekBarTimer?.schedule(object: TimerTask(){
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying && !isSeeking) {
                        val intent = Intent(MusicActivity.COM_IDK_TIME_UPDATE)
                        intent.setPackage(packageName)
                        intent.putExtra(MusicActivity.CURRENT_TIME, it.currentPosition)
                        intent.putExtra(MusicActivity.DURATION, it.duration)
                        sendBroadcast(intent)
                    }
                }
            }
        }, 0 , 1000)
    }

    private val iBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        return iBinder
    }

    override fun onDestroy() {
        // Cancel the seek bar timer
        seekBarTimer?.cancel()
        seekBarTimer?.purge()

        // Unregister all broadcast receivers
        for (receiver in broadcastReceivers) {
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Receiver may have already been unregistered
            }
        }
        broadcastReceivers.clear()

        // Stop and release media player
        stopMedia()
        mediaPlayer?.release()
        mediaPlayer = null

        // Release media session
        mediaSession?.release()
        mediaSession = null

        // Release audio focus
        audioManager?.abandonAudioFocus(this)

        super.onDestroy()
    }

    // ...existing code...

    override fun onCompletion(mp: MediaPlayer?) {
        stopMedia()
        stopSelf()
    }

    override fun onPrepared(mp: MediaPlayer?) {
        playMedia()
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        when (what) {
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> Log.d(
                "MediaPlayer Error",
                "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK $extra"
            )
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> Log.d(
                "MediaPlayer Error",
                "MEDIA ERROR SERVER DIED $extra"
            )
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> Log.d(
                "MediaPlayer Error",
                "MEDIA ERROR UNKNOWN $extra"
            )
        }
        return false
    }

    override fun onSeekComplete(mp: MediaPlayer?) {
    }

    override fun onInfo(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        return false
    }

    override fun onBufferingUpdate(mp: MediaPlayer?, percent: Int) {
        TODO("Not yet implemented")
    }

    override fun onAudioFocusChange(focusChange: Int) {
        //Invoked when the audio focus of the system is updated.
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // resume playback
                mediaPlayer?.let {
                    if (!it.isPlaying) {
                        playMedia()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // pause playback
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        pauseMedia()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        pauseMedia()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.setVolume(0.1f, 0.1f)
                    }
                }
            }
        }
    }

    private fun getContentUri(songId: Long) =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)

    private fun playNextTrack() {
        Log.d("MediaPlayerService", "playNextTrack called with songId=$songId")
        songId?.let { id ->
            mediaPlayer?.let {
                Log.d("MediaPlayerService", "Stopping current song and loading new song with ID: $id")
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.setDataSource(applicationContext, getContentUri(id))
                it.setOnCompletionListener(object : MediaPlayer.OnCompletionListener {
                    override fun onCompletion(mp: MediaPlayer?) {
                        val intent = Intent(MusicActivity.COM_IDK_MUSIC_DONE)
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                    }
                })
                it.prepareAsync()
                Log.d("MediaPlayerService", "prepareAsync called for new song")
            }
        } ?: Log.e("MediaPlayerService", "Cannot play next track - songId is null!")
    }

    private fun playMedia() {
        if (mediaPlayer?.isPlaying == false) {
            mediaSession?.release()

            mediaSession = MediaSession(this, "MusicPlayer").apply {
                this.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, songAlbum)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, songArtist)
                        .putString(MediaMetadata.METADATA_KEY_TITLE, songTitle)
                        .build()
                )
            }
            mediaPlayer?.start()
            val intent = Intent(MusicActivity.COM_IDK_PLAY_SONG)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    private fun stopMedia() {
        if (mediaPlayer == null) return
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            val intent = Intent(MusicActivity.COM_IDK_PAUSE_SONG)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    private fun pauseMedia() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            val intent = Intent(MusicActivity.COM_IDK_PAUSE_SONG)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            mediaPlayer?.let {
                resumePosition = it.currentPosition
            }
        } else {
            playMedia()
        }
    }

    private fun seekMedia(progress: Int) {
        mediaPlayer?.let {
            val seekTo = it.duration.times(progress.toDouble() / 100).toInt()
            it.seekTo(seekTo)
        }
    }

    inner class LocalBinder: Binder() {
        fun getService(): MediaPlayerService = this@MediaPlayerService
    }

    private fun requestAudioFocus(): Boolean {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result: Int? = audioManager?.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

}