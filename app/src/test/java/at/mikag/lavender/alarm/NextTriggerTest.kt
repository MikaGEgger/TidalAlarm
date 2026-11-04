package at.mikag.lavender.alarm

import at.mikag.lavender.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NextTriggerTest {
    private val vienna = ZoneId.of("Europe/Vienna")

    // Wednesday, 4 Nov 2026, 21:00
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 11, 4, 21, 0), vienna)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(LocalDateTime.of(y, m, d, h, min), vienna)

    private fun days(vararg days: DayOfWeek) = days.fold(0) { acc, day -> acc or Alarm.dayBit(day) }

    @Test
    fun oneTimeLaterToday() {
        assertEquals(at(2026, 11, 4, 22, 30), NextTrigger.compute(Alarm(hour = 22, minute = 30), now))
    }

    @Test
    fun oneTimeEarlierRingsTomorrow() {
        assertEquals(at(2026, 11, 5, 7, 0), NextTrigger.compute(Alarm(hour = 7, minute = 0), now))
    }

    @Test
    fun sameMinuteAsNowRingsTomorrow() {
        assertEquals(at(2026, 11, 5, 21, 0), NextTrigger.compute(Alarm(hour = 21, minute = 0), now))
    }

    @Test
    fun disabledNeverRings() {
        assertNull(NextTrigger.compute(Alarm(hour = 7, minute = 0, enabled = false), now))
    }

    @Test
    fun weekdaysFromWednesdayEvening() {
        val alarm = Alarm(hour = 7, minute = 0, days = Alarm.WEEKDAYS)
        assertEquals(at(2026, 11, 5, 7, 0), NextTrigger.compute(alarm, now))
    }

    @Test
    fun weekdaysFromFridayEveningRingsMonday() {
        val friday = at(2026, 11, 6, 21, 0)
        val alarm = Alarm(hour = 7, minute = 0, days = Alarm.WEEKDAYS)
        assertEquals(at(2026, 11, 9, 7, 0), NextTrigger.compute(alarm, friday))
    }

    @Test
    fun repeatingLaterTodayRingsToday() {
        val alarm = Alarm(hour = 23, minute = 15, days = days(DayOfWeek.WEDNESDAY))
        assertEquals(at(2026, 11, 4, 23, 15), NextTrigger.compute(alarm, now))
    }

    @Test
    fun skippedOccurrenceMovesToTheOneAfter() {
        val next = at(2026, 11, 5, 7, 0)
        val alarm = Alarm(hour = 7, minute = 0, days = Alarm.WEEKDAYS, skippedOccurrence = next.toInstant().toEpochMilli())
        assertEquals(at(2026, 11, 6, 7, 0), NextTrigger.compute(alarm, now))
    }

    @Test
    fun skipOfWeeklyAlarmJumpsOneWeek() {
        val monday = at(2026, 11, 9, 7, 0)
        val alarm = Alarm(hour = 7, minute = 0, days = days(DayOfWeek.MONDAY), skippedOccurrence = monday.toInstant().toEpochMilli())
        assertEquals(at(2026, 11, 16, 7, 0), NextTrigger.compute(alarm, now))
    }

    @Test
    fun snoozeWinsOverSchedule() {
        val snoozed = now.plusMinutes(10)
        val alarm = Alarm(hour = 7, minute = 0, days = Alarm.WEEKDAYS, snoozedUntil = snoozed.toInstant().toEpochMilli())
        assertEquals(snoozed, NextTrigger.compute(alarm, now))
    }

    @Test
    fun expiredSnoozeIsIgnored() {
        val alarm = Alarm(hour = 7, minute = 0, snoozedUntil = now.minusMinutes(1).toInstant().toEpochMilli())
        assertEquals(at(2026, 11, 5, 7, 0), NextTrigger.compute(alarm, now))
    }

    @Test
    fun datedAlarmInFuture() {
        val alarm = Alarm(hour = 9, minute = 30, dateEpochDay = LocalDate.of(2026, 12, 24).toEpochDay())
        assertEquals(at(2026, 12, 24, 9, 30), NextTrigger.compute(alarm, now))
    }

    @Test
    fun datedAlarmInPastIsNull() {
        val alarm = Alarm(hour = 9, minute = 30, dateEpochDay = LocalDate.of(2026, 11, 1).toEpochDay())
        assertNull(NextTrigger.compute(alarm, now))
    }

    @Test
    fun alarmInsideSpringForwardGapStillRings() {
        // 29 Mar 2026: clocks jump from 02:00 to 03:00 in Vienna. A 02:30 alarm rings at 03:30.
        val before = at(2026, 3, 28, 22, 0)
        val result = NextTrigger.compute(Alarm(hour = 2, minute = 30), before)!!
        assertEquals(LocalDate.of(2026, 3, 29), result.toLocalDate())
        assertEquals(3, result.hour)
    }

    @Test
    fun dayBitsAreMondayFirst() {
        assertEquals(1, Alarm.dayBit(DayOfWeek.MONDAY))
        assertEquals(64, Alarm.dayBit(DayOfWeek.SUNDAY))
        assertEquals(Alarm.WEEKDAYS or Alarm.WEEKEND, Alarm.EVERY_DAY)
    }
}
