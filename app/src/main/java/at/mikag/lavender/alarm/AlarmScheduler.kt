package at.mikag.lavender.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.ui.MainActivity
import java.time.ZonedDateTime

/** Hands alarms to Android's AlarmManager. setAlarmClock() is exact, survives Doze and shows the alarm icon. */
class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()

    /** Schedules (or cancels) the alarm and returns it with [Alarm.scheduledFor] / [Alarm.enabled] updated. */
    fun schedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): Alarm {
        Notifications.cancelUpcoming(context, alarm.id)
        val next = NextTrigger.compute(alarm, now)
        if (next == null) {
            cancel(alarm)
            // A one-time alarm whose date has passed switches itself off.
            val expired = alarm.enabled && !alarm.isRepeating
            return alarm.copy(scheduledFor = null, enabled = if (expired) false else alarm.enabled)
        }
        val at = next.toInstant().toEpochMilli()
        val fire = firePendingIntent(alarm.id)
        try {
            if (canScheduleExact()) {
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
        scheduleUpcoming(alarm, at)
        return alarm.copy(scheduledFor = at)
    }

    fun cancel(alarm: Alarm) {
        manager.cancel(firePendingIntent(alarm.id))
        manager.cancel(upcomingPendingIntent(alarm.id))
        Notifications.cancelUpcoming(context, alarm.id)
    }

    /** The "Upcoming alarm" notification, shown two hours ahead with a "Dismiss now" button. */
    private fun scheduleUpcoming(alarm: Alarm, at: Long) {
        val showAt = at - UPCOMING_LEAD_MS
        if (showAt <= System.currentTimeMillis()) {
            Notifications.upcoming(context, alarm, at)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC, showAt, upcomingPendingIntent(alarm.id))
        }
    }

    private fun firePendingIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun upcomingPendingIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_UPCOMING).putExtra(EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val UPCOMING_LEAD_MS = 2 * 60 * 60 * 1000L
    }
}
