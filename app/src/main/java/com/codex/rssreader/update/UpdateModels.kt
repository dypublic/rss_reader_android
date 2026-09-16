package com.codex.rssreader.update

import org.json.JSONObject
import java.io.File
import java.net.URI

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releaseUrl: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Checking(val manual: Boolean) : UpdateUiState
    data class UpToDate(val versionName: String) : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateUiState
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

object ReleaseParser {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")

    fun parseLatest(payload: String): ReleaseInfo {
        val json = JSONObject(payload)
        val tagName = json.getString("tag_name")
        val versionName = tagName.removePrefix("v")
        require(VersionOrder.isValid(versionName)) { "Release 版本号无效：$tagName" }

        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith("-release.apk") }
            ?: error("Release 中没有正式 APK")
        val digest = apk.optString("digest").removePrefix("sha256:")
        require(sha256.matches(digest)) { "Release APK 缺少有效的 SHA-256" }

        val releaseUrl = json.getString("html_url").also(::requireHttps)
        val apkUrl = apk.getString("browser_download_url").also(::requireHttps)
        return ReleaseInfo(
            versionName = versionName,
            tagName = tagName,
            releaseUrl = releaseUrl,
            notes = if (json.isNull("body")) "" else json.optString("body"),
            apkName = apk.getString("name"),
            apkUrl = apkUrl,
            apkSha256 = digest.lowercase(),
            apkSize = apk.optLong("size").coerceAtLeast(0L),
        )
    }

    private fun requireHttps(address: String) {
        val uri = URI(address)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Release 包含不安全的下载地址" }
    }
}

object VersionOrder {
    private val supported = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[.-][0-9A-Za-z.-]+)?$")

    fun isValid(value: String): Boolean = supported.matches(value)

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    internal fun compare(left: String, right: String): Int {
        val l = ParsedVersion.parse(left)
        val r = ParsedVersion.parse(right)
        l.numbers.indices.forEach { index ->
            val compared = l.numbers[index].compareTo(r.numbers[index])
            if (compared != 0) return compared
        }
        return when {
            l.qualifier == null && r.qualifier != null -> 1
            l.qualifier != null && r.qualifier == null -> -1
            else -> (l.qualifier ?: "").compareTo(r.qualifier ?: "", ignoreCase = true)
        }
    }

    private data class ParsedVersion(val numbers: List<Long>, val qualifier: String?) {
        companion object {
            fun parse(raw: String): ParsedVersion {
                val normalized = raw.removePrefix("v")
                val numericEnd = normalized.indexOfFirst { it == '-' || it.isLetter() }
                    .let { if (it == -1) normalized.length else it }
                val numeric = normalized.take(numericEnd).trimEnd('.')
                val qualifier = normalized.drop(numericEnd).trimStart('-', '.').ifBlank { null }
                val values = numeric.split('.').map { it.toLongOrNull() ?: 0L }.toMutableList()
                while (values.size < 4) values += 0L
                return ParsedVersion(values.take(4), qualifier)
            }
        }
    }
}
