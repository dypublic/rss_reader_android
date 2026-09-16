package com.codex.rssreader.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.codex.rssreader.AppViewModel
import com.codex.rssreader.data.ArticleEntity
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.rules.ArticleRules
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
    val source = sources.firstOrNull { it.subscription.id == sourceId }?.subscription
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirmKeys by remember { mutableStateOf<List<String>?>(null) }
    var scrollArmed by remember { mutableStateOf(false) }
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

    val scrollObserver = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f) scrollArmed = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState, viewModel.listKeys) {
        var previous: Int? = null
        snapshotFlow { listState.firstVisibleItemIndex }.collect { current ->
            val before = previous
            if (before != null && keys.isNotEmpty()) {
                val crossed = ArticleRules.crossedKeys(before, current, keys, scrollArmed)
                    .filter { byKey[it]?.readAt == null }
                if (crossed.isNotEmpty()) viewModel.markRead(crossed)
            }
            previous = current
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) scrollArmed = false
        }
    }

    Column(modifier.background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text(source?.title ?: "文章", fontSize = 24.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = ::requestRefresh) {
                if (viewModel.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { scope.launch { confirmKeys = repository.unreadKeys(sourceId) } }) {
                Icon(Icons.Default.CheckCircle, contentDescription = "全部标为已读")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterTab("未读", selected = viewModel.unreadOnly, onClick = {
                viewModel.selectUnreadFilter(true); scope.launch { listState.scrollToItem(0) }
            })
            FilterTab("全部", selected = !viewModel.unreadOnly, onClick = {
                viewModel.selectUnreadFilter(false); scope.launch { listState.scrollToItem(0) }
            })
        }
        source?.error?.let {
            Text("刷新失败：$it", color = ReaderOrange, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 5.dp))
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
                LazyColumn(Modifier.fillMaxSize().nestedScroll(scrollObserver), state = listState) {
                    itemsIndexed(shown, key = { _, article -> article.articleKey }) { _, article ->
                        ArticleRow(article, source?.title ?: "订阅源", onClick = { onOpen(article.articleKey) })
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
private fun FilterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = if (selected) ReaderOrange else Color(0xFFF0F0F4)) {
        Text(label, color = if (selected) Color.White else ReaderMuted, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
    }
}

@Composable
private fun ArticleRow(article: ArticleEntity, sourceTitle: String, onClick: () -> Unit) {
    val read = article.readAt != null
    val titleColor = if (read) ReaderMuted else ReaderText
    Surface(onClick = onClick, color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!read) {
                Box(Modifier.size(6.dp).background(Color(0xFFF5473A), RoundedCornerShape(50)))
                Spacer(Modifier.width(9.dp))
            } else Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text("$sourceTitle · ${articleTime(article)}", color = ReaderMuted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(7.dp))
                Text(article.title, color = titleColor, fontSize = 18.sp, lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (article.summary.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(article.summary, color = ReaderMuted, fontSize = 14.sp, lineHeight = 21.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            article.imageUrl?.let { url ->
                Spacer(Modifier.width(12.dp))
                AsyncImage(model = url, contentDescription = null,
                    modifier = Modifier.size(width = 96.dp, height = 90.dp).background(Color(0xFFF2F2F5), RoundedCornerShape(9.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("M月d日")
private fun articleTime(article: ArticleEntity): String {
    val date = Instant.ofEpochMilli(article.sortAt).atZone(ZoneId.systemDefault()).format(dateFormat)
    return if (article.publishedAt == null) "获取于$date" else date
}
