package at.mikag.lavender.ui.settings

import android.app.Application
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mikag.lavender.BuildConfig
import at.mikag.lavender.R
import at.mikag.lavender.container
import at.mikag.lavender.data.AppSettings
import at.mikag.lavender.data.DarkMode
import at.mikag.lavender.data.TimeFormat
import at.mikag.lavender.data.VolumeButtons
import at.mikag.lavender.data.WeekStart
import at.mikag.lavender.tidal.TidalLauncher
import at.mikag.lavender.ui.Reliability
import at.mikag.lavender.ui.components.ChoiceDialog
import at.mikag.lavender.ui.components.FlagSwatch
import at.mikag.lavender.ui.components.SectionTitle
import at.mikag.lavender.ui.components.SettingRow
import at.mikag.lavender.ui.theme.Palettes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.container.settings
    private val auth = app.container.tidalAuth

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val loggedIn: StateFlow<Boolean> = auth.loggedIn
    val writeAccess: StateFlow<Boolean> = auth.writeAccess

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(change) }
    }

    fun logout() = auth.logout()

    fun login(context: android.content.Context) {
        val uri = auth.loginUri()
        try {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
            } catch (ignored: android.content.ActivityNotFoundException) {
            }
        }
    }

    val configured: Boolean get() = auth.isConfigured
}

private enum class Dialog { SNOOZE, SILENCE, VOLUME_BUTTONS, WEEK_START, TIME_FORMAT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLog: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val writeAccess by vm.writeAccess.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var reliability by remember { mutableStateOf(Reliability.check(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reliability = Reliability.check(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle(stringResource(R.string.settings_theme))
            Palettes.all.chunked(4).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { palette ->
                        val selected = palette.choice == settings.theme
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.update { it.copy(theme = palette.choice) } }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            FlagSwatch(
                                palette.stripes,
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        RoundedCornerShape(10.dp),
                                    ),
                            )
                            Text(
                                palette.name,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DarkMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.darkMode == mode,
                        onClick = { vm.update { it.copy(darkMode = mode) } },
                        label = { Text(darkModeLabel(mode)) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_alarms))
            SettingRow(
                stringResource(R.string.settings_time_format),
                timeFormatLabel(settings.timeFormat),
                onClick = { dialog = Dialog.TIME_FORMAT },
            )
            SettingRow(
                stringResource(R.string.settings_default_snooze),
                stringResource(R.string.minutes, settings.defaultSnoozeMinutes),
                onClick = { dialog = Dialog.SNOOZE },
            )
            SettingRow(
                stringResource(R.string.settings_silence_after),
                silenceLabel(settings.silenceAfterMinutes),
                onClick = { dialog = Dialog.SILENCE },
            )
            SettingRow(
                stringResource(R.string.settings_volume_buttons),
                volumeButtonsLabel(settings.volumeButtons),
                onClick = { dialog = Dialog.VOLUME_BUTTONS },
            )
            SettingRow(
                stringResource(R.string.settings_week_start),
                weekStartLabel(settings.weekStart),
                onClick = { dialog = Dialog.WEEK_START },
            )
            if (Build.VERSION.SDK_INT >= 33) {
                SettingRow(stringResource(R.string.settings_language), null, onClick = { Reliability.openAppLanguage(context) })
            }

            SectionTitle(stringResource(R.string.settings_tidal))
            val installed = TidalLauncher.isInstalled(context)
            StatusRow(
                ok = installed,
                title = stringResource(if (installed) R.string.settings_tidal_installed else R.string.settings_tidal_missing),
                text = stringResource(R.string.settings_tidal_app_text),
                onClick = null,
            )
            if (loggedIn) {
                SettingRow(stringResource(R.string.settings_tidal_logged_in), stringResource(R.string.action_logout), onClick = vm::logout)
                if (!writeAccess) {
                    SettingRow(
                        stringResource(R.string.settings_tidal_relogin),
                        stringResource(R.string.settings_tidal_relogin_text),
                        onClick = { if (vm.configured) vm.login(context) },
                    )
                }
                Text(
                    stringResource(R.string.settings_tidal_wakeup_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                SettingRow(
                    stringResource(R.string.settings_tidal_logged_out),
                    stringResource(R.string.action_login_tidal),
                    onClick = { if (vm.configured) vm.login(context) },
                )
            }

            SectionTitle(stringResource(R.string.settings_reliability))
            StatusRow(
                ok = reliability.notifications,
                title = stringResource(R.string.reliability_notifications),
                text = stringResource(R.string.reliability_notifications_text),
                onClick = { Reliability.openNotificationSettings(context) },
            )
            if (Build.VERSION.SDK_INT >= 34) {
                StatusRow(
                    ok = reliability.fullScreen,
                    title = stringResource(R.string.reliability_full_screen),
                    text = stringResource(R.string.reliability_full_screen_text),
                    onClick = { Reliability.openFullScreenSettings(context) },
                )
            }
            if (Build.VERSION.SDK_INT >= 31) {
                StatusRow(
                    ok = reliability.exactAlarms,
                    title = stringResource(R.string.reliability_exact),
                    text = stringResource(R.string.reliability_exact_text),
                    onClick = { Reliability.openExactAlarmSettings(context) },
                )
            }
            StatusRow(
                ok = reliability.overlay,
                title = stringResource(R.string.reliability_overlay),
                text = stringResource(R.string.reliability_overlay_text),
                onClick = { Reliability.openOverlaySettings(context) },
                warnOnly = true,
            )
            StatusRow(
                ok = reliability.batteryUnrestricted,
                title = stringResource(R.string.reliability_battery),
                text = stringResource(R.string.reliability_battery_text),
                onClick = { Reliability.openAppDetails(context) },
                warnOnly = true,
            )
            SettingRow(
                stringResource(R.string.reliability_phone_tips, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }),
                stringResource(R.string.reliability_phone_tips_text),
                onClick = { Reliability.openUrl(context, Reliability.manufacturerTipsUrl()) },
            )

            SectionTitle(stringResource(R.string.settings_developer))
            val lastSent = at.mikag.lavender.LogUpload.lastSent(context)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_send_logs)) },
                supportingContent = {
                    Text(
                        when {
                            !at.mikag.lavender.LogUpload.isConfigured -> stringResource(R.string.settings_auto_send_off_build)
                            lastSent > 0 -> stringResource(
                                R.string.settings_auto_send_last,
                                at.mikag.lavender.ui.Format.dayAndTime(context, lastSent),
                            )
                            else -> stringResource(R.string.settings_auto_send_text)
                        }
                    )
                },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = settings.autoSendLogs && at.mikag.lavender.LogUpload.isConfigured,
                        enabled = at.mikag.lavender.LogUpload.isConfigured,
                        onCheckedChange = { on -> vm.update { it.copy(autoSendLogs = on) } },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.log_title)) },
                supportingContent = { Text(stringResource(R.string.settings_log_text)) },
                leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLog),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )

            SectionTitle(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME) },
                supportingContent = { Text(stringResource(R.string.settings_about_text)) },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
    }

    when (dialog) {
        Dialog.SNOOZE -> ChoiceDialog(
            title = stringResource(R.string.settings_default_snooze),
            options = listOf(5, 10, 15, 20, 30),
            selected = settings.defaultSnoozeMinutes,
            label = { stringResource(R.string.minutes, it) },
            onSelect = { minutes -> vm.update { it.copy(defaultSnoozeMinutes = minutes) } },
            onDismiss = { dialog = null },
        )
        Dialog.SILENCE -> ChoiceDialog(
            title = stringResource(R.string.settings_silence_after),
            options = listOf(5, 10, 15, 20, 30, 0),
            selected = settings.silenceAfterMinutes,
            label = { silenceLabel(it) },
            onSelect = { minutes -> vm.update { it.copy(silenceAfterMinutes = minutes) } },
            onDismiss = { dialog = null },
        )
        Dialog.VOLUME_BUTTONS -> ChoiceDialog(
            title = stringResource(R.string.settings_volume_buttons),
            options = VolumeButtons.entries,
            selected = settings.volumeButtons,
            label = { volumeButtonsLabel(it) },
            onSelect = { choice -> vm.update { it.copy(volumeButtons = choice) } },
            onDismiss = { dialog = null },
        )
        Dialog.WEEK_START -> ChoiceDialog(
            title = stringResource(R.string.settings_week_start),
            options = WeekStart.entries,
            selected = settings.weekStart,
            label = { weekStartLabel(it) },
            onSelect = { choice -> vm.update { it.copy(weekStart = choice) } },
            onDismiss = { dialog = null },
        )
        Dialog.TIME_FORMAT -> ChoiceDialog(
            title = stringResource(R.string.settings_time_format),
            options = TimeFormat.entries,
            selected = settings.timeFormat,
            label = { timeFormatLabel(it) },
            onSelect = { choice -> vm.update { it.copy(timeFormat = choice) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun timeFormatLabel(choice: TimeFormat): String = stringResource(
    when (choice) {
        TimeFormat.H24 -> R.string.time_format_24
        TimeFormat.H12 -> R.string.time_format_12
        TimeFormat.SYSTEM -> R.string.time_format_system
    }
)

@Composable
private fun StatusRow(ok: Boolean, title: String, text: String, onClick: (() -> Unit)?, warnOnly: Boolean = false) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = when {
                    ok -> MaterialTheme.colorScheme.primary
                    warnOnly -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun darkModeLabel(mode: DarkMode): String = stringResource(
    when (mode) {
        DarkMode.DARK -> R.string.dark_mode_dark
        DarkMode.LIGHT -> R.string.dark_mode_light
        DarkMode.SYSTEM -> R.string.dark_mode_system
    }
)

@Composable
private fun silenceLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.silence_never) else stringResource(R.string.minutes, minutes)

@Composable
private fun volumeButtonsLabel(choice: VolumeButtons): String = stringResource(
    when (choice) {
        VolumeButtons.VOLUME -> R.string.volume_buttons_volume
        VolumeButtons.SNOOZE -> R.string.volume_buttons_snooze
        VolumeButtons.DISMISS -> R.string.volume_buttons_dismiss
    }
)

@Composable
private fun weekStartLabel(choice: WeekStart): String = when (choice) {
    WeekStart.SYSTEM -> stringResource(R.string.week_start_system)
    WeekStart.MONDAY -> java.time.DayOfWeek.MONDAY.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    WeekStart.SUNDAY -> java.time.DayOfWeek.SUNDAY.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    WeekStart.SATURDAY -> java.time.DayOfWeek.SATURDAY.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
}
