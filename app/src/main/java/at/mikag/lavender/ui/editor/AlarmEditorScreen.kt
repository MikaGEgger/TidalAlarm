package at.mikag.lavender.ui.editor

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import at.mikag.lavender.R
import at.mikag.lavender.alarm.AlarmService
import at.mikag.lavender.container
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.data.AppSettings
import at.mikag.lavender.data.TidalItem
import at.mikag.lavender.tidal.TidalLauncher
import at.mikag.lavender.tidal.prepareWakeupIfNeeded
import at.mikag.lavender.ui.Format
import at.mikag.lavender.ui.Reliability
import at.mikag.lavender.ui.components.ChoiceDialog
import at.mikag.lavender.ui.components.DateDialog
import at.mikag.lavender.ui.components.SectionTitle
import at.mikag.lavender.ui.components.TimePickerDialog
import at.mikag.lavender.ui.ringing.RingingActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

class AlarmEditorViewModel(app: Application, private val id: Long) : AndroidViewModel(app) {
    private val repo = app.container.alarms

    val alarm: StateFlow<Alarm?> = repo.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AppSettings> =
        app.container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val auth = app.container.tidalAuth
    val tidalLoggedIn: StateFlow<Boolean> = auth.loggedIn
    val tidalWriteAccess: StateFlow<Boolean> = auth.writeAccess

    /** [refreshWakeup]: the change affects the wake-up playlist (songs, shuffle, or its name). */
    private fun update(refreshWakeup: Boolean = false, change: (Alarm) -> Alarm) {
        viewModelScope.launch {
            val saved = repo.edit(id, change)
            if (refreshWakeup) saved?.prepareWakeupIfNeeded(getApplication())
        }
    }

    /** Changing when an alarm rings switches it on, like other alarm apps do. */
    private fun reschedule(refreshWakeup: Boolean = false, change: (Alarm) -> Alarm) = update(refreshWakeup) {
        change(it).copy(enabled = true, skippedOccurrence = null, snoozedUntil = null)
    }

    fun setTime(hour: Int, minute: Int) = reschedule(refreshWakeup = true) { it.copy(hour = hour, minute = minute) }

    fun toggleDay(day: DayOfWeek) = reschedule {
        val days = it.days xor Alarm.dayBit(day)
        it.copy(days = days, dateEpochDay = if (days != 0) null else it.dateEpochDay)
    }

    fun setDate(date: LocalDate?) = reschedule { it.copy(dateEpochDay = date?.toEpochDay(), days = 0) }

    fun setLabel(label: String) = update(refreshWakeup = true) { it.copy(label = label) }

    fun clearMusic() = update(refreshWakeup = true) { it.withTidal(null).copy(shuffle = false) }

    fun setShuffle(on: Boolean) = update(refreshWakeup = true) { it.copy(shuffle = on) }

    /** Try preparing again, e.g. after logging in once more. */
    fun retryWakeup() = update(refreshWakeup = true) { it }

    fun login(context: android.content.Context) {
        val uri = auth.loginUri()
        try {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (ignored: android.content.ActivityNotFoundException) {
            }
        }
    }

    fun setRingtone(uri: String?, title: String?) = update { it.copy(ringtoneUri = uri, ringtoneTitle = title) }

    fun setVolume(percent: Int) = update { it.copy(volumePercent = percent) }

    fun setRamp(seconds: Int) = update { it.copy(rampSeconds = seconds) }

    fun setVibrate(on: Boolean) = update { it.copy(vibrate = on) }

    fun setSnooze(minutes: Int) = update { it.copy(snoozeMinutes = minutes) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        repo.get(id)?.let { repo.delete(it) }
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(alarmId: Long, onBack: () -> Unit, onPickMusic: () -> Unit) {
    val vm: AlarmEditorViewModel = viewModel(
        key = "alarm-$alarmId",
        factory = viewModelFactory { initializer { AlarmEditorViewModel(this[APPLICATION_KEY]!!, alarmId) } },
    )
    val alarm by vm.alarm.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Rings right away, without changing the alarm's schedule.
                        AlarmService.test(context, alarmId)
                        context.startActivity(Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) {
                        Icon(Icons.Filled.NotificationsActive, contentDescription = stringResource(R.string.action_test_alarm))
                    }
                    IconButton(onClick = { vm.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        val current = alarm
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        EditorContent(current, settings, vm, onPickMusic, Modifier.padding(padding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    alarm: Alarm,
    settings: AppSettings,
    vm: AlarmEditorViewModel,
    onPickMusic: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val music = alarm.tidalItem
    var showTime by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showRamp by remember { mutableStateOf(false) }
    var showSnooze by remember { mutableStateOf(false) }
    var showSound by remember { mutableStateOf(false) }
    var label by rememberSaveable(alarm.id) { mutableStateOf(alarm.label) }
    LaunchedEffect(label) {
        delay(400)
        if (label != alarm.label) vm.setLabel(label)
    }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        if (uri == null) {
            vm.setRingtone(null, null)
        } else {
            val title = try {
                RingtoneManager.getRingtone(context, uri)?.getTitle(context)
            } catch (e: Exception) {
                null
            }
            vm.setRingtone(uri.toString(), title)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text(
            Format.time(context, alarm.hour, alarm.minute),
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { showTime = true }.padding(top = 8.dp),
        )
        val nextText = when {
            !alarm.enabled -> stringResource(R.string.editor_off)
            alarm.scheduledFor != null -> stringResource(
                R.string.editor_rings_in,
                Format.duration(context, alarm.scheduledFor - System.currentTimeMillis()),
            )
            else -> ""
        }
        Text(
            nextText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle(stringResource(R.string.editor_repeat))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Format.weekOrder(settings.weekStart).forEach { day ->
                FilterChip(
                    selected = alarm.ringsOn(day),
                    onClick = { vm.toggleDay(day) },
                    label = {
                        Text(Format.narrowDay(day), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!alarm.isRepeating) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.editor_date)) },
                supportingContent = {
                    Text(alarm.date?.let { Format.date(it) } ?: stringResource(R.string.editor_date_next))
                },
                trailingContent = {
                    if (alarm.date != null) {
                        IconButton(onClick = { vm.setDate(null) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                modifier = Modifier.clickable { showDate = true },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
        }

        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(60) },
            label = { Text(stringResource(R.string.editor_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SectionTitle(stringResource(R.string.editor_sound))
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_music)) },
            supportingContent = {
                if (music != null) {
                    Column {
                        Text(music.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!TidalLauncher.isInstalled(context)) {
                            Text(stringResource(R.string.editor_tidal_missing), color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Text(stringResource(R.string.editor_music_none))
                }
            },
            leadingContent = {
                if (music?.imageUrl != null) {
                    AsyncImage(
                        model = music.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp).padding(10.dp))
                }
            },
            trailingContent = {
                if (music != null) {
                    IconButton(onClick = vm::clearMusic) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            modifier = Modifier.clickable(onClick = onPickMusic),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (music != null && music.type != TidalItem.Type.TRACK) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.editor_shuffle)) },
                trailingContent = { Switch(checked = alarm.shuffle, onCheckedChange = vm::setShuffle) },
                modifier = Modifier.clickable { vm.setShuffle(!alarm.shuffle) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
        if (alarm.needsWakeupPlaylist) {
            val loggedIn by vm.tidalLoggedIn.collectAsStateWithLifecycle()
            val canWrite by vm.tidalWriteAccess.collectAsStateWithLifecycle()
            // Just logged in again: prepare right away.
            LaunchedEffect(canWrite) {
                if (canWrite && alarm.wakeupPreparedAt == null) vm.retryWakeup()
            }
            WakeupStatus(alarm, loggedIn, canWrite, onLogin = { vm.login(context) }, onRetry = vm::retryWakeup)
        }
        var canOverlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            canOverlay = android.provider.Settings.canDrawOverlays(context)
        }
        if (music != null && !canOverlay) {
            Text(
                stringResource(R.string.editor_overlay_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { Reliability.openOverlaySettings(context) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        ListItem(
            headlineContent = {
                Text(stringResource(if (music != null) R.string.editor_backup_sound else R.string.editor_alarm_sound))
            },
            supportingContent = { Text(alarm.ringtoneTitle ?: stringResource(R.string.sound_lavender_chime)) },
            modifier = Modifier.clickable { showSound = true },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        )

        var volume by remember(alarm.id) { mutableFloatStateOf(alarm.volumePercent.toFloat()) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.editor_volume, volume.toInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { vm.setVolume(volume.toInt()) },
                valueRange = 10f..100f,
                steps = 8,
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_ramp)) },
            supportingContent = { Text(rampLabel(alarm.rampSeconds)) },
            modifier = Modifier.clickable { showRamp = true },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_vibrate)) },
            trailingContent = { Switch(checked = alarm.vibrate, onCheckedChange = vm::setVibrate) },
            modifier = Modifier.clickable { vm.setVibrate(!alarm.vibrate) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_snooze)) },
            supportingContent = { Text(stringResource(R.string.minutes, alarm.snoozeMinutes)) },
            modifier = Modifier.clickable { showSnooze = true },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.editor_test_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (showTime) {
        TimePickerDialog(
            initialHour = alarm.hour,
            initialMinute = alarm.minute,
            onConfirm = { h, m ->
                showTime = false
                vm.setTime(h, m)
            },
            onDismiss = { showTime = false },
        )
    }
    if (showDate) {
        DateDialog(initial = alarm.date, onConfirm = vm::setDate, onDismiss = { showDate = false })
    }
    if (showRamp) {
        ChoiceDialog(
            title = stringResource(R.string.editor_ramp),
            options = listOf(0, 15, 30, 60, 120, 300),
            selected = alarm.rampSeconds,
            label = { rampLabel(it) },
            onSelect = vm::setRamp,
            onDismiss = { showRamp = false },
        )
    }
    if (showSnooze) {
        ChoiceDialog(
            title = stringResource(R.string.editor_snooze),
            options = listOf(5, 10, 15, 20, 30),
            selected = alarm.snoozeMinutes,
            label = { stringResource(R.string.minutes, it) },
            onSelect = vm::setSnooze,
            onDismiss = { showSnooze = false },
        )
    }
    if (showSound) {
        AlertDialog(
            onDismissRequest = { showSound = false },
            title = { Text(stringResource(if (music != null) R.string.editor_backup_sound else R.string.editor_alarm_sound)) },
            text = { Text(stringResource(R.string.editor_sound_explainer)) },
            confirmButton = {
                TextButton(onClick = {
                    showSound = false
                    ringtonePicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, alarm.ringtoneUri?.let(Uri::parse))
                    )
                }) { Text(stringResource(R.string.editor_sound_phone)) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showSound = false
                    vm.setRingtone(null, null)
                }) { Text(stringResource(R.string.sound_lavender_chime)) }
            },
        )
    }
}

/** Whether the wake-up playlist for shuffle / My songs is ready, and what to do if not. */
@Composable
private fun WakeupStatus(alarm: Alarm, loggedIn: Boolean, canWrite: Boolean, onLogin: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val preparedAt = alarm.wakeupPreparedAt
    val (text, problem) = when {
        !loggedIn -> stringResource(R.string.wakeup_login_needed) to true
        !canWrite -> stringResource(R.string.wakeup_relogin_needed) to true
        preparedAt == null -> stringResource(R.string.wakeup_preparing) to false
        else -> stringResource(R.string.wakeup_ready, alarm.wakeupTrackCount, Format.dayAndTime(context, preparedAt)) to false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !loggedIn || !canWrite -> TextButton(onClick = onLogin) { Text(stringResource(R.string.action_login_tidal)) }
            preparedAt == null -> TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun rampLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.ramp_off)
    seconds < 60 -> stringResource(R.string.seconds, seconds)
    else -> stringResource(R.string.minutes, seconds / 60)
}
