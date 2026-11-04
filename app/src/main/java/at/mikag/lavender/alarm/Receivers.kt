package at.mikag.lavender.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import at.mikag.lavender.AppLog
import at.mikag.lavender.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive (up to ~10 s). */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** Alarm time reached, upcoming-alarm reminders, and notification buttons outside of ringing. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        AppLog.i("Receiver", "${intent.action?.substringAfterLast('.')} #$id")
        if (id < 0) return
        when (intent.action) {
            ACTION_FIRE -> AlarmService.fire(context, id)
            ACTION_UPCOMING -> runAsync {
                val alarm = context.container.alarms.get(id) ?: return@runAsync
                val at = alarm.scheduledFor ?: return@runAsync
                if (alarm.enabled) Notifications.upcoming(context, alarm, at)
            }
            ACTION_DISMISS_UPCOMING -> runAsync {
                Notifications.cancelUpcoming(context, id)
                context.container.alarms.skipNext(id)
            }
            ACTION_CANCEL_SNOOZE -> runAsync {
                Notifications.cancelSnoozed(context, id)
                context.container.alarms.edit(id) { it.copy(snoozedUntil = null, enabled = it.isRepeating) }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "at.mikag.lavender.FIRE"
        const val ACTION_UPCOMING = "at.mikag.lavender.UPCOMING"
        const val ACTION_DISMISS_UPCOMING = "at.mikag.lavender.DISMISS_UPCOMING"
        const val ACTION_CANCEL_SNOOZE = "at.mikag.lavender.CANCEL_SNOOZE"
    }
}

/** Android forgets all alarms on reboot, and times shift with clock or time zone changes: re-register them. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reportMissed = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> false
            else -> return
        }
        AppLog.i("Receiver", "System event ${intent.action}: rescheduling all alarms")
        runAsync { context.container.alarms.rescheduleAll(reportMissed) }
    }
}
