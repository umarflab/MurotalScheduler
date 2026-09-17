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
            val store = AppStore(context)
            val uris = if (schedule.stopMode == "after_source") {
                store.nextUriForSchedule(schedule)?.let { listOf(it) }.orEmpty()
            } else {
                store.resolveUris(schedule.targetType, schedule.targetId)
            }
            if (uris.isNotEmpty()) {
                val service = Intent(context, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PLAY
                    putStringArrayListExtra(PlaybackService.EXTRA_URIS, ArrayList(uris))
                    putExtra(PlaybackService.EXTRA_TITLE, schedule.name)
                    putExtra(PlaybackService.EXTRA_VOLUME, schedule.volumePercent)
                    putExtra(PlaybackService.EXTRA_MODE,
                        if (schedule.stopMode == "after_source") "single" else schedule.playbackMode)
                    putExtra(PlaybackService.EXTRA_FADE_IN, schedule.fadeIn)
                    putExtra(PlaybackService.EXTRA_RESTORE_VOLUME, true)
                    putExtra(PlaybackService.EXTRA_EQ_ENABLED, schedule.equalizerEnabled)
                    putExtra(PlaybackService.EXTRA_EQ_PRESET, schedule.equalizerPreset)
                    putIntegerArrayListExtra(PlaybackService.EXTRA_EQ_BANDS, ArrayList(schedule.equalizerBands))
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
