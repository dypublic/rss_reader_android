package com.codex.rssreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.BuildConfig
import com.codex.rssreader.update.UpdateUiState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: ReaderRepository,
    updateState: UpdateUiState,
    onCheckForUpdate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFlow = remember(repository) { repository.preferences.fontSize }
    val fontSize by fontFlow.collectAsState(initial = 18)
    var candidate by remember(fontSize) { mutableIntStateOf(fontSize) }
    val scope = rememberCoroutineScope()
    Column(modifier.background(ReaderBackground)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        Surface(Modifier.fillMaxWidth().padding(20.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("阅读字号", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("${candidate}sp", color = ReaderMuted)
                Slider(candidate.toFloat(), onValueChange = { candidate = it.toInt() }, onValueChangeFinished = {
                    scope.launch { repository.preferences.setFontSize(candidate) }
                }, valueRange = 14f..28f, steps = 13)
                Text("预览文字：阅读从这里继续。", fontSize = candidate.sp)
            }
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("应用更新", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）${if (BuildConfig.DEBUG) " · 调试版" else ""}",
                    color = ReaderMuted,
                )
                Spacer(Modifier.height(8.dp))
                Text(updateStatusText(updateState), color = ReaderMuted, fontSize = 13.sp)
                TextButton(
                    onClick = onCheckForUpdate,
                    enabled = updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
                ) { Text(if (updateState is UpdateUiState.Checking) "正在检查…" else "检查更新") }
            }
        }
    }
}

private fun updateStatusText(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Checking -> "正在连接 GitHub Releases"
    is UpdateUiState.Available -> "发现 ${state.release.versionName}"
    is UpdateUiState.Downloading -> "正在下载 ${state.release.versionName}"
    is UpdateUiState.ReadyToInstall -> "${state.release.versionName} 已下载并通过校验"
    is UpdateUiState.UpToDate -> "当前已是最新版本"
    is UpdateUiState.Failed -> state.message
    UpdateUiState.Idle -> "通过 GitHub Releases 获取正式更新"
}
