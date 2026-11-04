package at.mikag.lavender.ui

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import at.mikag.lavender.container
import at.mikag.lavender.data.AppSettings
import at.mikag.lavender.ui.alarms.AlarmListScreen
import at.mikag.lavender.ui.editor.AlarmEditorScreen
import at.mikag.lavender.ui.picker.TidalPickerScreen
import at.mikag.lavender.ui.settings.SettingsScreen
import at.mikag.lavender.ui.theme.LavenderTheme
import at.mikag.lavender.ui.theme.isDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // Force-stopping an app deletes its alarms in Android; opening the app restores them.
            lifecycleScope.launch { container.alarms.rescheduleAll(reportMissed = false) }
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = isDark(settings.darkMode)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LavenderTheme(settings.theme, settings.darkMode) {
                AskForNotifications()
                LavenderNavigation()
            }
        }
    }
}

@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
private fun LavenderNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "alarms") {
        composable("alarms") {
            AlarmListScreen(
                onOpenAlarm = { id -> nav.navigate("alarm/$id") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("alarm/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            AlarmEditorScreen(
                alarmId = id,
                onBack = { nav.popBackStack() },
                onPickMusic = { nav.navigate("alarm/$id/music") },
            )
        }
        composable("alarm/{id}/music", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            TidalPickerScreen(alarmId = id, onDone = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
