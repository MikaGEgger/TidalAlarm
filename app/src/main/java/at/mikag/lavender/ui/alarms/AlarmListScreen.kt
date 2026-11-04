package at.mikag.lavender.ui.alarms

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mikag.lavender.R
import at.mikag.lavender.container
import at.mikag.lavender.data.Alarm
import at.mikag.lavender.data.AppSettings
import at.mikag.lavender.ui.Format
import at.mikag.lavender.ui.Reliability
import at.mikag.lavender.ui.components.FlagStripe
import at.mikag.lavender.ui.components.TimePickerDialog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlarmListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.container.alarms
    private val settingsRepo = app.container.settings

    val alarms: StateFlow<List<Alarm>?> = repo.alarms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    sealed interface Message {
        data class Scheduled(val at: Long) : Message
        data class Deleted(val alarm: Alarm) : Message
    }

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val messages: SharedFlow<Message> = _messages

    fun create(hour: Int, minute: Int, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val defaults = settingsRepo.current()
        val alarm = repo.create(Alarm(hour = hour, minute = minute, snoozeMinutes = defaults.defaultSnoozeMinutes))
        alarm.scheduledFor?.let { _messages.emit(Message.Scheduled(it)) }
        onCreated(alarm.id)
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        val saved = repo.edit(alarm.id) {
            it.copy(enabled = enabled, snoozedUntil = null, skippedOccurrence = null, dateEpochDay = if (enabled) it.dateEpochDay?.takeIf { day -> day >= java.time.LocalDate.now().toEpochDay() } else it.dateEpochDay)
        }
        if (enabled) saved?.scheduledFor?.let { _messages.emit(Message.Scheduled(it)) }
    }

    fun skipNext(alarm: Alarm) = viewModelScope.launch { repo.skipNext(alarm.id) }

    fun undoSkip(alarm: Alarm) = viewModelScope.launch { repo.undoSkip(alarm.id) }

    fun delete(alarm: Alarm) = viewModelScope.launch {
        repo.delete(alarm)
        _messages.emit(Message.Deleted(alarm))
    }

    fun restore(alarm: Alarm) = viewModelScope.launch {
        repo.create(alarm.copy(id = 0, scheduledFor = null, snoozedUntil = null))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(onOpenAlarm: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm: AlarmListViewModel = viewModel()
    val context = LocalContext.current
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showTimePicker by remember { mutableStateOf(false) }
    var reliability by remember { mutableStateOf(Reliability.check(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reliability = Reliability.check(context) }

    val undoLabel = stringResource(R.string.action_undo)
    val deletedText = stringResource(R.string.message_alarm_deleted)
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            when (message) {
                is AlarmListViewModel.Message.Scheduled -> snackbar.showSnackbar(
                    context.getString(R.string.message_alarm_in, Format.duration(context, message.at - System.currentTimeMillis()))
                )
                is AlarmListViewModel.Message.Deleted -> {
                    val result = snackbar.showSnackbar(deletedText, actionLabel = undoLabel, withDismissAction = true)
                    if (result == SnackbarResult.ActionPerformed) vm.restore(message.alarm)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                FlagStripe()
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTimePicker = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_alarm))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = alarms
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!reliability.critical) {
                item { ReliabilityBanner(onClick = onOpenSettings) }
            }
            if (list != null && list.isEmpty()) {
                item { EmptyState() }
            }
            items(list.orEmpty(), key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    settings = settings,
                    onClick = { onOpenAlarm(alarm.id) },
                    onToggle = { vm.setEnabled(alarm, it) },
                    onSkip = { vm.skipNext(alarm) },
                    onUndoSkip = { vm.undoSkip(alarm) },
                    onDelete = { vm.delete(alarm) },
                )
            }
        }
    }

    if (showTimePicker) {
        val now = java.time.LocalTime.now()
        TimePickerDialog(
            initialHour = (now.hour + 1) % 24,
            initialMinute = 0,
            onConfirm = { hour, minute ->
                showTimePicker = false
                vm.create(hour, minute, onOpenAlarm)
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReliabilityBanner(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.reliability_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(R.string.reliability_banner_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmCard(
    alarm: Alarm,
    settings: AppSettings,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onUndoSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val active = alarm.enabled
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).alpha(if (active) 1f else 0.55f)) {
                Text(
                    Format.time(context, alarm.hour, alarm.minute),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                )
                val summary = buildList {
                    if (alarm.label.isNotBlank()) add(alarm.label)
                    add(Format.repeatSummary(context, alarm, settings.weekStart, alarm.scheduledFor))
                }.joinToString(" · ")
                Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    alarm.enabled && alarm.snoozedUntil != null ->
                        stringResource(R.string.status_snoozed_until, Format.time(context, alarm.snoozedUntil))
                    alarm.enabled && alarm.skippedOccurrence != null && alarm.skippedOccurrence > System.currentTimeMillis() ->
                        stringResource(R.string.status_skipping, Format.dayAndTime(context, alarm.skippedOccurrence))
                    else -> null
                }
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Snooze, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                val music = alarm.tidalItem
                if (music != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Filled.MusicNote, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            music.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Switch(checked = active, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val skipped = alarm.skippedOccurrence != null && alarm.skippedOccurrence > System.currentTimeMillis()
                    if (alarm.enabled && alarm.isRepeating) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (skipped) R.string.action_undo_skip else R.string.action_skip_next)) },
                            onClick = {
                                menu = false
                                if (skipped) onUndoSkip() else onSkip()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
