package com.codex.rssreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

class FeedClient {
    suspend fun fetch(address: String): ParsedFeed = withContext(Dispatchers.IO) {
        val uri = URI(address.trim())
        require(uri.scheme == "http" || uri.scheme == "https") { "请输入 http 或 https 订阅地址" }
        require(!uri.host.isNullOrBlank()) { "订阅地址无效" }
        val connection = URL(address.trim()).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("User-Agent", "RSSReader/0.1 (Android)")
        connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "订阅源请求失败（HTTP $code）" }
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count: Int
                while (stream.read(buffer).also { count = it } != -1) {
                    require(output.size() + count <= 8_000_000) { "订阅内容超过 8 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val xml = FeedTextDecoder.decode(bytes, connection.contentType)
            FeedParser.parse(xml, uri.host, address.trim())
        } finally {
            connection.disconnect()
        }
    }
}

internal object FeedTextDecoder {
    private val headerCharset = Regex("charset\\s*=\\s*['\"]?([^;\\s'\"]+)", RegexOption.IGNORE_CASE)
    private val xmlCharset = Regex("<\\?xml[^>]*\\bencoding\\s*=\\s*['\"]([^'\"]+)", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, contentType: String?): String {
        val fromHeader = contentType?.let { headerCharset.find(it)?.groupValues?.get(1) }
        val declaration = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val fromXml = xmlCharset.find(declaration)?.groupValues?.get(1)
        val encoding = sequenceOf(fromHeader, fromXml).filterNotNull()
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .firstOrNull() ?: Charsets.UTF_8
        return String(bytes, encoding)
    }
}
