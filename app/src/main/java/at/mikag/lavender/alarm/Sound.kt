package at.mikag.lavender.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import at.mikag.lavender.AppLog
import at.mikag.lavender.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Volume and audio focus. Remembers the user's volumes and puts them back when the alarm ends. */
class AudioControl(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var savedMusic: Int? = null
    private var savedAlarm: Int? = null

    val isMusicActive: Boolean get() = audio.isMusicActive

    private fun level(stream: Int, percent: Int): Int {
        val max = audio.getStreamMaxVolume(stream)
        return (max * percent / 100f).roundToInt().coerceIn(1, max)
    }

    private fun set(stream: Int, value: Int) {
        try {
            audio.setStreamVolume(stream, value, 0)
        } catch (e: SecurityException) {
            // Some Do-Not-Disturb states forbid volume changes; the alarm still rings at the current volume.
        }
    }

    /** Media volume TIDAL starts at: low when the volume rises gradually, else the target. */
    fun prepareMusic(percent: Int, rampSeconds: Int) {
        if (savedMusic == null) savedMusic = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = level(AudioManager.STREAM_MUSIC, percent)
        set(AudioManager.STREAM_MUSIC, if (rampSeconds > 0) (target / 4).coerceAtLeast(1) else target)
    }

    suspend fun rampMusic(percent: Int, rampSeconds: Int) {
        val target = level(AudioManager.STREAM_MUSIC, percent)
        if (rampSeconds <= 0) {
            set(AudioManager.STREAM_MUSIC, target)
            return
        }
        val start = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtMost(target)
        val steps = (target - start).coerceAtLeast(1)
        for (value in start..target) {
            set(AudioManager.STREAM_MUSIC, value)
            delay(rampSeconds * 1000L / steps)
        }
    }

    /** Alarm stream volume for Lavender's own sound. A muted alarm stream is the classic silent-alarm bug. */
    fun prepareAlarmStream(percent: Int) {
        if (savedAlarm == null) savedAlarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        set(AudioManager.STREAM_ALARM, level(AudioManager.STREAM_ALARM, percent))
    }

    /** Music must be reported in two checks in a row: TIDAL sometimes blips while loading. */
    suspend fun waitForMusic(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var streak = 0
        while (System.currentTimeMillis() < end) {
            streak = if (audio.isMusicActive) streak + 1 else 0
            if (streak >= 2) return true
            delay(500)
        }
        return false
    }

    /**
     * Stops TIDAL (or whatever plays music). Taking permanent audio focus makes well-behaved players
     * pause and stay paused; the general Pause key is the backup (it worked on the lock screen in testing).
     */
    suspend fun stopOtherPlayback() {
        if (!audio.isMusicActive) return
        AppLog.i("Audio", "Music is playing: taking audio focus to pause it")
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audio.requestAudioFocus(request)
        delay(700)
        if (audio.isMusicActive) {
            AppLog.i("Audio", "Still playing after audio focus: sending the Pause key")
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        delay(500)
        audio.abandonAudioFocusRequest(request)
        AppLog.i("Audio", "Music playing after stop: ${audio.isMusicActive}")
    }

    fun restoreVolumes() {
        savedMusic?.let { set(AudioManager.STREAM_MUSIC, it) }
        savedAlarm?.let { set(AudioManager.STREAM_ALARM, it) }
        savedMusic = null
        savedAlarm = null
    }
}

/** Lavender's own alarm sound: the chosen ringtone, else the built-in chime, else the system alarm sound. */
class BackupSound(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var ramp: Job? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    val isPlaying: Boolean get() = player != null

    fun start(ringtoneUri: String?, rampSeconds: Int, scope: CoroutineScope) {
        if (player != null) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { audio.requestAudioFocus(it) }

        val chime = Uri.parse("android.resource://${context.packageName}/${R.raw.lavender_chime}")
        val candidates = listOfNotNull(
            ringtoneUri?.let(Uri::parse),
            chime,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        for (uri in candidates) {
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(attributes)
                candidate.setDataSource(context, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.setVolume(if (rampSeconds > 0) 0.15f else 1f, if (rampSeconds > 0) 0.15f else 1f)
                candidate.start()
                player = candidate
                AppLog.i("Audio", "Backup sound playing: $uri")
                break
            } catch (e: Exception) {
                // e.g. a ringtone on storage that isn't readable before first unlock: try the next one.
                AppLog.w("Audio", "Couldn't play $uri", e)
                candidate.release()
            }
        }
        val current = player ?: run {
            AppLog.w("Audio", "No backup sound could be played")
            return
        }
        if (rampSeconds > 0) {
            ramp = scope.launch {
                val steps = 20
                for (step in 1..steps) {
                    delay(rampSeconds * 1000L / steps)
                    val volume = 0.15f + 0.85f * step / steps
                    try {
                        current.setVolume(volume, volume)
                    } catch (e: IllegalStateException) {
                        return@launch
                    }
                }
            }
        }
    }

    fun stop() {
        ramp?.cancel()
        ramp = null
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}

class AlarmVibration(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    fun start() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    fun stop() = vibrator.cancel()
}
