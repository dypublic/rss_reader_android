package com.codex.rssreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.rssreader.diagnostics.LogLevel
import com.codex.rssreader.diagnostics.RuntimeLogStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RuntimeLogScreen(logs: RuntimeLogStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val entries by logs.entries.collectAsState(initial = null)
    Column(modifier.background(ReaderBackground)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("运行日志", fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            "最新记录在前 · 最多约 384 KB，旧记录自动覆盖\n后台每 15 分钟调度，系统省电可能延迟执行。",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontSize = 12.sp, color = ReaderMuted,
        )
        when {
            entries == null -> Text("正在读取日志…", Modifier.padding(20.dp), color = ReaderMuted)
            entries!!.isEmpty() -> Text("暂无运行日志", Modifier.padding(20.dp), color = ReaderMuted)
            else -> LazyColumn(
                Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries!!) { entry ->
                    Surface(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val levelText = when (entry.level) {
                                    LogLevel.INFO -> "信息"
                                    LogLevel.WARNING -> "注意"
                                    LogLevel.ERROR -> "错误"
                                }
                                val levelColor = when (entry.level) {
                                    LogLevel.INFO -> ReaderMuted
                                    LogLevel.WARNING -> Color(0xFF946000)
                                    LogLevel.ERROR -> Color(0xFFB3261E)
                                }
                                Text("${LogTime.format(Instant.ofEpochMilli(entry.timestamp))} · $levelText", fontSize = 11.sp, color = levelColor)
                                Text(entry.event, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (entry.details.isNotEmpty()) Text(entry.details, fontSize = 12.sp, color = ReaderMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val LogTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
