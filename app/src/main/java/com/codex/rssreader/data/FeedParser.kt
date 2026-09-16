package com.codex.rssreader.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.net.URI
import java.util.Locale

data class FeedItem(
    val stableId: String,
    val link: String?,
    val title: String,
    val summary: String,
    val contentHtml: String,
    val imageUrl: String?,
    val publishedAt: Long?,
)

data class ParsedFeed(val title: String, val items: List<FeedItem>, val iconUrl: String? = null)

object FeedParser {
    fun parse(xml: String, fallbackTitle: String, baseUrl: String = ""): ParsedFeed {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        val root = document.selectFirst("rss > channel") ?: document.selectFirst("feed")
            ?: throw IllegalArgumentException("内容不是 RSS 或 Atom 订阅源")
        val isAtom = root.tagName().equals("feed", ignoreCase = true)
        val feedTitle = root.children().firstOrNull { it.tagName().equals("title", true) }?.text().orEmpty().ifBlank { fallbackTitle }
        val entries = root.children().filter { it.tagName().equals(if (isAtom) "entry" else "item", true) }
        val items = entries.mapIndexedNotNull { index, entry -> item(entry, isAtom, index, baseUrl) }
        val icon = if (isAtom) {
            root.children().firstOrNull { it.tagName().equals("logo", true) || it.tagName().equals("icon", true) }?.wholeText()
        } else {
            root.children().firstOrNull { it.tagName().equals("image", true) }
                ?.children()?.firstOrNull { it.tagName().equals("url", true) }?.wholeText()
        }
        return ParsedFeed(feedTitle, items, resolveUrl(baseUrl, icon))
    }

    private fun item(entry: Element, atom: Boolean, index: Int, baseUrl: String): FeedItem? {
        fun field(vararg names: String): String? = entry.children().firstOrNull { child ->
            names.any { it.equals(child.tagName(), true) }
        }?.let { it.wholeText().trim().ifBlank { null } }

        val rawLink = if (atom) entry.children().firstOrNull {
            it.tagName().equals("link", true) && (it.attr("rel").isBlank() || it.attr("rel") == "alternate")
        }?.attr("href")?.ifBlank { null } else field("link")
        val link = resolveUrl(baseUrl, rawLink)
        val title = field("title")?.let { Jsoup.parse(it).text() }?.ifBlank { "无标题" } ?: "无标题"
        val summaryHtml = field("summary", "description").orEmpty()
        val contentNode = entry.children().firstOrNull { it.tagName().equals("content:encoded", true) || it.tagName().equals("content", true) }
        val contentValue = if (contentNode?.children()?.isNotEmpty() == true) {
            contentNode.children().joinToString("") { it.outerHtml() }
        } else contentNode?.wholeText()
        val contentHtml = contentValue?.ifBlank { null } ?: summaryHtml
        val summary = Jsoup.parse(summaryHtml).text().ifBlank { Jsoup.parse(contentHtml).text() }.take(700)
        val date = field("pubDate", "published", "updated", "dc:date")?.let(::parseDate)
        val rawImage = entry.children().firstOrNull { it.tagName().equals("media:thumbnail", true) }?.attr("url")?.ifBlank { null }
            ?: entry.children().firstOrNull { it.tagName().equals("media:content", true) && it.attr("medium") == "image" }?.attr("url")?.ifBlank { null }
            ?: entry.children().firstOrNull { it.tagName().equals("enclosure", true) && it.attr("type").startsWith("image/") }?.attr("url")?.ifBlank { null }
            ?: Jsoup.parse(contentHtml).selectFirst("img[src]")?.attr("src")
        val image = resolveUrl(link ?: baseUrl, rawImage)
        val guid = field("guid", "id")
        val stableId = guid ?: link ?: "$title|${date ?: index}|$summary"
        if (stableId.isBlank()) return null
        return FeedItem(stableId, link, title, summary, contentHtml, image, date)
    }

    private fun resolveUrl(base: String, value: String?): String? {
        val raw = value?.trim()?.ifBlank { null } ?: return null
        return runCatching { URI(base).resolve(raw).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    internal fun parseDate(raw: String): Long? {
        val value = raw.trim()
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                val formatter = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                formatter.parse(value)?.time
            }.getOrNull()
    }
}
