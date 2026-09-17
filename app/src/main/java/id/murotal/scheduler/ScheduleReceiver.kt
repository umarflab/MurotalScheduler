package id.murotal.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("schedule_id") ?: return
        val schedule = AppStore(context).schedules().firstOrNull { it.id == id && it.enabled } ?: return
        if (intent.action == AlarmScheduler.ACTION_START) {
            val uris = AppStore(context).resolveUris(schedule.targetType, schedule.targetId)
            if (uris.isNotEmpty()) {
                val service = Intent(context, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PLAY
                    putStringArrayListExtra(PlaybackService.EXTRA_URIS, ArrayList(uris))
                    putExtra(PlaybackService.EXTRA_TITLE, schedule.name)
                    putExtra(PlaybackService.EXTRA_VOLUME, schedule.volumePercent)
                }
                ContextCompat.startForegroundService(context, service)
            }
        } else {
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
        }
        AlarmScheduler.schedule(context, schedule)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(context)
    }
}
