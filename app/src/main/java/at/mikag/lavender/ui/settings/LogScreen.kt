package at.mikag.lavender.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.mikag.lavender.AppLog
import at.mikag.lavender.LogUpload
import at.mikag.lavender.R

/** Developer log: what the alarm did and why. Copy it and paste it into a chat or an issue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(AppLog.read()) }
    val fullText = { AppLog.header(context) + "\n\n" + AppLog.read() }
    val vertical = rememberScrollState()
    LaunchedEffect(text) { vertical.scrollTo(vertical.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (LogUpload.isConfigured) {
                        IconButton(onClick = {
                            LogUpload.schedule(context, delaySeconds = 0)
                            Toast.makeText(context, R.string.log_sending, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = stringResource(R.string.action_send_log))
                        }
                    }
                    IconButton(onClick = { text = AppLog.read() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Lavender log", fullText()))
                        Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fullText())
                        context.startActivity(Intent.createChooser(send, null))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = {
                        AppLog.clear()
                        text = ""
                    }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear_log))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(vertical)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    // Showing everything would make the screen slow; Copy/Share always include the whole log.
                    text.ifEmpty { stringResource(R.string.log_empty) }.takeLast(60_000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
