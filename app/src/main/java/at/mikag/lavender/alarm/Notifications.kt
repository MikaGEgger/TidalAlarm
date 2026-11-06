package at.mikag.lavender.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import at.mikag.lavender.R
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.ui.Format
import at.mikag.lavender.ui.MainActivity
import at.mikag.lavender.ui.ringing.RingingActivity

object Notifications {
    const val CHANNEL_RINGING = "ringing"
    private const val CHANNEL_UPCOMING = "upcoming"
    private const val CHANNEL_SNOOZED = "snoozed"
    private const val CHANNEL_MISSED = "missed"

    const val ID_RINGING = 1
    private const val ID_RESHOW = 2
    private fun upcomingId(alarmId: Long) = 10_000 + alarmId.toInt()
    private fun snoozedId(alarmId: Long) = 20_000 + alarmId.toInt()
    private fun missedId(alarmId: Long) = 30_000 + alarmId.toInt()

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_RINGING, context.getString(R.string.channel_ringing), NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    // Lavender plays its own sound and vibration.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                },
                NotificationChannel(
                    CHANNEL_UPCOMING, context.getString(R.string.channel_upcoming), NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    CHANNEL_SNOOZED, context.getString(R.string.channel_snoozed), NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    CHANNEL_MISSED, context.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT
                ),
            )
        )
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun ringingScreen(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun receiverAction(context: Context, action: String, alarmId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun title(context: Context, alarm: Alarm?): String =
        alarm?.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.alarm_default_label)

    /** The foreground-service notification while ringing. Its full-screen intent shows the ringing screen. */
    fun ringing(context: Context, alarm: Alarm?, detail: String?): Notification {
        val builder = Notification.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(context, alarm))
            .setContentText(detail ?: Format.time(context, System.currentTimeMillis()))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            // The text changes while ringing (TIDAL starting → playing); don't pop up again each time.
            .setOnlyAlertOnce(true)
            .setContentIntent(ringingScreen(context, 1))
            .setFullScreenIntent(ringingScreen(context, 1), true)
            .addAction(action(context, R.string.action_snooze, serviceAction(context, AlarmService.ACTION_SNOOZE, 1)))
            .addAction(action(context, R.string.action_dismiss, serviceAction(context, AlarmService.ACTION_DISMISS, 2)))
        // When the phone is in use Android shows only this pop-up; opening the ringing screen starts TIDAL.
        if (alarm?.tidalItem != null) {
            builder.addAction(action(context, R.string.action_play_music, ringingScreen(context, 3)))
        }
        return builder.build()
    }

    /**
     * Brings the ringing screen back in front of TIDAL. Posting a fresh full-screen notification is the
     * only way Android lets a background app do that (tested on the lock screen).
     */
    fun reshowRingingScreen(context: Context, alarm: Alarm?) {
        val notification = Notification.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(context, alarm))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(5_000)
            .setContentIntent(ringingScreen(context, 2))
            .setFullScreenIntent(ringingScreen(context, 2), true)
            .build()
        manager(context).cancel(ID_RESHOW)
        manager(context).notify(ID_RESHOW, notification)
    }

    fun cancelReshow(context: Context) = manager(context).cancel(ID_RESHOW)

    fun upcoming(context: Context, alarm: Alarm, at: Long) {
        if (!alarm.enabled) return
        val notification = Notification.Builder(context, CHANNEL_UPCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_upcoming_title))
            .setContentText(listOfNotNull(Format.dayAndTime(context, at), alarm.label.takeIf { it.isNotBlank() }).joinToString(" · "))
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(maxOf(1L, at - System.currentTimeMillis()))
            .addAction(
                action(context, R.string.action_dismiss_now, receiverAction(context, AlarmReceiver.ACTION_DISMISS_UPCOMING, alarm.id))
            )
            .build()
        manager(context).notify(upcomingId(alarm.id), notification)
    }

    fun cancelUpcoming(context: Context, alarmId: Long) = manager(context).cancel(upcomingId(alarmId))

    fun snoozed(context: Context, alarm: Alarm, until: Long) {
        val notification = Notification.Builder(context, CHANNEL_SNOOZED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_snoozed_title, Format.time(context, until)))
            .setContentText(title(context, alarm))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(action(context, R.string.action_dismiss, receiverAction(context, AlarmReceiver.ACTION_CANCEL_SNOOZE, alarm.id)))
            .build()
        manager(context).notify(snoozedId(alarm.id), notification)
    }

    fun cancelSnoozed(context: Context, alarmId: Long) = manager(context).cancel(snoozedId(alarmId))

    fun missed(context: Context, alarm: Alarm, at: Long, silenced: Boolean = false) {
        val text = if (silenced) R.string.notification_silenced_title else R.string.notification_missed_title
        val notification = Notification.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(text, Format.time(context, at)))
            .setContentText(title(context, alarm))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager(context).notify(missedId(alarm.id), notification)
    }

    private fun action(context: Context, label: Int, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), context.getString(label), intent)
            .build()
}
