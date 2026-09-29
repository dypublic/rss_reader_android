package com.codex.rssreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.codex.rssreader.AppViewModel
import com.codex.rssreader.data.ArticleEntity
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.rules.ScrollReadTracker
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleListScreen(
    sourceId: Long,
    repository: ReaderRepository,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val articleFlow = remember(repository, sourceId) { repository.articles(sourceId) }
    val articlesOrNull by articleFlow.collectAsState(initial = null)
    val allArticles = articlesOrNull.orEmpty()
    val sourceFlow = remember(repository) { repository.subscriptions() }
    val sources by sourceFlow.collectAsState(initial = emptyList())
    val titleSizeFlow = remember(repository) { repository.preferences.listTitleSize }
    val titleSize by titleSizeFlow.collectAsState(initial = 12f)
    val summarySizeFlow = remember(repository) { repository.preferences.listSummarySize }
    val summarySize by summarySizeFlow.collectAsState(initial = 10.5f)
    val source = sources.firstOrNull { it.subscription.id == sourceId }?.subscription
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirmKeys by remember { mutableStateOf<List<String>?>(null) }
    var refreshAnchor by remember { mutableStateOf<RefreshAnchor?>(null) }

    LaunchedEffect(sourceId, articlesOrNull, viewModel.unreadOnly, viewModel.listKeys == null) {
        articlesOrNull?.let(viewModel::initializeList)
    }
    val keys = viewModel.listKeys.orEmpty()
    val byKey = allArticles.associateBy { it.articleKey }
    val shown = keys.mapNotNull(byKey::get)
    val renderedKeys = shown.map { it.articleKey }

    fun requestRefresh() {
        if (viewModel.refreshing) return
        val top = listState.firstVisibleItemIndex
        refreshAnchor = RefreshAnchor(viewModel.sourceRefreshVersion, keys.drop(top), listState.firstVisibleItemScrollOffset)
        viewModel.refreshSource(sourceId)
    }

    LaunchedEffect(viewModel.sourceRefreshVersion, viewModel.listKeys, renderedKeys, refreshAnchor) {
        val anchor = refreshAnchor ?: return@LaunchedEffect
        if (viewModel.sourceRefreshVersion <= anchor.version) return@LaunchedEffect
        val newKeys = viewModel.listKeys.orEmpty()
        if (renderedKeys.size != newKeys.size) return@LaunchedEffect
        withFrameNanos { }
        val survivingKey = anchor.candidates.firstOrNull { it in renderedKeys }
        if (survivingKey != null) listState.requestScrollToItem(renderedKeys.indexOf(survivingKey), anchor.offset)
        else if (renderedKeys.isNotEmpty()) listState.requestScrollToItem(0)
        refreshAnchor = null
    }

    val scrollReadTracker = remember(sourceId, viewModel.unreadOnly) { ScrollReadTracker() }
    LaunchedEffect(listState, scrollReadTracker, renderedKeys) {
        scrollReadTracker.updateKeys(renderedKeys)
        snapshotFlow { listState.firstVisibleItemIndex }.collect { firstVisibleIndex ->
            val exited = scrollReadTracker.onPosition(firstVisibleIndex)
            if (exited.isNotEmpty()) viewModel.markRead(exited)
        }
    }

    Column(modifier.background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text(source?.title ?: "文章", fontSize = 21.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            UnreadSwitch(checked = viewModel.unreadOnly, onCheckedChange = { unreadOnly ->
                viewModel.selectUnreadFilter(unreadOnly)
                scope.launch { listState.scrollToItem(0) }
            })
            IconButton(onClick = ::requestRefresh) {
                if (viewModel.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { scope.launch { confirmKeys = repository.unreadKeys(sourceId) } }) {
                Icon(Icons.Default.CheckCircle, contentDescription = "全部标为已读")
            }
        }
        source?.error?.let {
            Text("刷新失败：$it", color = ReaderOrange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        HorizontalDivider(color = Color(0xFFEAEAF0))
        PullToRefreshBox(
            isRefreshing = viewModel.refreshing,
            onRefresh = ::requestRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (articlesOrNull == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (viewModel.unreadOnly) "已读完，切换“全部”查看历史文章" else "暂无文章", color = ReaderMuted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(shown, key = { _, article -> article.articleKey }) { _, article ->
                        ArticleRow(article, titleSize, summarySize,
                            onClick = { onOpen(article.articleKey) })
                        HorizontalDivider(color = Color(0xFFEAEAF0), thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    confirmKeys?.let { snapshot ->
        AlertDialog(onDismissRequest = { confirmKeys = null }, title = { Text("全部标为已读？") },
            text = { Text("将“${source?.title ?: "当前订阅源"}”的 ${snapshot.size} 篇未读文章标为已读。") },
            confirmButton = { TextButton(onClick = { viewModel.markRead(snapshot); confirmKeys = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirmKeys = null }) { Text("取消") } })
    }
}

private data class RefreshAnchor(val version: Int, val candidates: List<String>, val offset: Int)

@Composable
private fun UnreadSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (checked) "未读" else "全部", color = ReaderMuted, fontSize = 12.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.72f),
        )
    }
}

@Composable
private fun ArticleRow(
    article: ArticleEntity,
    titleSize: Float,
    summarySize: Float,
    onClick: () -> Unit,
) {
    val read = article.readAt != null
    val titleColor = if (read) ReaderMuted else Color(0xFF30343A)
    Surface(onClick = onClick, color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 5.dp, top = 5.dp, end = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!read) {
                Box(Modifier.size(5.dp).background(Color(0xFFF5473A), RoundedCornerShape(50)))
                Spacer(Modifier.width(5.dp))
            } else Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (article.imageUrl == null) {
                    Text(articleTime(article), color = ReaderMuted, fontSize = 10.sp, lineHeight = 11.sp,
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ArticleText(article, titleColor, titleSize, summarySize)
            }
            article.imageUrl?.let { url ->
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(76.dp)) {
                    Text(articleTime(article), color = ReaderMuted, fontSize = 10.sp, lineHeight = 11.sp,
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AsyncImage(model = url, contentDescription = null,
                        modifier = Modifier.size(width = 76.dp, height = 72.dp).background(Color(0xFFF2F2F5), RoundedCornerShape(7.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun ArticleText(article: ArticleEntity, titleColor: Color, titleSize: Float, summarySize: Float) {
    Text(article.title, color = titleColor, fontSize = titleSize.sp, lineHeight = (titleSize + 3f).sp,
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (article.summary.isNotBlank()) {
        Spacer(Modifier.height(1.dp))
        Text(article.summary, color = ReaderMuted, fontSize = summarySize.sp,
            lineHeight = (summarySize + 2.5f).sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("M月d日")
private fun articleTime(article: ArticleEntity): String {
    val date = Instant.ofEpochMilli(article.sortAt).atZone(ZoneId.systemDefault()).format(dateFormat)
    return if (article.publishedAt == null) "获取于$date" else date
}
