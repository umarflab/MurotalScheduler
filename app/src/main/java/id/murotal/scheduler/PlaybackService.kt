package id.murotal.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.net.Uri
import android.os.IBinder
import java.io.IOException
import androidx.core.app.NotificationCompat

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

    private var player: MediaPlayer? = null
    private var queue: List<String> = emptyList()
    private var queueIndex = 0
    private var title = "Murotal Scheduler"
    private var mode = "sequential"
    private var fadeIn = false
    private var fadePending = false
    private var restoreVolume = false
    private var previousVolume: Int? = null
    private var targetPlayerVolume = 1f
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
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopPlayback()
                ACTION_PAUSE_RESUME -> {
                    player?.let { active ->
                        if (runCatching { active.isPlaying }.getOrDefault(false)) active.pause() else active.start()
                    }
                    broadcastState()
                }
                ACTION_SEEK -> {
                    val position = intent.getIntExtra(EXTRA_POSITION, 0)
                    player?.seekTo(position.coerceAtLeast(0))
                    broadcastState()
                }
                ACTION_PLAY -> {
                    queue = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                    if (queue.isEmpty()) {
                        stopPlayback("Audio tidak ditemukan di Pustaka.")
                        return START_NOT_STICKY
                    }
                    mode = intent.getStringExtra(EXTRA_MODE) ?: if (queue.size == 1) "single" else "sequential"
                    if (mode == "shuffle_cycle") queue = queue.shuffled()
                    queueIndex = 0
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "Murotal"
                    fadeIn = intent.getBooleanExtra(EXTRA_FADE_IN, false)
                    fadePending = fadeIn
                    restoreVolume = intent.getBooleanExtra(EXTRA_RESTORE_VOLUME, false)
                    eqEnabled = intent.getBooleanExtra(EXTRA_EQ_ENABLED, false)
                    eqPreset = intent.getStringExtra(EXTRA_EQ_PRESET) ?: "Normal"
                    eqBands = intent.getIntegerArrayListExtra(EXTRA_EQ_BANDS)?.toList() ?: listOf(0, 0, 0, 0, 0)
                    setDeviceVolume(intent.getIntExtra(EXTRA_VOLUME, -1), restoreVolume)
                    startForeground(NOTIFICATION_ID, notification(title))
                    playCurrent()
                    stateHandler.removeCallbacks(stateTask)
                    stateHandler.post(stateTask)
                }
            }
        } catch (error: Throwable) {
            stopPlayback(readableError(error))
        }
        return START_NOT_STICKY
    }

    private fun setDeviceVolume(percent: Int, remember: Boolean) {
        if (percent !in 0..100) return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (remember && previousVolume == null) previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100.0).toInt(), 0)
    }

    private fun playCurrent() {
        releasePlayer()
        releaseAudioEffects()
        if (queueIndex !in queue.indices) {
            stopPlayback()
            return
        }

        val activePlayer = MediaPlayer()
        player = activePlayer
        try {
            activePlayer.setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            setPlayerDataSource(activePlayer, queue[queueIndex])
            activePlayer.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                applyAudioEffects(it)
                if (fadePending) {
                    fadePending = false
                    it.setVolume(0f, 0f)
                    it.start()
                    fadePlayer(it)
                } else {
                    it.setVolume(targetPlayerVolume, targetPlayerVolume)
                    it.start()
                }
                broadcastState()
            }
            activePlayer.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                queueIndex++
                if (mode == "shuffle_cycle" && queueIndex >= queue.size) {
                    queue = queue.shuffled()
                    queueIndex = 0
                }
                if (queueIndex in queue.indices) playCurrent() else stopPlayback()
            }
            activePlayer.setOnErrorListener { mediaPlayer, what, extra ->
                if (player === mediaPlayer) {
                    stopPlayback("Audio gagal diputar (kode $what/$extra). Impor ulang file jika masalah berulang.")
                }
                true
            }
            activePlayer.prepareAsync()
        } catch (error: Throwable) {
            stopPlayback(readableError(error))
        }
    }

    private fun setPlayerDataSource(activePlayer: MediaPlayer, source: String) {
        val uri = Uri.parse(source)
        if (uri.scheme == "content") {
            val descriptor = contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Android tidak dapat membuka file audio.")
            descriptor.use {
                if (it.declaredLength >= 0) {
                    activePlayer.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
                } else {
                    activePlayer.setDataSource(it.fileDescriptor)
                }
            }
        } else {
            activePlayer.setDataSource(applicationContext, uri)
        }
    }

    private fun readableError(error: Throwable): String = when (error) {
        is SecurityException -> "Izin membaca file audio ditolak. Hapus track dari Pustaka lalu impor kembali."
        is IOException -> "File audio tidak dapat dibuka. Pastikan file masih tersedia dan formatnya didukung."
        is IllegalStateException -> "Pemutar audio berada dalam keadaan yang tidak valid. Coba putar kembali."
        else -> "Pemutaran gagal: ${error.javaClass.simpleName}."
    }

    private fun applyAudioEffects(activePlayer: MediaPlayer) {
        if (!eqEnabled) return
        runCatching {
            equalizer = Equalizer(1000, activePlayer.audioSessionId).apply {
                enabled = false
                val range = bandLevelRange
                val minimum = range[0].toInt()
                val maximum = range[1].toInt()
                val count = numberOfBands.toInt().coerceAtLeast(1)
                for (band in 0 until count) {
                    val source = if (count == 1) 2 else (band * 4f / (count - 1)).toInt().coerceIn(0, 4)
                    val percent = eqBands.getOrElse(source) { 0 }.coerceIn(-100, 100)
                    val level = if (percent >= 0) percent * maximum / 100 else -percent * minimum / 100
                    setBandLevel(band.toShort(), level.coerceIn(minimum, maximum).toShort())
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
        if (reverbConfig.first != PresetReverb.PRESET_NONE) runCatching {
            reverb = PresetReverb(0, 0).apply {
                preset = reverbConfig.first
                enabled = true
            }
            activePlayer.attachAuxEffect(reverb!!.id)
            activePlayer.setAuxEffectSendLevel(reverbConfig.second)
        }
    }

    private fun broadcastState(errorMessage: String? = null) {
        val active = player
        val state = Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, runCatching { active?.currentPosition ?: 0 }.getOrDefault(0))
            putExtra(EXTRA_DURATION, runCatching { active?.duration ?: 0 }.getOrDefault(0))
            putExtra(EXTRA_PLAYING, runCatching { active?.isPlaying ?: false }.getOrDefault(false))
            putExtra(EXTRA_QUEUE_INDEX, queueIndex)
            putExtra(EXTRA_QUEUE_SIZE, queue.size)
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

    private fun fadePlayer(activePlayer: MediaPlayer) {
        val handler = android.os.Handler(mainLooper)
        var step = 0
        val task = object : Runnable {
            override fun run() {
                if (player !== activePlayer || !activePlayer.isPlaying) return
                step++
                val level = (step / 20f).coerceAtMost(targetPlayerVolume)
                activePlayer.setVolume(level, level)
                if (step < 20) handler.postDelayed(this, 500)
            }
        }
        handler.post(task)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Sedang diputar")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Hentikan", PendingIntent.getService(this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun releasePlayer() {
        val activePlayer = player
        player = null
        runCatching { activePlayer?.reset() }
        runCatching { activePlayer?.release() }
    }

    private fun stopPlayback(errorMessage: String? = null) {
        stateHandler.removeCallbacks(stateTask)
        releasePlayer()
        releaseAudioEffects()
        if (restoreVolume) {
            previousVolume?.let { saved ->
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            }
        }
        previousVolume = null
        restoreVolume = false
        queue = emptyList()
        queueIndex = 0
        broadcastState(errorMessage)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stateHandler.removeCallbacks(stateTask)
        releasePlayer()
        releaseAudioEffects()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
