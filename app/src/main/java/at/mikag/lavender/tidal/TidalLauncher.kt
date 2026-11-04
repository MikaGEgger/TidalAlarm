package at.mikag.lavender.tidal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import at.mikag.lavender.data.TidalItem

/**
 * Starts music in the TIDAL app. Tested on a real phone (locked and unlocked): links with a "play"
 * hint make TIDAL start the playlist on its own; plain links only open it.
 */
object TidalLauncher {
    const val PACKAGE = "com.aspiro.tidal"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** In the order they're tried. The first worked in almost every test; the second is the backup. */
    fun playLinks(item: TidalItem): List<String> = listOf(
        "https://tidal.com/browse/${item.type.path}/${item.id}?play=true",
        "tidal://${item.type.path}/${item.id}?play=true",
    )

    fun webLink(item: TidalItem): String = "https://tidal.com/browse/${item.type.path}/${item.id}"

    fun open(context: Context, link: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Understands TIDAL share links: tidal.com/browse/playlist/<uuid>, tidal.com/track/123, tidal://track/123 … */
    fun parseLink(text: String): Pair<TidalItem.Type, String>? {
        val match = Regex("""(playlist|track)/([A-Za-z0-9-]+)""").find(text) ?: return null
        val type = TidalItem.Type.fromPath(match.groupValues[1]) ?: return null
        return type to match.groupValues[2]
    }
}
