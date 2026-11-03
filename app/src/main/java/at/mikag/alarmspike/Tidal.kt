package at.mikag.alarmspike

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import android.view.KeyEvent

/** The different ways of asking the TIDAL app to start playing. The test finds out which ones work. */
enum class Strategy(val label: String, val opensTidalScreen: Boolean) {
    LINK_HTTPS("A: Open link in TIDAL (https)", true),
    LINK_SCHEME("B: Open link in TIDAL (tidal://)", true),
    LINK_PLUS_PLAY("C: Open link, then send Play", true),
    PLAY_FROM_SEARCH("D: Ask TIDAL to play a search", true),
    MEDIA_BROWSER("E: Talk to TIDAL's media service", false),
    PLAY_KEY("F: Send Play only (resumes last song)", false),
}

object Tidal {
    const val PACKAGE = "com.aspiro.tidal"

    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null

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

    fun run(context: Context, strategy: Strategy, linkText: String, query: String, log: (String) -> Unit) {
        val item = parse(linkText)
        when (strategy) {
            Strategy.LINK_HTTPS -> open(context, item?.let { Uri.parse(it.webUrl) }, log)
            Strategy.LINK_SCHEME -> open(context, item?.let { Uri.parse(it.schemeUrl) }, log)
            Strategy.LINK_PLUS_PLAY -> {
                if (open(context, item?.let { Uri.parse(it.webUrl) }, log)) {
                    main.postDelayed({ mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, log) }, 4000)
                }
            }
            Strategy.PLAY_FROM_SEARCH -> playFromSearch(context, query, log)
            Strategy.MEDIA_BROWSER -> mediaBrowser(context, item, query, log)
            Strategy.PLAY_KEY -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, log)
        }
    }

    /** Logs after 5, 10 and 15 seconds whether any music is actually coming out of the phone. */
    fun checkPlaybackLater(handler: Handler, context: Context, log: (String) -> Unit, onSilent: (() -> Unit)?) {
        for (seconds in listOf(5, 10, 15)) {
            handler.postDelayed({
                val playing = context.getSystemService(AudioManager::class.java).isMusicActive
                log("After $seconds s: music playing = ${if (playing) "YES" else "no"}")
                if (seconds == 15 && !playing) onSilent?.invoke()
            }, seconds * 1000L)
        }
    }

    fun mediaKey(context: Context, keyCode: Int, log: (String) -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        log("Sent media key ${KeyEvent.keyCodeToString(keyCode)}")
    }

    private fun open(context: Context, uri: Uri?, log: (String) -> Unit): Boolean {
        if (uri == null) {
            log("No valid TIDAL link yet. Paste or share one first.")
            return false
        }
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

    private fun playFromSearch(context: Context, query: String, log: (String) -> Unit) {
        if (query.isBlank()) {
            log("Test D needs the playlist or track name in the second field.")
            return
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(PACKAGE)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            log("Asked TIDAL to play search \"$query\"")
        } catch (e: ActivityNotFoundException) {
            log("TIDAL doesn't accept play-from-search requests")
        } catch (e: Exception) {
            log("Search request failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Tries to control TIDAL without opening its screen, the way Android Auto does. */
    private fun mediaBrowser(context: Context, item: Item?, query: String, log: (String) -> Unit) {
        val app = context.applicationContext
        @Suppress("DEPRECATION")
        val services = app.packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(PACKAGE), 0
        )
        if (services.isEmpty()) {
            log("TIDAL offers no media service to other apps")
            return
        }
        val info = services[0].serviceInfo
        log("Found TIDAL media service ${info.name}")
        browser?.disconnect()

        var connecting: MediaBrowser? = null
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val connected = connecting ?: return
                log("TIDAL accepted the media service connection")
                val controller = MediaController(app, connected.sessionToken)
                log("TIDAL says it supports: ${describeActions(controller.playbackState?.actions ?: 0L)}")
                val controls = controller.transportControls

                fun isPlaying() = controller.playbackState?.state == PlaybackState.STATE_PLAYING

                if (item != null) {
                    controls.playFromUri(Uri.parse(item.webUrl), null)
                    log("Sent playFromUri(${item.webUrl})")
                } else {
                    log("No link, skipping playFromUri")
                }
                main.postDelayed({
                    if (!isPlaying() && item != null) {
                        controls.playFromUri(Uri.parse(item.schemeUrl), null)
                        log("Not playing yet, sent playFromUri(${item.schemeUrl})")
                    }
                }, 4000)
                main.postDelayed({
                    if (!isPlaying()) {
                        if (query.isNotBlank()) {
                            controls.playFromSearch(query, null)
                            log("Still not playing, sent playFromSearch(\"$query\")")
                        } else {
                            controls.play()
                            log("Still not playing, sent play()")
                        }
                    }
                }, 8000)
                main.postDelayed({
                    log("TIDAL reports playback state: ${stateName(controller.playbackState?.state)}")
                }, 12000)
            }

            override fun onConnectionFailed() {
                log("TIDAL refused the media service connection")
            }

            override fun onConnectionSuspended() {
                log("Media service connection dropped")
            }
        }
        val newBrowser = MediaBrowser(app, ComponentName(info.packageName, info.name), callback, null)
        connecting = newBrowser
        browser = newBrowser
        try {
            newBrowser.connect()
        } catch (e: Exception) {
            log("Connecting failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun describeActions(actions: Long): String {
        val names = listOf(
            PlaybackState.ACTION_PLAY to "play",
            PlaybackState.ACTION_PAUSE to "pause",
            PlaybackState.ACTION_PLAY_FROM_URI to "playFromUri",
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "playFromSearch",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "playFromMediaId",
        ).filter { (actions and it.first) != 0L }.map { it.second }
        return if (names.isEmpty()) "nothing reported" else names.joinToString()
    }

    private fun stateName(state: Int?): String = when (state) {
        null -> "unknown"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_NONE -> "none"
        else -> "state $state"
    }
}
