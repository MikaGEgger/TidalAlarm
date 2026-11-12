package at.mikag.lavender.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import at.mikag.lavender.AppLog
import at.mikag.lavender.LogUpload
import at.mikag.lavender.R
import at.mikag.lavender.container
import at.mikag.lavender.ui.ringing.RingingActivity
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.data.TidalItem
import at.mikag.lavender.tidal.TidalLauncher
import at.mikag.lavender.tidal.WakeupPlaylists
import at.mikag.lavender.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs while an alarm rings. Decides between TIDAL and the backup sound, watches that music really
 * plays, and handles snooze / dismiss / auto-silence. Foreground service so Android doesn't kill it.
 */
class AlarmService : Service() {

    private enum class Outcome { SNOOZE, DISMISS, SILENCED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringJob: Job? = null
    private var current: Alarm? = null
    private var startedAt = 0L
    /** A test ring from the editor: doesn't touch the alarm's schedule. */
    private var testRun = false
    private var finishing = false
    private lateinit var audio: AudioControl
    private lateinit var backup: BackupSound
    private var vibration: AlarmVibration? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = AudioControl(this)
        backup = BackupSound(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "Command ${intent?.action?.substringAfterLast('.')} (ringing=${current?.id})")
        when (intent?.action) {
            ACTION_FIRE -> {
                // Android requires this within seconds of startForegroundService(), so do it before anything else.
                goForeground(current, null)
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
                if (current != null || ringJob?.isActive == true) {
                    scope.launch { reportBusy(id) }
                } else {
                    testRun = intent.getBooleanExtra(EXTRA_TEST, false)
                    startRinging(id)
                }
            }
            ACTION_SNOOZE -> finish(Outcome.SNOOZE)
            ACTION_DISMISS -> finish(Outcome.DISMISS)
            else -> if (current == null && ringJob?.isActive != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(id: Long) {
        acquireWakeLock()
        startedAt = System.currentTimeMillis()
        ringJob = scope.launch {
            val repo = container.alarms
            val alarm = withContext(Dispatchers.IO) { repo.get(id) }
            if (alarm == null) {
                shutDown()
                return@launch
            }
            current = alarm
            RingController.start(alarm)
            Notifications.cancelUpcoming(this@AlarmService, id)
            Notifications.cancelSnoozed(this@AlarmService, id)
            if (!testRun) {
                withContext(Dispatchers.IO) {
                    // Schedule the next occurrence now: even if something goes wrong while ringing,
                    // tomorrow's alarm is safe. A one-time alarm switches off (snooze turns it back on).
                    repo.save(alarm.copy(snoozedUntil = null, enabled = alarm.isRepeating))
                }
            }
            goForeground(alarm, null)
            logRingContext(alarm)
            // Don't wait for Android: on an unlocked phone the full-screen notification only shows a pop-up.
            openScreenDirectly("alarm started")
            if (alarm.vibrate) vibration = AlarmVibration(this@AlarmService).also { it.start() }

            val silenceAfter = withContext(Dispatchers.IO) { container.settings.current().silenceAfterMinutes }
            if (silenceAfter > 0) {
                launch {
                    delay(silenceAfter * 60_000L)
                    finish(Outcome.SILENCED)
                }
            }

            val item = alarm.tidalItem
            val playable = item?.let { playableItem(alarm, it) }
            if (playable != null && tidalUsable()) {
                // The alarm as loaded still carries its snooze time: this ring ends a snooze.
                playTidal(alarm, playable, resume = alarm.snoozedUntil != null)
            } else {
                playBackup(alarm, tidalFailed = item != null)
            }
        }
    }

    /** What to open in TIDAL. Shuffle and My songs play the wake-up playlist Lavender filled beforehand. */
    private fun playableItem(alarm: Alarm, item: TidalItem): TidalItem? {
        if (!alarm.needsWakeupPlaylist) return item
        val wakeup = alarm.wakeupPlaylistId
        if (wakeup != null) {
            val filled = alarm.wakeupPreparedAt?.let { Format.dayAndTime(this, it) } ?: "never"
            AppLog.i(TAG, "Playing wake-up playlist $wakeup (${alarm.wakeupTrackCount} songs, filled $filled, shuffle=${alarm.shuffle})")
            return TidalItem(TidalItem.Type.PLAYLIST, wakeup, item.title, item.subtitle)
        }
        return if (item.type == TidalItem.Type.PLAYLIST) {
            AppLog.w(TAG, "Wake-up playlist not ready: playing the playlist in its normal order")
            item
        } else {
            AppLog.w(TAG, "Wake-up playlist for My songs not ready: backup sound")
            null
        }
    }

    /**
     * Everything that decides whether the ringing screen can appear.
     */
    private fun logRingContext(alarm: Alarm) {
        val notifications = getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()
        val enabled = notifications.areNotificationsEnabled()
        val channel = notifications.getNotificationChannel(Notifications.CHANNEL_RINGING)
        AppLog.i(
            TAG,
            "Ringing #${alarm.id} at ${alarm.hour}:${"%02d".format(alarm.minute)} test=$testRun" +
                " | notifications=$enabled fullScreenAllowed=$fullScreen channelImportance=${channel?.importance}" +
                " | screenOn=${getSystemService(PowerManager::class.java).isInteractive}" +
                " locked=${getSystemService(KeyguardManager::class.java).isKeyguardLocked}" +
                " userUnlocked=${getSystemService(UserManager::class.java).isUserUnlocked}" +
                " | tidalInstalled=${TidalLauncher.isInstalled(this)} music=${alarm.tidalItem?.let { "${it.type.path}/${it.id}" }}" +
                " volume=${alarm.volumePercent}% ramp=${alarm.rampSeconds}s" +
                " displayOverOtherApps=${Settings.canDrawOverlays(this)}",
        )
    }

    /**
     * Opens the ringing screen without waiting for the full-screen notification. Always allowed with
     * "Display over other apps"; without it, Android decides (some phones allow it, some don't).
     */
    private fun openScreenDirectly(reason: String) {
        if (RingController.screenVisible.value) return
        AppLog.i(TAG, "Opening the ringing screen directly ($reason)")
        try {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Opening the ringing screen directly failed", e)
        }
    }

    /** Not before the first unlock after a reboot (TIDAL can't run then), and only if TIDAL is installed. */
    private fun tidalUsable(): Boolean =
        getSystemService(UserManager::class.java).isUserUnlocked && TidalLauncher.isInstalled(this)

    private suspend fun playTidal(alarm: Alarm, item: TidalItem, resume: Boolean) {
        RingController.setSound(RingController.Sound.TIDAL_STARTING)
        goForeground(alarm, getString(R.string.ringing_starting_tidal))
        // Something else already playing (a podcast left running overnight) would look like TIDAL started.
        audio.stopOtherPlayback()
        audio.prepareMusic(alarm.volumePercent, alarm.rampSeconds)

        if (resume) {
            // After a snooze TIDAL still has the playlist loaded (Lavender paused it), and then ignores the
            // play link. The Play key resumes it where it stopped, and doesn't even need the ringing screen.
            AppLog.i(TAG, "Snooze over: resuming TIDAL with the Play key")
            audio.sendPlayKey()
            if (audio.waitForMusic(RESUME_WAIT_MS)) {
                AppLog.i(TAG, "TIDAL resumed")
                keepPlaying(alarm, item)
                return
            }
            AppLog.w(TAG, "Play key didn't resume TIDAL within ${RESUME_WAIT_MS / 1000} s: opening the link instead")
        }

        if (!waitForScreen(SCREEN_WAIT_MS) && !bringScreenBack(alarm, "it didn't appear on its own")) {
            // The phone is in use: Android shows a pop-up notification instead of the ringing screen,
            // and only a visible screen may open TIDAL. Ring with the backup sound until the user opens it.
            AppLog.w(TAG, "Ringing screen didn't appear: backup sound until it's opened")
            playBackup(alarm, tidalFailed = false)
            waitForScreen(Long.MAX_VALUE)
            AppLog.i(TAG, "Ringing screen opened: switching from backup sound to TIDAL")
            backup.stop()
            playTidal(alarm, item, resume = false)
            return
        }

        for ((index, link) in TidalLauncher.playLinks(item).withIndex()) {
            if (index > 0 && !waitForScreen(SCREEN_WAIT_MS)) break
            RingController.launched.value = null
            RingController.launchRequest.value = link
            AppLog.i(TAG, "Asking the ringing screen to open link ${index + 1}: $link")
            val opened = withTimeoutOrNull(4_000) { RingController.launched.first { it == link } } != null
            if (!opened) {
                AppLog.w(TAG, "Link ${index + 1} wasn't opened within 4 s")
                break
            }
            val openedAt = System.currentTimeMillis()

            val playing = coroutineScope {
                // TIDAL is now in front of the ringing screen. Bring Snooze/Dismiss back on top as soon as
                // music plays, or after a few seconds at the latest: without "Display over other apps",
                // Android lets an app reopen its screen only shortly after it was last in front.
                val latest = launch {
                    delay(BRING_BACK_LATEST_MS)
                    bringScreenBack(alarm, "${BRING_BACK_LATEST_MS / 1000} s after opening TIDAL")
                }
                val ok = audio.waitForMusic(if (index == 0) FIRST_LINK_WAIT_MS else NEXT_LINK_WAIT_MS)
                if (ok && latest.isActive) {
                    latest.cancel()
                    bringScreenBack(alarm, "music started")
                }
                ok
            }
            AppLog.i(TAG, if (playing) "Music detected ${System.currentTimeMillis() - openedAt} ms after opening link ${index + 1}" else "No music after link ${index + 1}")
            if (playing) {
                keepPlaying(alarm, item)
                return
            }
        }
        playBackup(alarm, tidalFailed = true)
    }

    /** TIDAL plays: raise the volume, and switch to the backup sound if the music stops. */
    private suspend fun keepPlaying(alarm: Alarm, item: TidalItem) {
        RingController.setSound(RingController.Sound.TIDAL)
        goForeground(alarm, item.title)
        coroutineScope {
            val ramp = launch { audio.rampMusic(alarm.volumePercent, alarm.rampSeconds) }
            waitUntilSilent()
            ramp.cancel()
        }
        // TIDAL stopped playing (network gone, app closed, paused …): keep waking the user.
        AppLog.w(TAG, "Music stopped for ${TIDAL_SILENCE_LIMIT_MS / 1000} s while ringing: backup sound")
        playBackup(alarm, tidalFailed = true)
    }

    /**
     * Brings the ringing screen back in front of TIDAL. First directly: Android allows that shortly after the
     * screen was last shown, and always with "Display over other apps". If that doesn't work, a fresh
     * full-screen notification, which brings it back on a locked phone.
     */
    private suspend fun bringScreenBack(alarm: Alarm, reason: String): Boolean {
        if (RingController.screenVisible.value) return true
        AppLog.i(TAG, "Bringing the ringing screen back ($reason, displayOverOtherApps=${Settings.canDrawOverlays(this)})")
        try {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Opening the ringing screen directly failed", e)
        }
        if (waitForScreen(1_500)) {
            AppLog.i(TAG, "Ringing screen back in front (opened directly)")
            return true
        }
        Notifications.reshowRingingScreen(this, alarm)
        var back = waitForScreen(2_500)
        if (!back) {
            // One more direct try: some phones allow it a moment later.
            openScreenDirectly("second try")
            back = waitForScreen(1_500)
        }
        AppLog.i(
            TAG,
            if (back) "Ringing screen back in front (full-screen notification)"
            else "Ringing screen did NOT come back; Snooze/Dismiss are in the notification",
        )
        return back
    }

    private suspend fun waitForScreen(timeoutMs: Long): Boolean {
        if (RingController.screenVisible.value) return true
        return withTimeoutOrNull(timeoutMs) { RingController.screenVisible.first { it } } != null
    }

    private suspend fun waitUntilSilent() {
        var silentSince: Long? = null
        while (true) {
            delay(2_000)
            if (audio.isMusicActive) {
                silentSince = null
            } else {
                val since = silentSince ?: System.currentTimeMillis().also { silentSince = it }
                if (System.currentTimeMillis() - since >= TIDAL_SILENCE_LIMIT_MS) return
            }
        }
    }

    private fun playBackup(alarm: Alarm, tidalFailed: Boolean) {
        AppLog.i(TAG, "Backup sound (tidalFailed=$tidalFailed, ringtone=${alarm.ringtoneTitle ?: "Lavender chime"})")
        RingController.setSound(RingController.Sound.BACKUP, tidalFailed)
        goForeground(alarm, alarm.ringtoneTitle ?: getString(R.string.sound_lavender_chime))
        audio.prepareAlarmStream(alarm.volumePercent)
        backup.start(alarm.ringtoneUri, alarm.rampSeconds, scope)
    }

    private fun finish(outcome: Outcome) {
        val alarm = current
        if (alarm == null) {
            if (ringJob?.isActive != true) shutDown()
            return
        }
        if (finishing) return
        finishing = true
        AppLog.i(TAG, "Finishing #${alarm.id}: $outcome (rang ${(System.currentTimeMillis() - startedAt) / 1000} s)")
        val job = ringJob
        ringJob = null
        scope.launch {
            job?.cancelAndJoin()
            vibration?.stop()
            vibration = null
            backup.stop()
            audio.stopOtherPlayback()
            audio.restoreVolumes()
            Notifications.cancelReshow(this@AlarmService)
            withContext(NonCancellable + Dispatchers.IO) {
                val context = this@AlarmService
                when (if (testRun) Outcome.DISMISS else outcome) {
                    Outcome.SNOOZE -> {
                        val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
                        val saved = container.alarms.edit(alarm.id) { it.copy(enabled = true, snoozedUntil = until) }
                        if (saved != null) Notifications.snoozed(context, saved, until)
                    }
                    Outcome.SILENCED -> Notifications.missed(context, alarm, startedAt, silenced = true)
                    Outcome.DISMISS -> Unit
                }
                // New songs (a new random pick) for the next time this alarm rings.
                if (!testRun && outcome != Outcome.SNOOZE && alarm.isRepeating && alarm.needsWakeupPlaylist) {
                    WakeupPlaylists.prepare(context, alarm.id)
                }
                if (container.settings.current().autoSendLogs) LogUpload.schedule(context, delaySeconds = 60)
            }
            shutDown()
        }
    }

    /** A second alarm went off while one is ringing: report it as missed rather than cutting the first one off. */
    private suspend fun reportBusy(id: Long) {
        if (id == current?.id || id < 0) return
        val repo = container.alarms
        val alarm = withContext(Dispatchers.IO) { repo.get(id) } ?: return
        Notifications.missed(this, alarm, System.currentTimeMillis())
        withContext(Dispatchers.IO) { repo.save(alarm.copy(snoozedUntil = null, enabled = alarm.isRepeating)) }
    }

    private fun goForeground(alarm: Alarm?, detail: String?) {
        val notification = Notifications.ringing(this, alarm, detail)
        try {
            when {
                Build.VERSION.SDK_INT >= 34 -> startForeground(
                    Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
                Build.VERSION.SDK_INT >= 29 -> startForeground(
                    Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                else -> startForeground(Notifications.ID_RINGING, notification)
            }
        } catch (e: RuntimeException) {
            // systemExempted needs the exact-alarm permission; media playback is the fallback type.
            AppLog.w(TAG, "startForeground(systemExempted) failed, using mediaPlayback", e)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                throw e
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lavender:ringing")
            .apply { acquire(MAX_RING_MS) }
    }

    private fun shutDown() {
        RingController.stop()
        current = null
        finishing = false
        testRun = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        vibration?.stop()
        backup.stop()
        audio.restoreVolumes()
        RingController.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_FIRE = "at.mikag.lavender.service.FIRE"
        const val ACTION_SNOOZE = "at.mikag.lavender.service.SNOOZE"
        const val ACTION_DISMISS = "at.mikag.lavender.service.DISMISS"
        private const val EXTRA_TEST = "test"
        private const val TAG = "Ring"

        private const val SCREEN_WAIT_MS = 5_000L
        private const val BRING_BACK_LATEST_MS = 8_000L
        private const val RESUME_WAIT_MS = 5_000L
        private const val FIRST_LINK_WAIT_MS = 15_000L
        private const val NEXT_LINK_WAIT_MS = 10_000L
        private const val TIDAL_SILENCE_LIMIT_MS = 10_000L
        private const val MAX_RING_MS = 60 * 60 * 1000L

        fun fire(context: Context, alarmId: Long) {
            context.startForegroundService(
                Intent(context, AlarmService::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            )
        }

        /** Rings right away for trying out an alarm; snooze acts like dismiss and the schedule stays as it is. */
        fun test(context: Context, alarmId: Long) {
            context.startForegroundService(
                Intent(context, AlarmService::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    .putExtra(EXTRA_TEST, true)
            )
        }

        fun snooze(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE))
        }

        fun dismiss(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS))
        }
    }
}
