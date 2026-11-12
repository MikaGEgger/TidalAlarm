package at.mikag.lavender.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.util.Locale

/** What a phone needs so alarms ring reliably, and where to fix each item. */
object Reliability {
    data class Status(
        val notifications: Boolean,
        val fullScreen: Boolean,
        val exactAlarms: Boolean,
        val batteryUnrestricted: Boolean,
        /** "Display over other apps": optional, brings the ringing screen back in front of TIDAL when unlocked. */
        val overlay: Boolean,
    ) {
        /**
         * Without these, alarms may be silent, not show over the lock screen, or show only as a small
         * notification on an unlocked phone (that's what "Display over other apps" prevents).
         */
        val critical: Boolean get() = notifications && fullScreen && exactAlarms && overlay
    }

    fun check(context: Context): Status = Status(
        notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        fullScreen = Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
        exactAlarms = Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        overlay = Settings.canDrawOverlays(context),
    )

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    fun openNotificationSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
        }
    }

    fun openOverlaySettings(context: Context) =
        open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)))

    /** App info page, where "Battery" can be set to Unrestricted. */
    fun openAppDetails(context: Context) =
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    fun openAppLanguage(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) open(context, Intent(Settings.ACTION_APP_LOCALE_SETTINGS, packageUri(context)))
    }

    /** Xiaomi/Redmi/POCO (MIUI, HyperOS) have their own switch for apps opening screens in the background. */
    val isXiaomi: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any { it.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

    /**
     * Xiaomi's own switches, read through its app-op codes (10021 "Display pop-up windows while running in
     * the background", 10020 "Show on Lock screen"). Unofficial, so: true/false when the phone answers,
     * null when it can't be told. Not a Xiaomi phone: always true.
     */
    data class XiaomiStatus(val backgroundPopups: Boolean?, val lockScreen: Boolean?) {
        val allAllowed: Boolean get() = backgroundPopups == true && lockScreen == true
        val anyDenied: Boolean get() = backgroundPopups == false || lockScreen == false
    }

    fun xiaomiStatus(context: Context): XiaomiStatus {
        if (!isXiaomi) return XiaomiStatus(true, true)
        return XiaomiStatus(xiaomiOp(context, 10021), xiaomiOp(context, 10020))
    }

    private fun xiaomiOp(context: Context, op: Int): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val check = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        when (check.invoke(ops, op, Process.myUid(), context.packageName) as Int) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Opens Xiaomi's per-app permission list ("Other permissions"), else the normal app info. */
    fun openXiaomiPermissions(context: Context) {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
        try {
            context.startActivity(miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            openAppDetails(context)
        }
    }

    /** dontkillmyapp.com has step-by-step instructions per phone maker for keeping apps alive. */
    fun manufacturerTipsUrl(): String {
        val maker = Build.MANUFACTURER.lowercase(Locale.ROOT).replace(" ", "-")
        val known = setOf("samsung", "xiaomi", "huawei", "honor", "oneplus", "oppo", "vivo", "realme", "asus", "motorola", "sony", "nokia", "meizu", "lenovo", "google", "tecno", "infinix")
        return if (maker in known) "https://dontkillmyapp.com/$maker" else "https://dontkillmyapp.com/"
    }

    fun openUrl(context: Context, url: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: ActivityNotFoundException) {
            }
        }
    }
}
