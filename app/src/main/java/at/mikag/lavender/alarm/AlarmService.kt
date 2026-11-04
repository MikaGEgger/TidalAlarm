package at.mikag.lavender.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import at.mikag.lavender.R
import at.mikag.lavender.container
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.data.TidalItem
import at.mikag.lavender.tidal.TidalLauncher
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
            if (alarm.vibrate) vibration = AlarmVibration(this@AlarmService).also { it.start() }

            val silenceAfter = withContext(Dispatchers.IO) { container.settings.current().silenceAfterMinutes }
            if (silenceAfter > 0) {
                launch {
                    delay(silenceAfter * 60_000L)
                    finish(Outcome.SILENCED)
                }
            }

            val item = alarm.tidalItem
            if (item != null && tidalUsable()) {
                playTidal(alarm, item)
            } else {
                playBackup(alarm, tidalFailed = item != null)
            }
        }
    }

    /** Not before the first unlock after a reboot (TIDAL can't run then), and only if TIDAL is installed. */
    private fun tidalUsable(): Boolean =
        getSystemService(UserManager::class.java).isUserUnlocked && TidalLauncher.isInstalled(this)

    private suspend fun playTidal(alarm: Alarm, item: TidalItem) {
        RingController.setSound(RingController.Sound.TIDAL_STARTING)
        goForeground(alarm, getString(R.string.ringing_starting_tidal))
        audio.prepareMusic(alarm.volumePercent, alarm.rampSeconds)

        if (!waitForScreen(SCREEN_WAIT_MS)) {
            // The phone is in use: Android shows a pop-up notification instead of the ringing screen,
            // and only a visible screen may open TIDAL. Ring with the backup sound until the user opens it.
            playBackup(alarm, tidalFailed = false)
            waitForScreen(Long.MAX_VALUE)
            backup.stop()
            playTidal(alarm, item)
            return
        }

        for ((index, link) in TidalLauncher.playLinks(item).withIndex()) {
            if (index > 0 && !waitForScreen(SCREEN_WAIT_MS)) break
            RingController.launchRequest.value = link
            val opened = withTimeoutOrNull(4_000) { RingController.launched.first { it == link } } != null
            if (!opened) break

            val playing = coroutineScope {
                // TIDAL is now in front of the ringing screen; bring Snooze/Dismiss back on top.
                launch {
                    delay(RESHOW_DELAY_MS)
                    Notifications.reshowRingingScreen(this@AlarmService, alarm)
                }
                audio.waitForMusic(if (index == 0) FIRST_LINK_WAIT_MS else NEXT_LINK_WAIT_MS)
            }
            if (playing) {
                RingController.setSound(RingController.Sound.TIDAL)
                goForeground(alarm, item.title)
                coroutineScope {
                    val ramp = launch { audio.rampMusic(alarm.volumePercent, alarm.rampSeconds) }
                    waitUntilSilent()
                    ramp.cancel()
                }
                // TIDAL stopped playing (network gone, app closed, paused …): keep waking the user.
                playBackup(alarm, tidalFailed = true)
                return
            }
        }
        playBackup(alarm, tidalFailed = true)
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

        private const val SCREEN_WAIT_MS = 5_000L
        private const val RESHOW_DELAY_MS = 5_000L
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
