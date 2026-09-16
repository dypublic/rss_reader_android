package com.codex.rssreader

import com.codex.rssreader.data.FeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.jsoup.Jsoup

class FeedParserTest {
    @Test fun parsesRssWithHtmlAndImageEnclosure() {
        val xml = """<rss version="2.0"><channel><title>示例 RSS</title>
            <item><guid>one</guid><title>第一篇</title><link>https://example.com/one</link>
            <pubDate>Mon, 14 Sep 2026 10:00:00 GMT</pubDate>
            <description><![CDATA[<p>摘要<b>内容</b></p>]]></description>
            <enclosure url="https://example.com/a.jpg" type="image/jpeg" /></item>
            </channel></rss>"""
        val feed = FeedParser.parse(xml, "fallback")
        assertEquals("示例 RSS", feed.title)
        assertEquals(1, feed.items.size)
        assertEquals("one", feed.items.single().stableId)
        assertEquals("摘要内容", feed.items.single().summary)
        assertEquals("https://example.com/a.jpg", feed.items.single().imageUrl)
        assertNotNull(feed.items.single().publishedAt)
    }

    @Test fun parsesAtomAndRejectsOrdinaryWebPage() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>示例 Atom</title>
            <entry><id>urn:article:1</id><title>Atom 文章</title>
            <link rel="alternate" href="https://example.com/atom/1"/>
            <updated>2026-09-14T12:00:00Z</updated><summary>短摘要</summary>
            <content type="html">&lt;p&gt;全文&lt;/p&gt;</content></entry></feed>"""
        val feed = FeedParser.parse(atom, "fallback")
        assertEquals("示例 Atom", feed.title)
        assertEquals("urn:article:1", feed.items.single().stableId)
        assertEquals("https://example.com/atom/1", feed.items.single().link)
        assertEquals("<p>全文</p>", feed.items.single().contentHtml)
        assertThrows(IllegalArgumentException::class.java) { FeedParser.parse("<html><body>普通网页</body></html>", "fallback") }
    }

    @Test fun atomXhtmlContentKeepsParagraphStructure() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>XHTML</title><entry>
            <id>urn:article:2</id><title>示例</title><content type="xhtml">
            <div xmlns="http://www.w3.org/1999/xhtml"><p>第一段</p><p>第二段</p></div>
            </content></entry></feed>"""
        val body = FeedParser.parse(atom, "fallback").items.single().contentHtml
        assertEquals(2, Jsoup.parse(body).select("p").size)
    }
}
