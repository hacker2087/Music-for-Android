package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.EqPreset
import com.example.data.MusicTrack
import kotlinx.coroutines.*
import java.io.IOException

class MusicPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var mediaSession: MediaSessionCompat? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var progressTrackerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "aura_playback_channel"
        const val NOTIFICATION_ID = 404

        const val ACTION_PLAY = "com.example.aura.PLAY"
        const val ACTION_PAUSE = "com.example.aura.PAUSE"
        const val ACTION_PLAY_PAUSE = "com.example.aura.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.aura.NEXT"
        const val ACTION_PREV = "com.example.aura.PREV"
        const val ACTION_SEEK = "com.example.aura.SEEK"
        const val ACTION_UPDATE_EQ = "com.example.aura.UPDATE_EQ"
        const val ACTION_STOP = "com.example.aura.STOP"

        const val EXTRA_TRACK_INDEX = "EXTRA_TRACK_INDEX"
        const val EXTRA_SEEK_POS = "EXTRA_SEEK_POS"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        initMediaPlayer()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "AuraPlaybackService").apply {
            isActive = true
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                playNext()
            }
            setOnErrorListener { _, what, extra ->
                PlaybackManager.isPlaying.value = false
                false
            }
            setOnPreparedListener { mp ->
                mp.start()
                PlaybackManager.isPlaying.value = true
                PlaybackManager.duration.value = mp.duration.toLong()
                startTrackingProgress()
                showNotification()
            }
        }
    }

    private fun initEqualizer(sessionId: Int) {
        try {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply {
                enabled = true
            }
            applyEqualizerSettings(PlaybackManager.eqPreset.value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyEqualizerSettings(preset: EqPreset) {
        try {
            equalizer?.let { eq ->
                eq.enabled = preset.isEnabled
                val bands = eq.numberOfBands
                val minLevel = eq.bandLevelRange[0] // e.g. -1500 (-15 dB)
                val maxLevel = eq.bandLevelRange[1] // e.g. +1500 (+15 dB)

                fun normalizeLevel(decibels: Float): Short {
                    val millibels = (decibels * 100).toInt()
                    return millibels.coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
                }

                if (bands >= 1) eq.setBandLevel(0, normalizeLevel(preset.band1))
                if (bands >= 2) eq.setBandLevel(1, normalizeLevel(preset.band2))
                if (bands >= 3) eq.setBandLevel(2, normalizeLevel(preset.band3))
                if (bands >= 4) eq.setBandLevel(3, normalizeLevel(preset.band4))
                if (bands >= 5) eq.setBandLevel(5, normalizeLevel(preset.band5))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val index = intent.getIntExtra(EXTRA_TRACK_INDEX, -1)
                if (index != -1 && index != PlaybackManager.currentIndex) {
                    playTrackAt(index)
                } else {
                    resume()
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_PLAY_PAUSE -> {
                if (PlaybackManager.isPlaying.value) pause() else resume()
            }
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrev()
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_SEEK_POS, 0L)
                mediaPlayer?.let {
                    it.seekTo(pos.toInt())
                    PlaybackManager.currentProgress.value = pos
                }
            }
            ACTION_UPDATE_EQ -> {
                applyEqualizerSettings(PlaybackManager.eqPreset.value)
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
        }
        return START_NOT_STICKY
    }

    private fun playTrackAt(index: Int) {
        val tracks = PlaybackManager.playlist.value
        if (tracks.isEmpty() || index < 0 || index >= tracks.size) return

        PlaybackManager.currentIndex = index
        val current = tracks[index]
        PlaybackManager.currentTrack.value = current

        mediaPlayer?.let { mp ->
            try {
                mp.reset()
                mp.setDataSource(current.path)
                mp.prepareAsync()
                mp.audioSessionId.takeIf { it != 0 }?.let { sessionId ->
                    initEqualizer(sessionId)
                }
            } catch (e: IOException) {
                e.printStackTrace()
                // Auto skip on error
                playNext()
            }
        }
    }

    private fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                PlaybackManager.isPlaying.value = true
                startTrackingProgress()
                showNotification()
            }
        }
    }

    private fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                PlaybackManager.isPlaying.value = false
                stopTrackingProgress()
                showNotification()
            }
        }
    }

    private fun playNext() {
        val tracks = PlaybackManager.playlist.value
        if (tracks.isEmpty()) return
        var next = PlaybackManager.currentIndex + 1
        if (next >= tracks.size) {
            next = 0 // loop
        }
        playTrackAt(next)
    }

    private fun playPrev() {
        val tracks = PlaybackManager.playlist.value
        if (tracks.isEmpty()) return
        var prev = PlaybackManager.currentIndex - 1
        if (prev < 0) {
            prev = tracks.size - 1
        }
        playTrackAt(prev)
    }

    private fun startTrackingProgress() {
        progressTrackerJob?.cancel()
        progressTrackerJob = serviceScope.launch {
            while (isActive) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        PlaybackManager.currentProgress.value = it.currentPosition.toLong()
                    }
                }
                delay(400)
            }
        }
    }

    private fun stopTrackingProgress() {
        progressTrackerJob?.cancel()
        progressTrackerJob = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Control de Reproducción"
            val desc = "Notificaciones de Aura para reproducir música en segundo plano"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val track = PlaybackManager.currentTrack.value ?: return

        val appIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPending = createActionPendingIntent(ACTION_PREV)
        val playPausePending = createActionPendingIntent(ACTION_PLAY_PAUSE)
        val nextPending = createActionPendingIntent(ACTION_NEXT)

        val playPauseIcon = if (PlaybackManager.isPlaying.value) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        // Beautiful X-Player styled clean background notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setContentIntent(pendingIntent)
            .setOngoing(PlaybackManager.isPlaying.value)
            .setSilent(true)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession?.sessionToken)
            )
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
            .addAction(playPauseIcon, "Play/Pause", playPausePending)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopForegroundService() {
        stopTrackingProgress()
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        equalizer?.release()
        mediaSession?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
