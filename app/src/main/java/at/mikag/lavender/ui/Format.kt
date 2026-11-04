package at.mikag.lavender.ui

import android.content.Context
import android.text.format.DateFormat
import at.mikag.lavender.R
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.data.TimeFormat
import at.mikag.lavender.data.WeekStart
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Time and date texts, following the phone's 12/24-hour setting and language. */
object Format {
    /** Kept in sync with the setting by LavenderApp; used everywhere times are shown (also in notifications). */
    @Volatile
    var timeFormat: TimeFormat = TimeFormat.H24

    fun is24h(context: Context): Boolean = when (timeFormat) {
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    }

    fun time(context: Context, millis: Long): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (is24h(context)) "Hm" else "hm")
        return DateFormat.format(pattern, Date(millis)).toString()
    }

    fun time(context: Context, hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return time(context, calendar.timeInMillis)
    }

    fun dayAndTime(context: Context, millis: Long): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE")
        return DateFormat.format(pattern, millis).toString() + " " + time(context, millis)
    }

    fun date(millis: Long): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")
        return DateFormat.format(pattern, millis).toString()
    }

    fun date(date: LocalDate): String = date(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /** "7 h 5 min", "12 min", "less than a minute". */
    fun duration(context: Context, millis: Long): String {
        val totalMinutes = ((millis + 59_999) / 60_000).coerceAtLeast(0)
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            totalMinutes < 1 -> context.getString(R.string.duration_less_than_minute)
            days > 0 -> context.getString(R.string.duration_days_hours, days, hours)
            hours > 0 -> context.getString(R.string.duration_hours_minutes, hours, minutes)
            else -> context.getString(R.string.duration_minutes, minutes)
        }
    }

    fun weekOrder(weekStart: WeekStart): List<DayOfWeek> {
        val first = when (weekStart) {
            WeekStart.SYSTEM -> WeekFields.of(Locale.getDefault()).firstDayOfWeek
            WeekStart.MONDAY -> DayOfWeek.MONDAY
            WeekStart.SUNDAY -> DayOfWeek.SUNDAY
            WeekStart.SATURDAY -> DayOfWeek.SATURDAY
        }
        return (0L..6L).map { first.plus(it) }
    }

    fun shortDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    fun narrowDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()).take(2)

    /** "Every day", "Weekdays", "Mon, Wed, Fri", "Tomorrow", "Thu, 12 Nov". */
    fun repeatSummary(context: Context, alarm: Alarm, weekStart: WeekStart, nextAt: Long?): String = when {
        alarm.days == Alarm.EVERY_DAY -> context.getString(R.string.repeat_every_day)
        alarm.days == Alarm.WEEKDAYS -> context.getString(R.string.repeat_weekdays)
        alarm.days == Alarm.WEEKEND -> context.getString(R.string.repeat_weekend)
        alarm.isRepeating -> weekOrder(weekStart).filter(alarm::ringsOn).joinToString(", ") { shortDay(it) }
        else -> {
            val day = (nextAt ?: alarm.date?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli())
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            val today = LocalDate.now()
            when (day) {
                null -> context.getString(R.string.repeat_once)
                today -> context.getString(R.string.repeat_today)
                today.plusDays(1) -> context.getString(R.string.repeat_tomorrow)
                else -> date(day)
            }
        }
    }
}
