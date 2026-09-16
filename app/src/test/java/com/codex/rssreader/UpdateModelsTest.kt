package com.codex.rssreader

import com.codex.rssreader.update.ReleaseParser
import com.codex.rssreader.update.VersionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelsTest {
    @Test fun comparesReleaseVersions() {
        assertTrue(VersionOrder.isNewer("0.1.1", "0.1"))
        assertTrue(VersionOrder.isNewer("0.2.0", "0.1.99"))
        assertTrue(VersionOrder.isNewer("1.0.0", "1.0.0-beta.2"))
        assertFalse(VersionOrder.isNewer("1.0.0-beta.2", "1.0.0"))
        assertFalse(VersionOrder.isNewer("1.0.0", "1.0.0"))
    }

    @Test fun parsesLatestReleaseAndSelectsSignedApk() {
        val payload = """{
          "tag_name":"v0.2.0",
          "html_url":"https://github.com/dypublic/rss_reader_android/releases/tag/v0.2.0",
          "body":"修复列表并加入更新功能",
          "assets":[
            {"name":"rss-reader-debug.apk","browser_download_url":"https://example.com/debug.apk","digest":"sha256:${"a".repeat(64)}","size":12},
            {"name":"rss-reader-0.2.0-release.apk","browser_download_url":"https://example.com/release.apk","digest":"sha256:${"b".repeat(64)}","size":14500000}
          ]
        }"""

        val release = ReleaseParser.parseLatest(payload)
        assertEquals("0.2.0", release.versionName)
        assertEquals("rss-reader-0.2.0-release.apk", release.apkName)
        assertEquals("b".repeat(64), release.apkSha256)
        assertEquals(14_500_000L, release.apkSize)
    }

    @Test fun rejectsReleaseWithoutSha256() {
        val payload = """{
          "tag_name":"v0.2.0",
          "html_url":"https://example.com/release",
          "assets":[{"name":"rss-reader-0.2.0-release.apk","browser_download_url":"https://example.com/app.apk","size":12}]
        }"""
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parseLatest(payload) }
    }

    @Test fun rejectsInsecureApkUrl() {
        val payload = """{
          "tag_name":"v0.2.0",
          "html_url":"https://example.com/release",
          "assets":[{"name":"rss-reader-0.2.0-release.apk","browser_download_url":"http://example.com/app.apk","digest":"sha256:${"a".repeat(64)}","size":12}]
        }"""
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parseLatest(payload) }
    }
}
