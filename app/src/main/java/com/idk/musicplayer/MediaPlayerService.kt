package com.idk.musicplayer

import android.app.Service
import android.content.*
import android.media.AudioManager
import android.media.MediaPlayer
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
    private var songId: Long? = null
    private var isSeeking = false

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        try {
            //An audio id is passed to the service through putExtra();
            val songId = intent.extras?.getLong(MusicActivity.SONG_ID)
            songId?.let {
                this.songId = it
            }
        } catch (e: NullPointerException) {
            stopSelf()
        }

        registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isSeeking = true
            }
        }, IntentFilter(MusicActivity.COM_IDK_MUSIC_SEEK_START))

        registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isSeeking = false
                intent?.let {
                    seekMedia(intent.getIntExtra(MusicActivity.SEEK_VALUE, 0))
                }
            }
        }, IntentFilter(MusicActivity.COM_IDK_MUSIC_SEEK_STOP))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                songId = intent?.extras?.getLong(MusicActivity.SONG_ID)
                playNextTrack()
            }
        }, IntentFilter(MusicActivity.COM_IDK_PLAY_NEXT_SONG))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pauseMedia()
            }
        }, IntentFilter(MusicActivity.COM_IDK_PLAY_PAUSE_SONG))

        //Request audio focus
        if (requestAudioFocus() == false) {
            //Could not gain focus
            stopSelf()
        }

        if (songId != null)
            initMediaPlayer()

        return super.onStartCommand(intent, flags, startId)
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
                mp.setOnCompletionListener { sendBroadcast(Intent("com.idk.music_done")) }
                mp.prepareAsync()
            }
        }
        startUpdatingSeekBar()
    }

    private fun startUpdatingSeekBar() {
        val timer = Timer()
        timer.scheduleAtFixedRate(object: TimerTask(){
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying && !isSeeking) {
                        val intent = Intent(MusicActivity.COM_IDK_TIME_UPDATE)
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
        songId?.let { id ->
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.setDataSource(applicationContext, getContentUri(id))
                it.setOnCompletionListener(object : MediaPlayer.OnCompletionListener {
                    override fun onCompletion(mp: MediaPlayer?) {
                        sendBroadcast(Intent(MusicActivity.COM_IDK_MUSIC_DONE))
                    }
                })
                it.prepareAsync()
            }
        }
    }

    private fun playMedia() {
        if (mediaPlayer?.isPlaying == false) {
            mediaPlayer?.start()
            sendBroadcast(Intent(MusicActivity.COM_IDK_PLAY_SONG))
        }
    }

    private fun stopMedia() {
        if (mediaPlayer == null) return
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            sendBroadcast(Intent(MusicActivity.COM_IDK_PAUSE_SONG))
        }
    }

    private fun pauseMedia() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            sendBroadcast(Intent(MusicActivity.COM_IDK_PAUSE_SONG))
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

    class LocalBinder: Binder() {
        fun getService(): MediaPlayerService {
            return MediaPlayerService()
        }
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