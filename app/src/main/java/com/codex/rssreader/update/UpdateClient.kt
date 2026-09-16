package com.codex.rssreader.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateClient(private val context: Context) {
    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val connection = open(LATEST_RELEASE_URL, "application/vnd.github+json")
        try {
            requireSuccess(connection, "检查更新")
            val payload = readLimited(connection, MAX_METADATA_BYTES).toString(Charsets.UTF_8)
            ReleaseParser.parseLatest(payload)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        release: ReleaseInfo,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach(File::delete)
        val finalFile = File(updateDir, release.apkName.replace(Regex("[^0-9A-Za-z._-]"), "_"))
        val temporary = File(updateDir, "${finalFile.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.apkUrl, "application/vnd.android.package-archive")
        try {
            requireSuccess(connection, "下载更新")
            val expectedLength = connection.contentLengthLong.takeIf { it > 0 }
            require(expectedLength == null || expectedLength <= MAX_APK_BYTES) { "更新包超过 200 MB" }
            var total = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= MAX_APK_BYTES) { "更新包超过 200 MB" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        expectedLength?.let {
                            val progress = (total.toDouble() / it).toFloat().coerceIn(0f, 1f)
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            require(total > 0L) { "下载到的更新包为空" }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual == release.apkSha256) { "更新包 SHA-256 校验失败" }
            require(temporary.renameTo(finalFile)) { "无法保存更新包" }
            onProgress(1f)
            finalFile
        } catch (error: Throwable) {
            temporary.delete()
            finalFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, accept: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RSSReader-Android")
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun requireSuccess(connection: HttpURLConnection, operation: String) {
        val code = connection.responseCode
        require(code in 200..299) { "$operation 失败（HTTP $code）" }
    }

    private fun readLimited(connection: HttpURLConnection, limit: Int): ByteArray {
        connection.contentLengthLong.takeIf { it > 0 }?.let { require(it <= limit) { "更新信息过大" } }
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= limit) { "更新信息过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/dypublic/rss_reader_android/releases/latest"
        private const val MAX_METADATA_BYTES = 2_000_000
        private const val MAX_APK_BYTES = 200_000_000L
    }
}
