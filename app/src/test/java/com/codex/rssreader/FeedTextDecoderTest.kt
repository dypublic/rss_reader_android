package com.codex.rssreader

import com.codex.rssreader.data.FeedTextDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class FeedTextDecoderTest {
    @Test fun usesXmlDeclarationWhenHttpCharsetIsMissing() {
        val xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?><rss><channel><title>中文订阅</title></channel></rss>"
        val bytes = xml.toByteArray(Charset.forName("GB2312"))
        assertTrue(FeedTextDecoder.decode(bytes, "application/xml").contains("中文订阅"))
    }

    @Test fun httpCharsetOverridesXmlDeclaration() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><title>中文订阅</title></channel></rss>"
        val bytes = xml.toByteArray(Charset.forName("GBK"))
        assertTrue(FeedTextDecoder.decode(bytes, "text/xml; charset=gbk").contains("中文订阅"))
    }
}
