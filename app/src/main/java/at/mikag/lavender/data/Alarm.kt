package at.mikag.lavender.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalDate

/** What an alarm plays from TIDAL. Opened in the TIDAL app with a "play" link when the alarm rings. */
data class TidalItem(
    val type: Type,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
) {
    enum class Type(val path: String) {
        PLAYLIST("playlist"),
        TRACK("track"),

        /** All tracks the user liked ("My Collection → Tracks"). Has no link of its own: played via a wake-up playlist. */
        MY_SONGS("mysongs");

        companion object {
            fun fromPath(path: String?): Type? = entries.firstOrNull { it.path == path }
        }
    }
}

@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    /** Bit 0 = Monday … bit 6 = Sunday. 0 means the alarm rings once. */
    val days: Int = 0,
    /** For a one-time alarm on a specific date (epoch day). Null: the next time the clock shows hour:minute. */
    val dateEpochDay: Long? = null,
    val enabled: Boolean = true,
    val label: String = "",
    val tidalType: String? = null,
    val tidalId: String? = null,
    val tidalTitle: String? = null,
    val tidalSubtitle: String? = null,
    val tidalImage: String? = null,
    /** Backup sound, and the main sound when no TIDAL item is set. Null = Lavender's built-in chime. */
    val ringtoneUri: String? = null,
    val ringtoneTitle: String? = null,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 10,
    /** Loudness of the alarm, as a share of the maximum (applies to TIDAL music and the backup sound). */
    val volumePercent: Int = 70,
    /** How long the volume takes to rise to [volumePercent]. 0 = full volume at once. */
    val rampSeconds: Int = 30,
    /** Trigger time (epoch millis) of a single repeating occurrence the user chose to skip. */
    val skippedOccurrence: Long? = null,
    /** When snoozed: the time it rings again (epoch millis). */
    val snoozedUntil: Long? = null,
    /** The trigger time last handed to AlarmManager. Used to notice alarms missed while the phone was off. */
    val scheduledFor: Long? = null,
    /** Play the playlist / My songs in random order. */
    @ColumnInfo(defaultValue = "0") val shuffle: Boolean = false,
    /**
     * The private playlist Lavender keeps in the user's TIDAL account for this alarm ("Lavender · 07:00"),
     * filled before each ring with the songs to play. Needed for shuffle and My songs, which TIDAL can't start by link.
     */
    val wakeupPlaylistId: String? = null,
    /** When the wake-up playlist was last filled (epoch millis). */
    val wakeupPreparedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val wakeupTrackCount: Int = 0,
) {
    val isRepeating: Boolean get() = days != 0

    val date: LocalDate? get() = dateEpochDay?.let(LocalDate::ofEpochDay)

    fun ringsOn(day: DayOfWeek): Boolean = days and dayBit(day) != 0

    val tidalItem: TidalItem?
        get() {
            val type = TidalItem.Type.fromPath(tidalType) ?: return null
            val id = tidalId ?: return null
            return TidalItem(type, id, tidalTitle ?: "", tidalSubtitle, tidalImage)
        }

    /** Shuffle and My songs are played from a wake-up playlist that Lavender fills. */
    val needsWakeupPlaylist: Boolean
        get() = when (tidalItem?.type) {
            TidalItem.Type.MY_SONGS -> true
            TidalItem.Type.PLAYLIST -> shuffle
            else -> false
        }

    /** Shown as the wake-up playlist's name in TIDAL. */
    fun wakeupPlaylistName(time: String): String =
        "Lavender · $time" + if (label.isNotBlank()) " · $label" else ""

    fun withTidal(item: TidalItem?): Alarm = copy(
        tidalType = item?.type?.path,
        tidalId = item?.id,
        tidalTitle = item?.title,
        tidalSubtitle = item?.subtitle,
        tidalImage = item?.imageUrl,
    )

    companion object {
        fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
        const val EVERY_DAY = 0b1111111
    }
}
