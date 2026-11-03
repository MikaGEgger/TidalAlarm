package at.mikag.alarmspike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A persistent, copyable log of every test step, so results survive the screen locking. */
object TestLog {
    private const val PREFS = "test_log"
    private const val KEY = "log"
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized
    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = "${format.format(Date())}  $message"
        val text = (prefs.getString(KEY, "") ?: "") + line + "\n"
        prefs.edit().putString(KEY, text.takeLast(30_000)).apply()
        Log.i("AlarmTest", message)
        main.post { listeners.toList().forEach { it() } }
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun link(context: Context): String = prefs(context).getString("link", "") ?: ""

    fun query(context: Context): String = prefs(context).getString("query", "") ?: ""

    fun save(context: Context, link: String, query: String) {
        prefs(context).edit().putString("link", link).putString("query", query).apply()
    }
}
