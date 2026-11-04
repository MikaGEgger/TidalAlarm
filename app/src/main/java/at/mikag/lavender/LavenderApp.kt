package at.mikag.lavender

import android.app.Application
import android.content.Context
import at.mikag.lavender.alarm.AlarmScheduler
import at.mikag.lavender.alarm.Notifications
import at.mikag.lavender.data.AlarmDatabase
import at.mikag.lavender.data.AlarmRepository
import at.mikag.lavender.data.SettingsRepository
import at.mikag.lavender.tidal.TidalApi
import at.mikag.lavender.tidal.TidalAuth
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LavenderApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Safe before first unlock: notification channels don't touch credential-protected storage.
        Notifications.createChannels(this)
    }
}

/**
 * Hand-made dependency container. Everything is lazy: the app may start before the phone is first
 * unlocked (to ring an alarm), when only device-protected storage is available.
 */
class AppContainer(private val app: Application) {
    private val database by lazy { AlarmDatabase.create(app) }
    val scheduler by lazy { AlarmScheduler(app) }
    val alarms by lazy { AlarmRepository(app, database.alarms(), scheduler) }
    val settings by lazy { SettingsRepository(app) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Credential-protected: only use after the user has unlocked the phone. */
    val tidalAuth by lazy { TidalAuth(app, http) }
    val tidalApi by lazy { TidalApi(http, tidalAuth) }
}

val Context.container: AppContainer get() = (applicationContext as LavenderApp).container
