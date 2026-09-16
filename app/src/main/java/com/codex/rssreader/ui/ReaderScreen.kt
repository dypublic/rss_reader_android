package com.codex.rssreader.ui

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.codex.rssreader.data.ArticleEntity
import com.codex.rssreader.data.ReaderRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReaderScreen(articleKey: String, repository: ReaderRepository, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val articleFlow = remember(articleKey, repository) { repository.article(articleKey).map(::LoadedArticle) }
    val loadedArticle by articleFlow.collectAsState(initial = null)
    val article = loadedArticle?.article
    val sourcesFlow = remember(repository) { repository.subscriptions() }
    val sources by sourcesFlow.collectAsState(initial = emptyList())
    val fontFlow = remember(repository) { repository.preferences.fontSize }
    val fontSize by fontFlow.collectAsState(initial = 18)
    val source = sources.firstOrNull { it.subscription.id == article?.sourceId }?.subscription
    var fontDialog by remember { mutableStateOf(false) }
    var imageUrl by remember { mutableStateOf<String?>(null) }
    var scrollY by remember(articleKey) { mutableIntStateOf(0) }
    var pageLoaded by remember(articleKey) { mutableStateOf(false) }

    LaunchedEffect(articleKey, scrollY, pageLoaded) {
        if (pageLoaded) {
            delay(500)
            repository.saveReadingPosition(articleKey, scrollY)
        }
    }

    Column(modifier.background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { fontDialog = true }) { Icon(Icons.Default.TextFields, contentDescription = "调整字号") }
            IconButton(onClick = {
                article?.link?.let { link ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }
            }) { Icon(Icons.Default.OpenInBrowser, contentDescription = "打开原文") }
        }
        if (loadedArticle == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (article == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("文章已清理或订阅源已删除") }
        } else {
            val row = article
            val baseUrl = row.link ?: source?.url ?: "https://example.com/"
            val sourceTitle = source?.title ?: "订阅源"
            val html = remember(row.articleKey, row.title, row.contentHtml, row.summary, fontSize, sourceTitle) {
                readerHtml(row, baseUrl, fontSize, sourceTitle)
            }
            AndroidView(
                factory = { androidContext ->
                    WebView(androidContext).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        setBackgroundColor(android.graphics.Color.WHITE)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return true
                                when (uri.scheme) {
                                    "reader-image" -> imageUrl = uri.getQueryParameter("url")
                                    "http", "https" -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                }
                                return true
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.post {
                                    view.scrollTo(0, row.readingPosition)
                                    scrollY = row.readingPosition
                                    pageLoaded = true
                                }
                            }
                        }
                        setOnScrollChangeListener { _, _, y, _, _ -> if (pageLoaded) scrollY = y }
                    }
                },
                update = { view ->
                    val tag = "${row.articleKey}:${html.hashCode()}"
                    if (view.tag != tag) {
                        pageLoaded = false
                        view.tag = tag
                        view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    if (fontDialog) {
        var candidate by remember(fontDialog) { mutableIntStateOf(fontSize) }
        AlertDialog(onDismissRequest = { fontDialog = false }, title = { Text("阅读字号") },
            text = { Column { Text("${candidate}sp"); Slider(candidate.toFloat(), onValueChange = { candidate = it.toInt() }, valueRange = 14f..28f, steps = 13) } },
            confirmButton = { TextButton(onClick = { scope.launch { repository.preferences.setFontSize(candidate); fontDialog = false } }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { fontDialog = false }) { Text("取消") } })
    }
    imageUrl?.let { image ->
        Dialog(onDismissRequest = { imageUrl = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember(image) { mutableStateOf(1f) }
            val transform = rememberTransformableState { zoomChange, _, _ -> scale = (scale * zoomChange).coerceIn(1f, 5f) }
            Box(Modifier.fillMaxSize().background(Color.Black).transformable(transform), contentAlignment = Alignment.Center) {
                AsyncImage(model = image, contentDescription = "文章图片", modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale))
                TextButton(onClick = { imageUrl = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("关闭", color = Color.White) }
            }
        }
    }
}

private data class LoadedArticle(val article: ArticleEntity?)

private fun readerHtml(article: ArticleEntity, baseUrl: String, fontSize: Int, sourceTitle: String): String {
    val source = article.contentHtml.ifBlank { article.summary }
    val safelist = Safelist.relaxed().addTags("pre", "code", "blockquote")
    val cleaned = Jsoup.clean(source, baseUrl, safelist)
    val fragment = Jsoup.parseBodyFragment(cleaned, baseUrl)
    fragment.select("img[src]").forEach { img ->
        val url = img.absUrl("src")
        if (url.startsWith("http://") || url.startsWith("https://")) {
            img.attr("src", url)
            img.wrap("<a href=\"reader-image://view?url=${Uri.encode(url)}\"></a>")
        }
    }
    val stamp = Instant.ofEpochMilli(article.sortAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;padding:0 22px 24px;color:#283542;font: ${fontSize}px/1.75 sans-serif;overflow-wrap:anywhere">
        <style>h1{margin:25px 0 15px;color:#151719;font:600 31px/1.4 sans-serif} .meta{color:#92969c;font:14px/1.5 sans-serif;margin-bottom:36px}
        img{max-width:100%;height:auto;border-radius:8px}pre,code{white-space:pre-wrap;overflow-wrap:anywhere}blockquote{border-left:3px solid #e5e6eb;margin:20px 0;padding-left:16px;font-style:italic}a{color:#e65e11}</style>
        <h1>${TextUtils.htmlEncode(article.title)}</h1>
        <div class="meta">${TextUtils.htmlEncode(sourceTitle)} · $stamp</div>
        ${fragment.body().html()}</body></html>"""
}
