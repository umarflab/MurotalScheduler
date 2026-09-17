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
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "play"
        const val ACTION_STOP = "stop"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLE = "title"
        const val EXTRA_VOLUME = "volume"
        private const val CHANNEL_ID = "murotal_playback"
        private const val NOTIFICATION_ID = 1001
    }

    private var player: MediaPlayer? = null
    private var queue: List<String> = emptyList()
    private var queueIndex = 0
    private var title = "Murotal Scheduler"

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPlayback()
            ACTION_PLAY -> {
                queue = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                queueIndex = 0
                title = intent.getStringExtra(EXTRA_TITLE) ?: "Murotal"
                setDeviceVolume(intent.getIntExtra(EXTRA_VOLUME, -1))
                startForeground(NOTIFICATION_ID, notification(title))
                playCurrent()
            }
        }
        return START_NOT_STICKY
    }

    private fun setDeviceVolume(percent: Int) {
        if (percent !in 0..100) return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100.0).toInt(), 0)
    }

    private fun playCurrent() {
        player?.release()
        if (queueIndex !in queue.indices) {
            stopPlayback()
            return
        }
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(applicationContext, Uri.parse(queue[queueIndex]))
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                queueIndex++
                playCurrent()
            }
            setOnErrorListener { _, _, _ ->
                queueIndex++
                playCurrent()
                true
            }
            prepareAsync()
        }
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

    private fun stopPlayback() {
        player?.stop()
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
