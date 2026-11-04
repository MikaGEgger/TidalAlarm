package at.mikag.lavender

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Development log, viewable and copyable in Settings → Developer → Log.
 * Kept in device-protected storage so it also records alarms that ring before the first unlock.
 * Never log tokens or other secrets here.
 */
object AppLog {
    private const val MAX_BYTES = 300 * 1024
    private val lock = Any()
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.createDeviceProtectedStorageContext().filesDir, "lavender.log")
    }

    fun i(tag: String, message: String) {
        Log.i("Lavender/$tag", message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w("Lavender/$tag", message, error)
        append("W", tag, if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message)
    }

    private fun append(level: String, tag: String, message: String) {
        synchronized(lock) {
            val target = file ?: return
            try {
                if (target.length() > MAX_BYTES) {
                    val kept = target.readText().takeLast(MAX_BYTES / 2)
                    target.writeText(kept.substringAfter('\n'))
                }
                target.appendText("${time.format(Date())} $level $tag: $message\n")
            } catch (e: IOException) {
                // Logging must never break an alarm.
            }
        }
    }

    fun read(): String = synchronized(lock) {
        try {
            file?.takeIf { it.exists() }?.readText().orEmpty()
        } catch (e: IOException) {
            ""
        }
    }

    fun clear() = synchronized(lock) {
        try {
            file?.writeText("")
        } catch (e: IOException) {
        }
        Unit
    }

    /** Device and app details, put on top of a copied log. */
    fun header(context: Context): String {
        val tidal = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("com.aspiro.tidal", 0).versionName
        } catch (e: Exception) {
            "not installed"
        }
        return "Lavender ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · TIDAL $tidal"
    }
}
