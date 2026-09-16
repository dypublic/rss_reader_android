package com.codex.rssreader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.codex.rssreader.AppViewModel
import com.codex.rssreader.data.ParsedFeed
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.data.SubscriptionEntity
import com.codex.rssreader.data.SubscriptionRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubscriptionScreen(
    repository: ReaderRepository,
    viewModel: AppViewModel,
    onOpenSource: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceFlow = remember(repository) { repository.subscriptions() }
    val sources by sourceFlow.collectAsState(initial = emptyList())
    var addOpen by remember { mutableStateOf(false) }
    var actionSource by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var renameSource by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var deleteSource by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier.background(ReaderBackground)) {
        Row(
            Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("订阅", fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshAll() }) {
                if (viewModel.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "刷新订阅")
            }
            IconButton(onClick = { addOpen = true }) { Icon(Icons.Default.Add, contentDescription = "添加订阅") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            item {
                Surface(shape = RoundedCornerShape(26.dp), color = ReaderOrange, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 27.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("订阅源", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${sources.size}", color = Color.White, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("订阅源", color = ReaderMuted, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(14.dp))
            }
            if (sources.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("还没有订阅源", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text("点击右上角 ＋，粘贴 RSS 或 Atom 地址。", color = ReaderMuted)
                        }
                    }
                }
            } else {
                item { Spacer(Modifier.height(1.dp)) }
                items(sources, key = { it.subscription.id }) { row ->
                    Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = { onOpenSource(row.subscription.id) },
                                onLongClick = { actionSource = row.subscription },
                            ).padding(horizontal = 18.dp, vertical = 17.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SourceIcon(row.subscription)
                            Spacer(Modifier.size(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.subscription.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.subscription.error != null) Text("刷新失败", color = ReaderOrange, fontSize = 12.sp)
                            }
                            Text("${row.unreadCount}", color = ReaderMuted, fontSize = 16.sp)
                        }
                    }
                    HorizontalDivider(color = Color(0xFFEAEAF0), thickness = 0.5.dp)
                }
            }
        }
    }

    if (addOpen) AddSubscriptionDialog(repository, onClose = { addOpen = false })
    actionSource?.let { source ->
        AlertDialog(
            onDismissRequest = { actionSource = null },
            title = { Text(source.title) },
            text = { Text("选择订阅源操作") },
            confirmButton = {
                TextButton(onClick = { renameSource = source; actionSource = null }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSource = source; actionSource = null }) { Text("删除") }
            },
        )
    }
    renameSource?.let { source ->
        var title by remember(source.id) { mutableStateOf(source.title) }
        AlertDialog(onDismissRequest = { renameSource = null }, title = { Text("重命名订阅源") },
            text = { OutlinedTextField(title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { repository.renameSubscription(source.id, title); renameSource = null } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameSource = null }) { Text("取消") } })
    }
    deleteSource?.let { source ->
        AlertDialog(onDismissRequest = { deleteSource = null }, title = { Text("删除 ${source.title}？") },
            text = { Text("该订阅源的已读和未读文章都会从本机删除。") },
            confirmButton = { TextButton(onClick = { scope.launch { repository.deleteSubscription(source.id); deleteSource = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteSource = null }) { Text("取消") } })
    }
}

@Composable
fun SourceIcon(source: SubscriptionEntity) {
    Box(Modifier.size(32.dp).background(ReaderOrange, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Text(source.title.take(1).ifBlank { "R" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        source.iconUrl?.let { url ->
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}

@Composable
private fun AddSubscriptionDialog(repository: ReaderRepository, onClose: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ParsedFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (preview == null) "添加订阅" else "确认订阅源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(address, onValueChange = { address = it; preview = null; error = null },
                    label = { Text("RSS / Atom 地址") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                preview?.let { Text("${it.title} · ${it.items.size} 篇文章", color = ReaderText) }
                error?.let { Text(it, color = ReaderOrange) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && address.isNotBlank(), onClick = {
                val submittedAddress = address
                val submittedPreview = preview
                scope.launch {
                    busy = true; error = null
                    try {
                        if (submittedPreview == null) {
                            repository.previewSubscription(submittedAddress).onSuccess { preview = it }.onFailure { error = it.message }
                        } else {
                            repository.addSubscription(submittedAddress, submittedPreview).onSuccess { onClose() }.onFailure { error = it.message }
                        }
                    } finally { busy = false }
                }
            }) { Text(if (preview == null) "验证" else "确认添加") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
