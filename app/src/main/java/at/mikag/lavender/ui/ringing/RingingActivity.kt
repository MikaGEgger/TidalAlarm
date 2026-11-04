package at.mikag.lavender.ui.ringing

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.mikag.lavender.R
import at.mikag.lavender.alarm.AlarmService
import at.mikag.lavender.alarm.RingController
import at.mikag.lavender.container
import at.mikag.lavender.data.AppSettings
import at.mikag.lavender.data.VolumeButtons
import at.mikag.lavender.tidal.TidalLauncher
import at.mikag.lavender.ui.Format
import at.mikag.lavender.ui.components.FlagStripe
import at.mikag.lavender.ui.theme.LavenderTheme
import at.mikag.lavender.ui.theme.LocalPalette
import at.mikag.lavender.ui.theme.isDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Full-screen alarm, shown over the lock screen. Also the one that opens TIDAL (only a visible screen may). */
class RingingActivity : ComponentActivity() {
    private var volumeButtons = VolumeButtons.VOLUME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Back must not silently leave a ringing alarm.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                RingController.screenVisible.value = true
                try {
                    RingController.launchRequest.filterNotNull().collect { link ->
                        RingController.launchRequest.value = null
                        if (TidalLauncher.open(this@RingingActivity, link)) RingController.launched.tryEmit(link)
                    }
                } finally {
                    RingController.screenVisible.value = false
                }
            }
        }

        lifecycleScope.launch {
            // The screen can appear a moment before the alarm is loaded; give it a few seconds.
            withTimeoutOrNull(5_000) { RingController.state.first { it != null } }
            RingController.state.first { it == null }
            finish()
        }

        lifecycleScope.launch {
            container.settings.settings.collect { volumeButtons = it.volumeButtons }
        }

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = isDark(settings.darkMode)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LavenderTheme(settings.theme, settings.darkMode) {
                RingingScreen(
                    onSnooze = { AlarmService.snooze(this) },
                    onDismiss = { AlarmService.dismiss(this) },
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (volumeButtons) {
                VolumeButtons.SNOOZE -> {
                    AlarmService.snooze(this)
                    return true
                }
                VolumeButtons.DISMISS -> {
                    AlarmService.dismiss(this)
                    return true
                }
                VolumeButtons.VOLUME -> Unit
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun RingingScreen(onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by RingController.state.collectAsStateWithLifecycle()
    val stripes = LocalPalette.current.stripes
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(Brush.verticalGradient(stripes.map { it.copy(alpha = 0.18f) }))
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            FlagStripe(height = 8.dp)
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(Format.time(context, now), fontSize = 84.sp, fontWeight = FontWeight.Light)
                Text(Format.date(now), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val alarm = state?.alarm
                if (alarm != null && alarm.label.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(alarm.label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                SoundLine(state)
            }
            val snoozeMinutes = state?.alarm?.snoozeMinutes ?: 10
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(stringResource(R.string.ringing_snooze, snoozeMinutes), style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(stringResource(R.string.action_dismiss), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SoundLine(state: RingController.Ringing?) {
    val music = state?.alarm?.tidalItem
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state?.sound) {
            null, RingController.Sound.STARTING -> Unit
            RingController.Sound.TIDAL_STARTING -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ringing_starting_tidal))
            }
            RingController.Sound.TIDAL -> {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(music?.title, music?.subtitle?.takeIf { music?.type == at.mikag.lavender.data.TidalItem.Type.TRACK })
                        .joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            RingController.Sound.BACKUP -> Text(
                stringResource(if (state?.tidalFailed == true) R.string.ringing_tidal_failed else R.string.ringing_backup),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
