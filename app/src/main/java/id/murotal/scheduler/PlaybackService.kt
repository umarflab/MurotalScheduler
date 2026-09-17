package id.murotal.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@UnstableApi
class PlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "play"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE_RESUME = "pause_resume"
        const val ACTION_SEEK = "seek"
        const val ACTION_STATE = "id.murotal.scheduler.PLAYBACK_STATE"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLE = "title"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FADE_IN = "fade_in"
        const val EXTRA_RESTORE_VOLUME = "restore_volume"
        const val EXTRA_EQ_ENABLED = "eq_enabled"
        const val EXTRA_EQ_PRESET = "eq_preset"
        const val EXTRA_EQ_BANDS = "eq_bands"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_QUEUE_INDEX = "queue_index"
        const val EXTRA_QUEUE_SIZE = "queue_size"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "murotal_playback"
        private const val NOTIFICATION_ID = 1001
    }

    private var player: ExoPlayer? = null
    private var queue: List<String> = emptyList()
    private var title = "Murotal Scheduler"
    private var mode = "sequential"
    private var fadePending = false
    private var restoreVolume = false
    private var previousVolume: Int? = null
    private var equalizer: Equalizer? = null
    private var reverb: PresetReverb? = null
    private var eqEnabled = false
    private var eqPreset = "Normal"
    private var eqBands: List<Int> = listOf(0, 0, 0, 0, 0)

    private val stateHandler by lazy { android.os.Handler(mainLooper) }
    private val stateTask = object : Runnable {
        override fun run() {
            broadcastState()
            if (player != null) stateHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notification_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopPlayback()
                ACTION_PAUSE_RESUME -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    broadcastState()
                }
                ACTION_SEEK -> {
                    player?.seekTo(intent.getIntExtra(EXTRA_POSITION, 0).coerceAtLeast(0).toLong())
                    broadcastState()
                }
                ACTION_PLAY -> startPlayback(intent)
            }
        } catch (error: Throwable) {
            stopPlayback("Pemutaran gagal dimulai: ${error.javaClass.simpleName}.")
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(intent: Intent) {
        releasePlayer()
        queue = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        if (queue.isEmpty()) {
            stopPlayback("Audio tidak ditemukan di Pustaka.")
            return
        }

        mode = intent.getStringExtra(EXTRA_MODE) ?: if (queue.size == 1) "single" else "sequential"
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Murotal"
        fadePending = intent.getBooleanExtra(EXTRA_FADE_IN, false)
        restoreVolume = intent.getBooleanExtra(EXTRA_RESTORE_VOLUME, false)
        eqEnabled = intent.getBooleanExtra(EXTRA_EQ_ENABLED, false)
        eqPreset = intent.getStringExtra(EXTRA_EQ_PRESET) ?: "Normal"
        eqBands = intent.getIntegerArrayListExtra(EXTRA_EQ_BANDS)?.toList()
            ?: listOf(0, 0, 0, 0, 0)

        setDeviceVolume(intent.getIntExtra(EXTRA_VOLUME, -1), restoreVolume)
        startForeground(NOTIFICATION_ID, notification(title))

        val activePlayer = ExoPlayer.Builder(this).build()
        player = activePlayer
        activePlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== activePlayer) return
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (fadePending) {
                            fadePending = false
                            activePlayer.volume = 0f
                            fadePlayer(activePlayer)
                        }
                        applyAudioEffects(activePlayer.audioSessionId, activePlayer)
                        broadcastState()
                    }
                    Player.STATE_ENDED -> stopPlayback()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (player === activePlayer) applyAudioEffects(audioSessionId, activePlayer)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === activePlayer) broadcastState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player === activePlayer) broadcastState()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player === activePlayer) {
                    stopPlayback(
                        "Audio tidak dapat diputar: ${error.errorCodeName}. " +
                            "Pastikan format audio didukung."
                    )
                }
            }
        })

        activePlayer.setMediaItems(queue.map { MediaItem.fromUri(it) })
        activePlayer.repeatMode =
            if (mode == "shuffle_cycle") Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        activePlayer.shuffleModeEnabled = mode == "shuffle_cycle"
        activePlayer.volume = if (fadePending) 0f else 1f
        activePlayer.prepare()
        activePlayer.play()

        stateHandler.removeCallbacks(stateTask)
        stateHandler.post(stateTask)
    }

    private fun setDeviceVolume(percent: Int, remember: Boolean) {
        if (percent !in 0..100) return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (remember && previousVolume == null) {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (max * percent / 100.0).toInt(),
            0
        )
    }

    private fun applyAudioEffects(audioSessionId: Int, activePlayer: ExoPlayer) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || player !== activePlayer) return
        releaseAudioEffects()
        if (!eqEnabled) return

        runCatching {
            equalizer = Equalizer(1000, audioSessionId).apply {
                enabled = false
                val range = bandLevelRange
                val minimum = range[0].toInt()
                val maximum = range[1].toInt()
                val count = numberOfBands.toInt().coerceAtLeast(1)
                for (band in 0 until count) {
                    val source =
                        if (count == 1) 2 else (band * 4f / (count - 1)).toInt().coerceIn(0, 4)
                    val percent = eqBands.getOrElse(source) { 0 }.coerceIn(-100, 100)
                    val level =
                        if (percent >= 0) percent * maximum / 100 else -percent * minimum / 100
                    setBandLevel(
                        band.toShort(),
                        level.coerceIn(minimum, maximum).toShort()
                    )
                }
                enabled = true
            }
        }

        val reverbConfig = when (eqPreset) {
            "Room" -> PresetReverb.PRESET_SMALLROOM to 0.35f
            "Ballroom" -> PresetReverb.PRESET_LARGEROOM to 0.65f
            "Concert" -> PresetReverb.PRESET_LARGEHALL to 0.85f
            "Hall" -> PresetReverb.PRESET_MEDIUMHALL to 0.60f
            "Plate" -> PresetReverb.PRESET_PLATE to 0.50f
            else -> PresetReverb.PRESET_NONE to 0f
        }
        if (reverbConfig.first != PresetReverb.PRESET_NONE) {
            runCatching {
                reverb = PresetReverb(0, 0).apply {
                    preset = reverbConfig.first
                    enabled = true
                }
                activePlayer.setAuxEffectInfo(
                    AuxEffectInfo(reverb!!.id, reverbConfig.second)
                )
            }
        }
    }

    private fun broadcastState(errorMessage: String? = null) {
        val active = player
        val durationLong = active?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val positionLong = active?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val state = Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, positionLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            putExtra(EXTRA_DURATION, durationLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            putExtra(EXTRA_PLAYING, active?.isPlaying ?: false)
            putExtra(EXTRA_QUEUE_INDEX, active?.currentMediaItemIndex ?: 0)
            putExtra(EXTRA_QUEUE_SIZE, active?.mediaItemCount ?: 0)
            putExtra(EXTRA_ERROR, errorMessage)
        }
        sendBroadcast(state)
    }

    private fun releaseAudioEffects() {
        runCatching { equalizer?.release() }
        runCatching { reverb?.release() }
        equalizer = null
        reverb = null
    }

    private fun fadePlayer(activePlayer: ExoPlayer) {
        var step = 0
        val task = object : Runnable {
            override fun run() {
                if (player !== activePlayer) return
                if (!activePlayer.isPlaying) {
                    stateHandler.postDelayed(this, 250)
                    return
                }
                step++
                activePlayer.volume = (step / 20f).coerceAtMost(1f)
                if (step < 20) stateHandler.postDelayed(this, 500)
            }
        }
        stateHandler.post(task)
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sedang diputar")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0,
                "Hentikan",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun releasePlayer() {
        val activePlayer = player
        player = null
        runCatching { activePlayer?.release() }
        releaseAudioEffects()
    }

    private fun stopPlayback(errorMessage: String? = null) {
        stateHandler.removeCallbacks(stateTask)
        releasePlayer()
        if (restoreVolume) {
            previousVolume?.let { saved ->
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            }
        }
        previousVolume = null
        restoreVolume = false
        queue = emptyList()
        broadcastState(errorMessage)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stateHandler.removeCallbacks(stateTask)
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
