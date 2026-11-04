package at.mikag.alarmspike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

/** Fired by AlarmManager at alarm time, and by the "Dismiss" button in the notification. */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "at.mikag.alarmspike.FIRE"
        const val ACTION_DISMISS = "at.mikag.alarmspike.DISMISS"
        const val EXTRA_STRATEGY = "strategy"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            AlarmActivity.dismissAll(context, "notification button")
            return
        }
        val name = intent.getStringExtra(EXTRA_STRATEGY) ?: Strategy.PLAY_LINK.name
        TestLog.add(context, "=== Test alarm fired: ${Strategy.valueOf(name).label} ===")
        AlarmNotifier.show(context, name, reshow = false)
    }
}

/** Shows the alarm screen through a full-screen notification, the only reliable way over the lock screen. */
object AlarmNotifier {
    private const val CHANNEL = "test_alarm"
    private const val ID = 4711

    fun show(context: Context, strategy: String, reshow: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Test alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            if (reshow) 2 else 1,
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmActivity.EXTRA_STRATEGY, strategy)
                .putExtra(AlarmActivity.EXTRA_RESHOW, reshow)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Test alarm")
            .setContentText("Tap to open the alarm screen")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dismiss",
                    dismiss
                ).build()
            )
            .build()
        if (reshow) manager.cancel(ID)
        manager.notify(ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}

/** The local ringtone that plays when TIDAL stays silent. */
object Fallback {
    private var player: MediaPlayer? = null

    fun start(context: Context, log: (String) -> Unit) {
        if (player != null) return
        val types = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
        for (type in types) {
            val uri = RingtoneManager.getDefaultUri(type)
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                candidate.setDataSource(context.applicationContext, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.start()
                player = candidate
                log("Fallback ringtone started")
                return
            } catch (e: Exception) {
                candidate.release()
                log("Couldn't play $uri: ${e.message}")
            }
        }
    }

    fun stop() {
        val current = player ?: return
        player = null
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            // already stopped
        }
        current.release()
    }
}
