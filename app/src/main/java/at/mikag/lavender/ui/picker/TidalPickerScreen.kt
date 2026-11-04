package at.mikag.lavender.ui.picker

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import at.mikag.lavender.R
import at.mikag.lavender.container
import at.mikag.lavender.data.TidalItem
import at.mikag.lavender.tidal.TidalApi
import at.mikag.lavender.tidal.TidalLauncher
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

class TidalPickerViewModel(app: Application, private val alarmId: Long) : AndroidViewModel(app) {
    private val auth = app.container.tidalAuth
    private val api = app.container.tidalApi
    private val repo = app.container.alarms

    val loggedIn: StateFlow<Boolean> = auth.loggedIn
    val configured: Boolean get() = auth.isConfigured

    enum class Tab { PLAYLISTS, TRACKS }

    data class ListState(
        val items: List<TidalItem> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: Int? = null,
    )

    private val lists = Tab.entries.associateWith { MutableStateFlow(ListState()) }
    fun list(tab: Tab): StateFlow<ListState> = lists.getValue(tab).asStateFlow()

    private val _linkError = MutableStateFlow<Int?>(null)
    val linkError: StateFlow<Int?> = _linkError.asStateFlow()

    fun loadIfNeeded(tab: Tab) {
        val state = lists.getValue(tab).value
        if (!state.loaded && !state.loading) load(tab, reset = true)
    }

    fun load(tab: Tab, reset: Boolean) {
        val flow = lists.getValue(tab)
        val before = flow.value
        if (before.loading) return
        flow.value = before.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val cursor = if (reset) null else before.nextCursor
                val page = if (tab == Tab.PLAYLISTS) api.playlists(cursor) else api.tracks(cursor)
                val items = if (reset) page.items else before.items + page.items
                flow.value = ListState(items.distinctBy { it.id }, page.nextCursor, loading = false, loaded = true)
            } catch (e: Exception) {
                flow.value = before.copy(loading = false, loaded = true, error = errorText(e))
            }
        }
    }

    fun choose(item: TidalItem, onDone: () -> Unit) = viewModelScope.launch {
        repo.edit(alarmId) { it.withTidal(item) }
        onDone()
    }

    /** A pasted (or shared) TIDAL link. With a login, its name is looked up; without, a generic name is used. */
    fun useLink(text: String, onDone: () -> Unit) = viewModelScope.launch {
        val parsed = TidalLauncher.parseLink(text)
        if (parsed == null) {
            _linkError.value = R.string.picker_link_invalid
            return@launch
        }
        _linkError.value = null
        val (type, id) = parsed
        val looked = if (loggedIn.value) {
            try {
                api.lookup(type, id)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val fallbackTitle = getApplication<Application>().getString(
            if (type == TidalItem.Type.PLAYLIST) R.string.picker_generic_playlist else R.string.picker_generic_track
        )
        choose(looked ?: TidalItem(type, id, fallbackTitle), onDone)
    }

    fun login(context: Context) {
        val uri = auth.loginUri()
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (ignored: ActivityNotFoundException) {
            }
        }
    }

    private fun errorText(e: Exception): Int = when {
        e is TidalApi.ApiException && e.code == 401 -> R.string.picker_error_login
        e is TidalApi.ApiException && e.code == 403 -> R.string.picker_error_forbidden
        e is IOException -> R.string.picker_error_network
        else -> R.string.picker_error_generic
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidalPickerScreen(alarmId: Long, onDone: () -> Unit) {
    val vm: TidalPickerViewModel = viewModel(
        key = "picker-$alarmId",
        factory = viewModelFactory { initializer { TidalPickerViewModel(this[APPLICATION_KEY]!!, alarmId) } },
    )
    val context = LocalContext.current
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TidalPickerViewModel.Tab.PLAYLISTS) }
    var filter by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(loggedIn, tab) { if (loggedIn) vm.loadIfNeeded(tab) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!TidalLauncher.isInstalled(context)) {
                InfoCard(stringResource(R.string.picker_install_tidal), error = true)
            }
            PasteLink(vm, onDone)
            when {
                !vm.configured -> InfoCard(stringResource(R.string.picker_not_configured), error = true)
                !loggedIn -> LoginCard(onLogin = { vm.login(context) })
                else -> {
                    TabRow(selectedTabIndex = tab.ordinal) {
                        Tab(
                            selected = tab == TidalPickerViewModel.Tab.PLAYLISTS,
                            onClick = { tab = TidalPickerViewModel.Tab.PLAYLISTS },
                            text = { Text(stringResource(R.string.picker_tab_playlists)) },
                        )
                        Tab(
                            selected = tab == TidalPickerViewModel.Tab.TRACKS,
                            onClick = { tab = TidalPickerViewModel.Tab.TRACKS },
                            text = { Text(stringResource(R.string.picker_tab_tracks)) },
                        )
                    }
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text(stringResource(R.string.picker_filter)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LibraryList(vm, tab, filter, onDone)
                }
            }
        }
    }
}

@Composable
private fun LibraryList(vm: TidalPickerViewModel, tab: TidalPickerViewModel.Tab, filter: String, onDone: () -> Unit) {
    val state by vm.list(tab).collectAsStateWithLifecycle()
    val shown = remember(state.items, filter) {
        if (filter.isBlank()) state.items
        else state.items.filter { it.title.contains(filter, ignoreCase = true) || it.subtitle?.contains(filter, ignoreCase = true) == true }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(shown, key = { "${it.type}:${it.id}" }) { item ->
            ListItem(
                headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val text = when (item.type) {
                        TidalItem.Type.PLAYLIST -> item.subtitle?.toIntOrNull()?.let { stringResource(R.string.picker_tracks_count, it) }
                        TidalItem.Type.TRACK -> item.subtitle
                    }
                    if (text != null) Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = {
                    if (item.imageUrl != null) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Icon(
                            if (item.type == TidalItem.Type.PLAYLIST) Icons.AutoMirrored.Filled.QueueMusic else Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).padding(12.dp),
                        )
                    }
                },
                modifier = Modifier.clickable { vm.choose(item, onDone) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(state.error!!), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.load(tab, reset = state.items.isEmpty()) }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    state.nextCursor != null -> OutlinedButton(onClick = { vm.load(tab, reset = false) }) {
                        Text(stringResource(R.string.picker_load_more))
                    }
                    state.loaded && state.items.isEmpty() -> Text(
                        stringResource(if (tab == TidalPickerViewModel.Tab.PLAYLISTS) R.string.picker_empty_playlists else R.string.picker_empty_tracks),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteLink(vm: TidalPickerViewModel, onDone: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    val errorState by vm.linkError.collectAsStateWithLifecycle()
    val error = errorState
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            placeholder = { Text(stringResource(R.string.picker_paste_link)) },
            singleLine = true,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(stringResource(error)) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { vm.useLink(link, onDone) }, enabled = link.isNotBlank()) {
            Text(stringResource(R.string.action_use))
        }
    }
}

@Composable
private fun LoginCard(onLogin: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.picker_login_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.picker_login_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onLogin) { Text(stringResource(R.string.action_login_tidal)) }
        }
    }
}

@Composable
private fun InfoCard(text: String, error: Boolean) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}
