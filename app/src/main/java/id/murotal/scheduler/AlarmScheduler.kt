package id.murotal.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val ACTION_START = "id.murotal.scheduler.START"
    const val ACTION_STOP = "id.murotal.scheduler.STOP"

    fun scheduleAll(context: Context) {
        AppStore(context).schedules().filter { it.enabled }.forEach { schedule(context, it) }
    }

    fun schedule(context: Context, item: PlaybackSchedule) {
        cancel(context, item.id)
        if (!item.enabled) return
        setDaily(context, item, true)
        setDaily(context, item, false)
    }

    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingIntent(context, id, true))
        manager.cancel(pendingIntent(context, id, false))
    }

    private fun setDaily(context: Context, item: PlaybackSchedule, start: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val minutes = if (start) item.startMinutes else item.endMinutes
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val operation = pendingIntent(context, item.id, start)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        }
    }

    private fun pendingIntent(context: Context, id: String, start: Boolean): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = if (start) ACTION_START else ACTION_STOP
            putExtra("schedule_id", id)
        }
        val code = id.hashCode() * 2 + if (start) 0 else 1
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
