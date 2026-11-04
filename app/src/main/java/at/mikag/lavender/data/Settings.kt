package at.mikag.lavender.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeChoice { LAVENDER, PRISM, COTTON_CANDY, TWILIGHT, SUNSET, CITRINE, SHERBET, BLACKBERRY }

enum class DarkMode { DARK, LIGHT, SYSTEM }

enum class VolumeButtons { VOLUME, SNOOZE, DISMISS }

enum class WeekStart { SYSTEM, MONDAY, SUNDAY, SATURDAY }

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.LAVENDER,
    val darkMode: DarkMode = DarkMode.DARK,
    val defaultSnoozeMinutes: Int = 10,
    /** Stop ringing after this many minutes without a reaction. 0 = never. */
    val silenceAfterMinutes: Int = 10,
    val volumeButtons: VolumeButtons = VolumeButtons.VOLUME,
    val weekStart: WeekStart = WeekStart.SYSTEM,
)

class SettingsRepository(context: Context) {
    // Device-protected, like the alarms: the ringing screen needs the theme before first unlock.
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.createDeviceProtectedStorageContext().preferencesDataStoreFile("settings")
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            theme = prefs[THEME].toEnum(ThemeChoice.LAVENDER),
            darkMode = prefs[DARK].toEnum(DarkMode.DARK),
            defaultSnoozeMinutes = prefs[SNOOZE] ?: 10,
            silenceAfterMinutes = prefs[SILENCE] ?: 10,
            volumeButtons = prefs[VOLUME_BUTTONS].toEnum(VolumeButtons.VOLUME),
            weekStart = prefs[WEEK_START].toEnum(WeekStart.SYSTEM),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val now = AppSettings(
                theme = prefs[THEME].toEnum(ThemeChoice.LAVENDER),
                darkMode = prefs[DARK].toEnum(DarkMode.DARK),
                defaultSnoozeMinutes = prefs[SNOOZE] ?: 10,
                silenceAfterMinutes = prefs[SILENCE] ?: 10,
                volumeButtons = prefs[VOLUME_BUTTONS].toEnum(VolumeButtons.VOLUME),
                weekStart = prefs[WEEK_START].toEnum(WeekStart.SYSTEM),
            )
            val next = change(now)
            prefs[THEME] = next.theme.name
            prefs[DARK] = next.darkMode.name
            prefs[SNOOZE] = next.defaultSnoozeMinutes
            prefs[SILENCE] = next.silenceAfterMinutes
            prefs[VOLUME_BUTTONS] = next.volumeButtons.name
            prefs[WEEK_START] = next.weekStart.name
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DARK = stringPreferencesKey("dark_mode")
        val SNOOZE = intPreferencesKey("default_snooze")
        val SILENCE = intPreferencesKey("silence_after")
        val VOLUME_BUTTONS = stringPreferencesKey("volume_buttons")
        val WEEK_START = stringPreferencesKey("week_start")
    }
}
