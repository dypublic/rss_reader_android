package com.codex.rssreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repository: ReaderRepository, onBack: () -> Unit, modifier: Modifier = Modifier) {
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
    }
}
