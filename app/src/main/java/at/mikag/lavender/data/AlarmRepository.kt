package at.mikag.lavender.data

import android.content.Context
import at.mikag.lavender.alarm.AlarmScheduler
import at.mikag.lavender.alarm.NextTrigger
import at.mikag.lavender.alarm.Notifications
import at.mikag.lavender.alarm.RingController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

/** All alarm changes go through here, so the database and AlarmManager never disagree. */
class AlarmRepository(
    private val context: Context,
    private val dao: AlarmDao,
    private val scheduler: AlarmScheduler,
) {
    private val lock = Mutex()

    val alarms: Flow<List<Alarm>> = dao.observeAll()

    fun observe(id: Long): Flow<Alarm?> = dao.observe(id)

    suspend fun get(id: Long): Alarm? = dao.get(id)

    suspend fun create(alarm: Alarm): Alarm = lock.withLock {
        val id = dao.insert(alarm)
        val scheduled = scheduler.schedule(alarm.copy(id = id))
        dao.update(scheduled)
        scheduled
    }

    /** Saves and (re)schedules. Returns the alarm as stored. */
    suspend fun save(alarm: Alarm): Alarm = lock.withLock { saveLocked(alarm) }

    suspend fun delete(alarm: Alarm) = lock.withLock {
        scheduler.cancel(alarm)
        Notifications.cancelSnoozed(context, alarm.id)
        dao.delete(alarm)
    }

    /** Applies [change] to the current stored version of the alarm, then saves. */
    suspend fun edit(id: Long, change: (Alarm) -> Alarm): Alarm? = lock.withLock {
        val current = dao.get(id) ?: return@withLock null
        saveLocked(change(current))
    }

    /** "Skip next" / "Dismiss now": for a one-time alarm that means switching it off. */
    suspend fun skipNext(id: Long): Alarm? = edit(id) { alarm ->
        when {
            alarm.snoozedUntil != null -> alarm.copy(snoozedUntil = null, enabled = alarm.isRepeating)
            !alarm.isRepeating -> alarm.copy(enabled = false)
            else -> {
                val next = NextTrigger.nextRegular(alarm, ZonedDateTime.now())
                alarm.copy(skippedOccurrence = next?.toInstant()?.toEpochMilli())
            }
        }
    }

    suspend fun undoSkip(id: Long): Alarm? = edit(id) { it.copy(skippedOccurrence = null) }

    /**
     * Re-registers every alarm with AlarmManager (after boot, time zone change, app update …).
     * With [reportMissed], alarms whose time passed while they couldn't ring get a "missed" notification.
     */
    suspend fun rescheduleAll(reportMissed: Boolean) = lock.withLock {
        val now = System.currentTimeMillis()
        for (alarm in dao.getAll()) {
            val scheduledFor = alarm.scheduledFor
            val missed = reportMissed && alarm.enabled && scheduledFor != null &&
                scheduledFor < now - MISSED_GRACE_MS && !RingController.isRinging(alarm.id)
            if (missed) {
                if (now - scheduledFor!! < MISSED_REPORT_WINDOW_MS) Notifications.missed(context, alarm, scheduledFor)
                saveLocked(alarm.copy(snoozedUntil = null, enabled = alarm.isRepeating))
            } else {
                saveLocked(alarm)
            }
        }
    }

    private suspend fun saveLocked(alarm: Alarm): Alarm {
        val scheduled = scheduler.schedule(alarm)
        dao.update(scheduled)
        if (scheduled.snoozedUntil == null) Notifications.cancelSnoozed(context, alarm.id)
        return scheduled
    }

    private companion object {
        const val MISSED_GRACE_MS = 60_000L
        const val MISSED_REPORT_WINDOW_MS = 12 * 60 * 60 * 1000L
    }
}
