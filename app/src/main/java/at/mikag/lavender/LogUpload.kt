package at.mikag.lavender

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Test builds only: posts new log lines as a comment on this phone's "Device log" issue in the
 * project's GitHub repo, so problems can be read there without copying logs by hand.
 * Play Store builds have no token, and then nothing here does anything.
 */
object LogUpload {
    private const val WORK_NAME = "log-upload"
    private const val PREFS = "log_upload"
    private const val KEY_ISSUE = "issue"
    private const val KEY_LAST_SENT = "last_sent"

    val isConfigured: Boolean get() = BuildConfig.LOG_UPLOAD_TOKEN.isNotBlank()

    /** Queues an upload; it waits for a network connection and retries on its own. */
    fun schedule(context: Context, delaySeconds: Long) {
        if (!isConfigured) return
        // WorkManager keeps its queue in storage that's only readable after the first unlock.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val request = OneTimeWorkRequestBuilder<LogUploadWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun lastSent(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SENT, 0L)

    internal fun issueNumber(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ISSUE, 0)

    internal fun saveIssue(context: Context, number: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ISSUE, number).apply()

    internal fun saveSent(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_SENT, System.currentTimeMillis()).apply()
}

class LogUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val http = context.container.http
    private val json = Json { ignoreUnknownKeys = true }

    private class HttpError(val code: Int, message: String) : Exception(message)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!LogUpload.isConfigured) return@withContext Result.success()
        val text = AppLog.pendingForUpload()
        if (text.isBlank()) return@withContext Result.success()
        try {
            val issue = findOrCreateIssue()
            val header = AppLog.header(applicationContext)
            val sent = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            // GitHub comments hold up to 65 536 characters.
            val chunks = text.chunked(60_000)
            chunks.forEachIndexed { index, chunk ->
                val part = if (chunks.size > 1) " · part ${index + 1}/${chunks.size}" else ""
                post("issues/$issue/comments", mapOf("body" to "**$header** · sent $sent$part\n\n```text\n$chunk\n```"))
            }
            AppLog.markUploaded(text.length)
            LogUpload.saveSent(applicationContext)
            AppLog.i("LogUpload", "Sent ${text.length} characters of log to issue #$issue")
            Result.success()
        } catch (e: HttpError) {
            AppLog.w("LogUpload", "GitHub refused the log upload (${e.code})", e)
            // 401/403/404: wrong or expired token, or missing permission. Retrying won't help.
            if (e.code in 500..599 || e.code == 429) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    /** One issue per phone, "Device log: <maker> <model>"; its number is remembered after the first upload. */
    private fun findOrCreateIssue(): Int {
        val known = LogUpload.issueNumber(applicationContext)
        if (known > 0) return known
        val title = "Device log: ${Build.MANUFACTURER} ${Build.MODEL}"
        val open = json.parseToJsonElement(get("issues?state=open&per_page=100")) as? JsonArray
        val existing = open.orEmpty().firstOrNull { issue ->
            (issue.jsonObject["title"] as? JsonPrimitive)?.contentOrNull == title
        }?.jsonObject?.get("number")?.jsonPrimitive?.intOrNull
        val number = existing ?: run {
            val created = post(
                "issues",
                mapOf("title" to title, "body" to "Automatic logs from Lavender test builds on this phone. Each comment is one upload."),
            )
            json.parseToJsonElement(created).jsonObject["number"]?.jsonPrimitive?.intOrNull
                ?: throw HttpError(0, "No issue number in GitHub's answer")
        }
        LogUpload.saveIssue(applicationContext, number)
        return number
    }

    private fun request(path: String) = Request.Builder()
        .url("https://api.github.com/repos/${BuildConfig.LOG_REPO}/$path")
        .header("Authorization", "Bearer ${BuildConfig.LOG_UPLOAD_TOKEN}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Lavender-Android")

    private fun get(path: String): String = execute(request(path).get().build())

    private fun post(path: String, fields: Map<String, String>): String {
        val body = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
        return execute(request(path).post(body.toRequestBody("application/json".toMediaType())).build())
    }

    private fun execute(request: Request): String = http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw HttpError(response.code, "GitHub ${response.code}: ${text.take(200)}")
        text
    }
}
