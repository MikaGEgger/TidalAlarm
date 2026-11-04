package at.mikag.alarmspike

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.view.KeyEvent

/** Round 2: the ways of getting TIDAL to play that are still open after round 1. */
enum class Strategy(val label: String, val ownChecks: Boolean) {
    LINK_PLUS_PLAY("C: Open link, then send Play to everyone (round 1)", false),
    LINK_PLUS_TIDAL_KEY("G: Open link, then send Play to TIDAL only", false),
    DIRECT_CONTROL("H: Control TIDAL directly (needs notification access)", true),
    LINK_VARIANTS("I: Try links with a \"play\" hint", true),
}

object Tidal {
    const val PACKAGE = "com.aspiro.tidal"

    data class Item(val type: String, val id: String) {
        val webUrl: String get() = "https://tidal.com/browse/$type/$id"
        val schemeUrl: String get() = "tidal://$type/$id"
    }

    /** Understands share links like https://tidal.com/browse/playlist/<uuid> or https://tidal.com/track/123/u */
    fun parse(text: String): Item? {
        val match = Regex("""(playlist|track|album|mix)/([A-Za-z0-9-]+)""").find(text) ?: return null
        return Item(match.groupValues[1], match.groupValues[2])
    }

    fun installedVersion(context: Context): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PACKAGE, 0).versionName ?: "unknown version"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun mediaVolume(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java)
        return "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
    }

    fun isMusicActive(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).isMusicActive

    /** "music playing = YES", plus which app is playing and what TIDAL plays, when notification access is on. */
    fun playingSummary(context: Context): String {
        val base = "music playing = ${if (isMusicActive(context)) "YES" else "no"}"
        val apps = Sessions.playingApps(context) ?: return base
        val tidal = Sessions.tidal(context)?.let { "; TIDAL: ${Sessions.nowPlaying(it)}" } ?: ""
        return "$base (playing apps: $apps$tidal)"
    }

    fun run(
        context: Context,
        handler: Handler,
        strategy: Strategy,
        linkText: String,
        query: String,
        alarmMode: Boolean,
        log: (String) -> Unit,
    ) {
        val item = parse(linkText)
        if (item == null) {
            log("No valid TIDAL link yet. Share a playlist or track from TIDAL to this app first.")
            return
        }
        when (strategy) {
            Strategy.LINK_PLUS_PLAY -> if (open(context, Uri.parse(item.webUrl), log)) {
                handler.postDelayed({ mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, log) }, 4000)
            }
            Strategy.LINK_PLUS_TIDAL_KEY -> if (open(context, Uri.parse(item.webUrl), log)) {
                handler.postDelayed({ tidalKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, log) }, 4000)
            }
            Strategy.DIRECT_CONTROL -> directControl(context, handler, item, query, alarmMode, log)
            Strategy.LINK_VARIANTS -> linkVariants(context, handler, item, log)
        }
    }

    /** Logs at the given seconds whether music is playing; calls onSilent if still silent at the last check. */
    fun checkPlaybackLater(
        handler: Handler,
        context: Context,
        seconds: List<Int>,
        log: (String) -> Unit,
        onSilent: (() -> Unit)?,
    ) {
        for (s in seconds) {
            handler.postDelayed({
                log("After $s s: ${playingSummary(context)}")
                if (s == seconds.last() && !isMusicActive(context)) onSilent?.invoke()
            }, s * 1000L)
        }
    }

    /** Media key to whichever app played last (round 1: that was sometimes Audible). */
    fun mediaKey(context: Context, keyCode: Int, log: (String) -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        log("Sent ${KeyEvent.keyCodeToString(keyCode)} to the last active media app")
    }

    /** Media key delivered only to TIDAL, the way a Bluetooth headset button reaches a closed app. */
    fun tidalKey(context: Context, keyCode: Int, log: (String) -> Unit) {
        val probe = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(PACKAGE)
        @Suppress("DEPRECATION")
        val target = context.packageManager.queryBroadcastReceivers(probe, 0).firstOrNull()?.activityInfo
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))
            if (target != null) {
                intent.component = ComponentName(target.packageName, target.name)
            } else {
                intent.setPackage(PACKAGE)
            }
            try {
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                log("Couldn't send media key to TIDAL: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
        val via = target?.let { " via ${it.name}" } ?: " (TIDAL lists no media button receiver)"
        log("Sent ${KeyEvent.keyCodeToString(keyCode)} to TIDAL only$via")
    }

    /** Pauses TIDAL: through its media session if we have access, otherwise with a TIDAL-only media key. */
    fun pause(context: Context, log: (String) -> Unit) {
        val controller = Sessions.tidal(context)
        if (controller != null) {
            controller.transportControls.pause()
            log("Paused TIDAL directly")
        } else {
            tidalKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE, log)
        }
    }

    private fun open(context: Context, uri: Uri, log: (String) -> Unit): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            log("Asked TIDAL to open $uri")
            true
        } catch (e: ActivityNotFoundException) {
            log("TIDAL has no screen for $uri")
            false
        } catch (e: Exception) {
            log("Opening failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------- Test H: TIDAL's own media session, reached through notification access ----------

    private fun directControl(
        context: Context,
        handler: Handler,
        item: Item,
        query: String,
        alarmMode: Boolean,
        log: (String) -> Unit,
    ) {
        if (!Sessions.granted(context)) {
            log("Test H needs notification access. Tap \"Allow notification access\" first.")
            return
        }
        if (!open(context, Uri.parse(item.webUrl), log)) return
        handler.postDelayed({ directSteps(context, handler, item, query, alarmMode, retried = false, log) }, 3000)
    }

    private fun directSteps(
        context: Context,
        handler: Handler,
        item: Item,
        query: String,
        alarmMode: Boolean,
        retried: Boolean,
        log: (String) -> Unit,
    ) {
        val controller = Sessions.tidal(context)
        if (controller == null) {
            val others = Sessions.controllers(context)?.joinToString { it.packageName }?.ifEmpty { "none" } ?: "none"
            if (!retried) {
                log("TIDAL has no media session yet (sessions: $others). Waking it with Play, then Pause.")
                tidalKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, log)
                handler.postDelayed({ tidalKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE, log) }, 1500)
                handler.postDelayed({ directSteps(context, handler, item, query, alarmMode, retried = true, log) }, 4000)
            } else {
                log("Still no TIDAL media session (sessions: $others). Test H can't continue.")
            }
            return
        }
        log("Got TIDAL's media session. It says it supports: ${describeActions(controller.playbackState?.actions ?: 0L)}")
        log("Before: ${Sessions.nowPlaying(controller)}")

        val controls = controller.transportControls
        val attempts = mutableListOf<Pair<String, () -> Unit>>(
            "playFromUri(${item.webUrl})" to { controls.playFromUri(Uri.parse(item.webUrl), null) },
            "playFromUri(${item.schemeUrl})" to { controls.playFromUri(Uri.parse(item.schemeUrl), null) },
            "playFromMediaId(${item.id})" to { controls.playFromMediaId(item.id, null) },
            "playFromMediaId(${item.type}:${item.id})" to { controls.playFromMediaId("${item.type}:${item.id}", null) },
            "playFromMediaId(${item.type}/${item.id})" to { controls.playFromMediaId("${item.type}/${item.id}", null) },
        )
        if (query.isNotBlank()) {
            attempts += "playFromSearch(\"$query\")" to { controls.playFromSearch(query, null) }
        }
        attempts += "play() (resumes TIDAL's last queue)" to { controls.play() }
        runAttempts(handler, controller, attempts, 0, stopWhenPlaying = alarmMode, log)
    }

    private fun runAttempts(
        handler: Handler,
        controller: MediaController,
        attempts: List<Pair<String, () -> Unit>>,
        index: Int,
        stopWhenPlaying: Boolean,
        log: (String) -> Unit,
    ) {
        if (index >= attempts.size) {
            log("Test H finished")
            return
        }
        val (name, action) = attempts[index]
        val step = "H${index + 1}"
        // In the diagnostic run, start each attempt from silence so every result stands on its own.
        if (!stopWhenPlaying) controller.transportControls.pause()
        handler.postDelayed({
            log("$step: sending $name")
            try {
                action()
            } catch (e: Exception) {
                log("$step failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            handler.postDelayed({
                log("$step result: ${Sessions.nowPlaying(controller)}")
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                if (stopWhenPlaying && playing) {
                    log("TIDAL is playing, stopping test H here")
                } else {
                    runAttempts(handler, controller, attempts, index + 1, stopWhenPlaying, log)
                }
            }, 5000)
        }, 1000)
    }

    // ---------- Test I: guessed link formats that might make TIDAL start playing on its own ----------

    private fun linkVariants(context: Context, handler: Handler, item: Item, log: (String) -> Unit) {
        val variants = listOf(
            "https://tidal.com/browse/${item.type}/${item.id}?play=true",
            "https://tidal.com/browse/${item.type}/${item.id}?autoplay=true",
            "tidal://${item.type}/${item.id}/play",
            "tidal://${item.type}/${item.id}?play=true",
        )
        variants.forEachIndexed { i, url ->
            val step = "I${i + 1}"
            handler.postDelayed({
                log("$step: opening $url")
                open(context, Uri.parse(url), log)
            }, i * 9000L)
            handler.postDelayed({
                log("$step result: ${playingSummary(context)}")
                pause(context, log)
            }, i * 9000L + 7000L)
        }
        handler.postDelayed({ log("Test I finished") }, variants.size * 9000L)
    }

    private fun describeActions(actions: Long): String {
        val names = listOf(
            PlaybackState.ACTION_PLAY to "play",
            PlaybackState.ACTION_PAUSE to "pause",
            PlaybackState.ACTION_PLAY_FROM_URI to "playFromUri",
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "playFromSearch",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "playFromMediaId",
            PlaybackState.ACTION_PREPARE_FROM_URI to "prepareFromUri",
        ).filter { (actions and it.first) != 0L }.map { it.second }
        return if (names.isEmpty()) "nothing reported" else names.joinToString()
    }
}
