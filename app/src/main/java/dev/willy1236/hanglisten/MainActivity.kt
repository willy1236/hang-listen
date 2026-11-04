package dev.willy1236.hanglisten

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.InputChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<PlaybackService?>(null)
    private val history by lazy { ChannelHistory(this) }
    private var ignoringBatteryOptimization by mutableStateOf(true)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlaybackService.LocalBinder).service().also {
                it.restoreLastSession()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppTheme {
                MainScreen(
                    service = service,
                    history = history,
                    ignoringBatteryOptimization = ignoringBatteryOptimization,
                    onRequestBatteryExemption = ::requestBatteryExemption,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        ignoringBatteryOptimization =
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    override fun onStop() {
        unbindService(connection)
        service = null
        super.onStop()
    }

    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")),
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun MainScreen(
    service: PlaybackService?,
    history: ChannelHistory,
    ignoringBatteryOptimization: Boolean,
    onRequestBatteryExemption: () -> Unit,
) {
    val emptyStreams = remember { MutableStateFlow(emptyList<PlaybackService.StreamState>()) }
    val notPaused = remember { MutableStateFlow(false) }
    val streams by (service?.streams ?: emptyStreams).collectAsState()
    val paused by (service?.pausedByUser ?: notPaused).collectAsState()
    val recent by history.channels.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("掛聽", style = MaterialTheme.typography.headlineSmall)

            if (!ignoringBatteryOptimization) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "建議關閉電池最佳化，避免背景播放被系統砍掉",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onRequestBatteryExemption) { Text("前往設定") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("Twitch／YouTube 頻道或網址") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val result = service?.addChannel(input)
                        if (result == null) {
                            StreamSource.parse(input)?.key?.let(history::record)
                            input = ""
                        } else {
                            error = result
                        }
                    },
                    enabled = service != null && input.isNotBlank(),
                ) { Text("加入") }
            }

            val currentError = error
            if (currentError != null) {
                Text(
                    currentError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val playing = streams.map { it.channel }.toSet()
            val notPlaying = recent.filter { it !in playing }
            if (notPlaying.isNotEmpty()) {
                RecentChannels(
                    channels = notPlaying,
                    enabled = service != null,
                    onAdd = { channel ->
                        val result = service?.addChannel(channel)
                        if (result == null) history.record(channel) else error = result
                    },
                    onAddAll = {
                        notPlaying.forEach { service?.addChannel(it) }
                    },
                    onForget = history::forget,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (paused) service?.resumeAll() else service?.pauseAll() },
                    enabled = streams.isNotEmpty(),
                ) { Text(if (paused) "全部繼續" else "全部暫停") }
                OutlinedButton(
                    onClick = { service?.stopAll() },
                    enabled = streams.isNotEmpty(),
                ) { Text("全部停止") }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(streams, key = { it.id }) { stream ->
                    StreamCard(
                        stream = stream,
                        onVolumeChange = { service?.setVolume(stream.id, it) },
                        onRemove = { service?.remove(stream.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentChannels(
    channels: List<String>,
    enabled: Boolean,
    onAdd: (String) -> Unit,
    onAddAll: () -> Unit,
    onForget: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近加過",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (channels.size > 1) {
                TextButton(onClick = onAddAll, enabled = enabled) { Text("全部加回") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            channels.forEach { channel ->
                val label = StreamSource.parse(channel)?.label ?: channel
                InputChip(
                    selected = false,
                    onClick = { onAdd(channel) },
                    enabled = enabled,
                    label = { Text(label) },
                    trailingIcon = {
                        IconButton(onClick = { onForget(channel) }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "從紀錄刪除 $label")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StreamCard(
    stream: PlaybackService.StreamState,
    onVolumeChange: (Float) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stream.label, style = MaterialTheme.typography.titleMedium)
                    val detail = stream.message?.let { "（$it）" }.orEmpty()
                    Text(
                        stream.status.label + detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRemove) { Text("移除") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = stream.volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}
