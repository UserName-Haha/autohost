package io.github.usernamehaha.autohost.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.usernamehaha.autohost.AutoHostState
import io.github.usernamehaha.autohost.HostStatus
import io.github.usernamehaha.autohost.ProbeResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { MainScreen() } }
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val hostState by viewModel.hostState.collectAsStateWithLifecycle()
    val ticker by viewModel.ticker.collectAsStateWithLifecycle()
    val brokenHosts by viewModel.brokenHosts.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { TickerCard(ticker) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::probe, enabled = !hostState.isProbing) {
                        Text(if (hostState.isProbing) "测速中" else "重新测速")
                    }
                    Text("当前线路：${hostState.current}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(hostState.hosts, key = { it.host.toString() }) { status ->
                HostRow(
                    status = status,
                    state = hostState,
                    broken = status.host.name in brokenHosts,
                    onTogglePin = { viewModel.togglePin(status.host) },
                    onToggleBroken = { viewModel.toggleBroken(status.host) },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("事件", style = MaterialTheme.typography.titleSmall)
            }
            items(log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun TickerCard(ticker: Ticker) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("BTC/USDT", style = MaterialTheme.typography.labelLarge)
            Text(ticker.price ?: "--", style = MaterialTheme.typography.headlineMedium)
            val detail = when {
                ticker.error != null -> "请求失败：${ticker.error}"
                ticker.servedBy != null -> "由 ${ticker.servedBy} 返回"
                else -> "请求中"
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ticker.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostRow(
    status: HostStatus,
    state: AutoHostState,
    broken: Boolean,
    onTogglePin: () -> Unit,
    onToggleBroken: () -> Unit,
) {
    val isCurrent = status.host == state.current
    val isPinned = status.host == state.pinned
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = status.host.toString() + if (isCurrent) "  · 使用中" else "",
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
            val probe = when (val result = status.lastProbe) {
                null -> "未探测"
                is ProbeResult.Success -> "${result.latency.inWholeMilliseconds} ms"
                is ProbeResult.Failure -> "探测失败：${result.cause.message}"
            }
            Text("$probe    连续失败 ${status.consecutiveFailures} 次", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTogglePin) { Text(if (isPinned) "取消固定" else "固定") }
                OutlinedButton(onClick = onToggleBroken) { Text(if (broken) "恢复线路" else "模拟故障") }
            }
        }
    }
}
