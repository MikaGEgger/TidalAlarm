package at.mikag.alarmspike

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant "notification access". That access is what Android requires
 * before an app may see and control other apps' media sessions (TIDAL's included).
 */
class MediaAccessService : NotificationListenerService()

object Sessions {
    fun component(context: Context) = ComponentName(context, MediaAccessService::class.java)

    fun granted(context: Context): Boolean {
        val mine = component(context)
        if (Build.VERSION.SDK_INT >= 27) {
            return context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(mine)
        }
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == mine }
    }

    /** All apps' active media sessions, or null without notification access. */
    fun controllers(context: Context): List<MediaController>? {
        if (!granted(context)) return null
        return try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
        } catch (e: SecurityException) {
            null
        }
    }

    fun tidal(context: Context): MediaController? = controllers(context)?.firstOrNull { it.packageName == Tidal.PACKAGE }

    fun playingApps(context: Context): String? {
        val list = controllers(context) ?: return null
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }
        return if (playing.isEmpty()) "none" else playing.joinToString()
    }

    fun nowPlaying(controller: MediaController): String {
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val queue = controller.queueTitle
        return buildString {
            append(stateName(controller.playbackState?.state))
            if (title != null) append(", \"$title\"")
            if (artist != null) append(" by $artist")
            if (album != null) append(" (album: $album)")
            if (queue != null) append(", queue: \"$queue\"")
        }
    }

    fun stateName(state: Int?): String = when (state) {
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
