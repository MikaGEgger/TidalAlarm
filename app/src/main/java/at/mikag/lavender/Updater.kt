package at.mikag.lavender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.mikag.lavender.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Test builds only: watches the repo's "latest-build" release and installs newer builds.
 * Play Store builds have SELF_UPDATE = false (and no install permission), so nothing here runs there.
 */
object Updater {
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 40_000
    private const val PREFS = "updater"
    private const val KEY_NOTIFIED = "notified_code"
    private const val KEY_LAST_CHECK = "last_check"

    val isEnabled: Boolean get() = BuildConfig.SELF_UPDATE

    data class Release(val versionCode: Int, val title: String, val assetUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** Checks at most every 30 minutes unless [force]. */
    suspend fun check(context: Context, force: Boolean = false): State {
        if (!isEnabled) return State.Idle
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = _state.value
        if (!force && current is State.Available) return current
        if (!force && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < 30 * 60_000L && current != State.Idle) {
            return current
        }
        _state.value = State.Checking
        val result = try {
            val release = latestRelease(context)
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null && release.versionCode > BuildConfig.VERSION_CODE) State.Available(release) else State.UpToDate
        } catch (e: GitHubError) {
            AppLog.w("Update", "Update check refused (${e.code})", e)
            State.Failed(
                if (e.code == 404 || e.code == 403) context.getString(R.string.update_error_permission)
                else context.getString(R.string.update_error_generic)
            )
        } catch (e: IOException) {
            State.Failed(context.getString(R.string.update_error_network))
        }
        _state.value = result
        return result
    }

    private class GitHubError(val code: Int, message: String) : Exception(message)

    private suspend fun latestRelease(context: Context): Release? = withContext(Dispatchers.IO) {
        val text = execute(context, request("releases/tags/latest-build").build()) { it.body?.string().orEmpty() }
        val root = json.parseToJsonElement(text).jsonObject
        val notes = (root["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val title = (root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val code = Regex("""versionCode:\s*(\d+)""").find(notes)?.groupValues?.get(1)?.toIntOrNull()
            ?: return@withContext null
        val asset = (root["assets"] as? JsonArray).orEmpty().map { it.jsonObject }
            .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
            ?: return@withContext null
        val url = (asset["url"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null
        val size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L
        Release(code, title, url, size)
    }

    /** Downloads the APK and opens Android's installer. Asks for "Install unknown apps" first if needed. */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            AppLog.i("Update", "Asking for permission to install apps")
            open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            _state.value = State.Available(release)
            return
        }
        _state.value = State.Downloading(release, 0)
        val file = try {
            download(context, release)
        } catch (e: Exception) {
            AppLog.w("Update", "Download failed", e)
            _state.value = State.Failed(context.getString(R.string.update_error_network))
            return
        }
        AppLog.i("Update", "Downloaded ${release.title} (${file.length()} bytes), opening the installer")
        _state.value = State.Available(release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        open(
            context,
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "lavender.apk")
        // Asking for the binary makes GitHub redirect to a short-lived download address (OkHttp drops the token there).
        val request = Request.Builder()
            .url(release.assetUrl)
            .header("Authorization", "Bearer ${BuildConfig.LOG_UPLOAD_TOKEN}")
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Lavender-Android")
            .build()
        execute(context, request) { response ->
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) _state.value = State.Downloading(release, (done * 100 / total).toInt())
                    }
                }
            }
        }
        target
    }

    private fun request(path: String) = Request.Builder()
        .url("https://api.github.com/repos/${BuildConfig.LOG_REPO}/$path")
        .header("Authorization", "Bearer ${BuildConfig.LOG_UPLOAD_TOKEN}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Lavender-Android")

    private fun <T> execute(context: Context, request: Request, read: (okhttp3.Response) -> T): T =
        context.container.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubError(response.code, "GitHub ${response.code}")
            read(response)
        }

    private fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            AppLog.w("Update", "No app to handle ${intent.action}", e)
        }
    }

    // ---------- Background: a check every few hours, with a notification for new builds ----------

    fun scheduleBackgroundChecks(context: Context) {
        if (!isEnabled) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun notifyIfNew(context: Context, release: Release) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED, 0) >= release.versionCode) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(release.title)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        prefs.edit().putInt(KEY_NOTIFIED, release.versionCode).apply()
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = Updater.check(applicationContext, force = true)
        if (state is Updater.State.Available) Updater.notifyIfNew(applicationContext, state.release)
        return Result.success()
    }
}
