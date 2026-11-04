package at.mikag.lavender.alarm

import at.mikag.lavender.data.Alarm
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** Pure calculation of when an alarm rings next. Kept free of Android classes so it can be unit-tested. */
object NextTrigger {

    fun compute(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled) return null
        val zone = now.zone
        val nowMillis = now.toInstant().toEpochMilli()

        alarm.snoozedUntil?.let { snoozed ->
            if (snoozed > nowMillis) return Instant.ofEpochMilli(snoozed).atZone(zone)
        }

        val time = LocalTime.of(alarm.hour, alarm.minute)

        if (!alarm.isRepeating) {
            alarm.date?.let { date ->
                // ZonedDateTime.of moves times inside a DST gap forward, so the alarm still rings.
                val at = ZonedDateTime.of(date, time, zone)
                return if (at.isAfter(now)) at else null
            }
            val today = ZonedDateTime.of(now.toLocalDate(), time, zone)
            return if (today.isAfter(now)) today else ZonedDateTime.of(now.toLocalDate().plusDays(1), time, zone)
        }

        // Up to 15 days ahead covers "every Monday, but skip the next one".
        for (offset in 0L..14L) {
            val date = now.toLocalDate().plusDays(offset)
            if (!alarm.ringsOn(date.dayOfWeek)) continue
            val at = ZonedDateTime.of(date, time, zone)
            if (!at.isAfter(now)) continue
            if (alarm.skippedOccurrence == at.toInstant().toEpochMilli()) continue
            return at
        }
        return null
    }

    /** The next occurrence ignoring snooze and skip: what "skip next" would skip. */
    fun nextRegular(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? =
        compute(alarm.copy(enabled = true, snoozedUntil = null, skippedOccurrence = null), now)
}
