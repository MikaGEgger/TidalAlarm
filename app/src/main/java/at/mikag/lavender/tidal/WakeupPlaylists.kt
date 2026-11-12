package at.mikag.lavender.tidal

import android.content.Context
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import at.mikag.lavender.AppLog
import at.mikag.lavender.container
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.data.TidalItem
import at.mikag.lavender.ui.Format
import java.io.IOException
import kotlin.random.Random

/** Which songs go into a wake-up playlist. Pure, so it can be unit-tested. */
object WakeupSelection {
    /** About three hours of music: plenty for waking up, and one TIDAL request to add. */
    const val MAX_TRACKS = 50

    fun pick(source: List<String>, shuffle: Boolean, random: Random = Random.Default, max: Int = MAX_TRACKS): List<String> {
        val unique = source.distinct()
        return if (shuffle) unique.shuffled(random).take(max) else unique.take(max)
    }
}

/**
 * Keeps each alarm's wake-up playlist in the user's TIDAL account up to date. TIDAL can't be told by link
 * to shuffle or to play the liked tracks, but it can play a playlist, so Lavender fills one with exactly
 * the songs to play, in the order to play them.
 */
object WakeupPlaylists {
    private const val TAG = "Wakeup"

    /** Queues filling [alarmId]'s wake-up playlist (or removing it if no longer needed). Waits for network, retries. */
    fun prepare(context: Context, alarmId: Long) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val request = OneTimeWorkRequestBuilder<WakeupPlaylistWorker>()
            .setInputData(workDataOf(WakeupPlaylistWorker.KEY_ALARM to alarmId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("wakeup-$alarmId", ExistingWorkPolicy.REPLACE, request)
    }

    /** Deletes a wake-up playlist whose alarm is gone. */
    fun discard(context: Context, playlistId: String) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val request = OneTimeWorkRequestBuilder<WakeupPlaylistWorker>()
            .setInputData(workDataOf(WakeupPlaylistWorker.KEY_DISCARD to playlistId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("discard-$playlistId", ExistingWorkPolicy.KEEP, request)
    }

    /** Does the actual work; called by the worker. */
    internal suspend fun fill(context: Context, alarmId: Long) {
        val repo = context.container.alarms
        val api = context.container.tidalApi
        val auth = context.container.tidalAuth
        val alarm = repo.get(alarmId) ?: return
        val item = alarm.tidalItem

        if (!alarm.needsWakeupPlaylist || item == null) {
            alarm.wakeupPlaylistId?.let { id ->
                AppLog.i(TAG, "#$alarmId no longer needs its wake-up playlist: deleting $id")
                runCatching { api.deletePlaylist(id) }
                repo.edit(alarmId) { it.copy(wakeupPlaylistId = null, wakeupPreparedAt = null, wakeupTrackCount = 0) }
            }
            return
        }
        if (!auth.loggedIn.value) {
            AppLog.w(TAG, "#$alarmId: not logged in to TIDAL, can't prepare the wake-up playlist")
            return
        }
        if (!auth.writeAccess.value) {
            AppLog.w(TAG, "#$alarmId: TIDAL login without playlist access; log in again")
            return
        }

        val source = when (item.type) {
            TidalItem.Type.MY_SONGS -> api.likedTrackIds()
            TidalItem.Type.PLAYLIST -> api.playlistTrackIds(item.id)
            TidalItem.Type.TRACK -> return
        }
        val tracks = WakeupSelection.pick(source, alarm.shuffle)
        AppLog.i(TAG, "#$alarmId: ${source.size} songs in ${item.type.path} ${item.title}, ${tracks.size} picked (shuffle=${alarm.shuffle})")
        if (tracks.isEmpty()) return

        val name = alarm.wakeupPlaylistName(Format.time(context, alarm.hour, alarm.minute))
        var playlistId = alarm.wakeupPlaylistId
        val existing = playlistId?.let { id ->
            try {
                api.playlistItems(id)
            } catch (e: TidalApi.ApiException) {
                if (e.code == 404) null else throw e
            }
        }
        if (playlistId == null || existing == null) {
            playlistId = api.createPlaylist(name, "Filled by the Lavender alarm app before each alarm. Lavender replaces the songs every time.")
            AppLog.i(TAG, "#$alarmId: created wake-up playlist $playlistId \"$name\"")
        } else {
            api.removeItems(playlistId, existing)
            runCatching { api.renamePlaylist(playlistId, name) }
        }
        api.addTracks(playlistId, tracks)
        val id = playlistId
        repo.edit(alarmId) { it.copy(wakeupPlaylistId = id, wakeupPreparedAt = System.currentTimeMillis(), wakeupTrackCount = tracks.size) }
        AppLog.i(TAG, "#$alarmId: wake-up playlist $id ready with ${tracks.size} songs")
    }
}

class WakeupPlaylistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val discard = inputData.getString(KEY_DISCARD)
        return try {
            if (discard != null) {
                applicationContext.container.tidalApi.deletePlaylist(discard)
                AppLog.i("Wakeup", "Deleted wake-up playlist $discard")
            } else {
                WakeupPlaylists.fill(applicationContext, inputData.getLong(KEY_ALARM, -1L))
            }
            Result.success()
        } catch (e: TidalApi.ApiException) {
            AppLog.w("Wakeup", "TIDAL refused (${e.code})", e)
            when (e.code) {
                403 -> {
                    // The login can't change playlists: the user has to log in again; retrying won't help.
                    applicationContext.container.tidalAuth.markWriteDenied()
                    Result.failure()
                }
                404 -> Result.success()
                401 -> Result.failure()
                else -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
        } catch (e: IOException) {
            AppLog.w("Wakeup", "No connection to TIDAL, will retry", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_ALARM = "alarm"
        const val KEY_DISCARD = "discard"
    }
}

/** Convenience for alarms: queue preparation when it's needed. */
fun Alarm.prepareWakeupIfNeeded(context: Context) {
    if (needsWakeupPlaylist || wakeupPlaylistId != null) WakeupPlaylists.prepare(context, id)
}
