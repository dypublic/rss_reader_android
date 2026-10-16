package com.codex.rssreader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import com.codex.rssreader.sync.BackgroundRefresh
import com.codex.rssreader.sync.RssRefreshWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BackgroundRefreshTest {
    @Test fun diagnosticsReadsOwnSystemStateWithoutChangingThePeriodicTask() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderApplication>()
        BackgroundRefresh.schedule(app)
        val manager = WorkManager.getInstance(app)
        val before = manager.getWorkInfosForUniqueWork(BackgroundRefresh.WORK_NAME).get(10, TimeUnit.SECONDS).single()
        val snapshot = app.backgroundDiagnostics.captureAndRecord("测试采样")
        val after = manager.getWorkInfosForUniqueWork(BackgroundRefresh.WORK_NAME).get(10, TimeUnit.SECONDS).single()
        assertEquals(before.id, after.id)
        assertEquals(before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
        assertTrue(snapshot.lines.any { it.contains("电池优化豁免") })
        assertTrue(snapshot.lines.any { it.contains("网络已验证") })
        assertTrue(snapshot.lines.any { it.contains(before.id.toString()) })
        assertFalse(snapshot.asText(), snapshot.lines.any { it.contains("读取失败") })
        assertTrue(app.runtimeLogs.readEntries().any { it.event == "后台诊断 · 测试采样" })
    }

    @Test fun schedulingTwiceKeepsOneTaskAndItsNextRunTime() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val manager = WorkManager.getInstance(app)
        BackgroundRefresh.schedule(app)
        val before = manager.getWorkInfosForUniqueWork(BackgroundRefresh.WORK_NAME).get(10, TimeUnit.SECONDS).single()
        BackgroundRefresh.schedule(app)
        val after = manager.getWorkInfosForUniqueWork(BackgroundRefresh.WORK_NAME).get(10, TimeUnit.SECONDS).single()
        assertEquals(before.id, after.id)
        assertEquals(before.nextScheduleTimeMillis, after.nextScheduleTimeMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(15), after.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, after.constraints.requiredNetworkType)
    }

    @Test fun workerImportsNewArticlesPreservesReadStateAndIsolatesSourceFailures() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReaderApplication>()
        val count = AtomicInteger(1)
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine().orEmpty()
                    while (!input.readLine().isNullOrEmpty()) { /* drain request headers */ }
                    val broken = request.contains("/broken") && count.get() > 1
                    val body = if (broken) "unavailable" else "<rss version=\"2.0\"><channel><title>后台测试</title>" +
                        (1..count.get()).joinToString("") { index ->
                            "<item><guid>background-$index</guid><title>测试 $index</title><description>测试正文</description></item>"
                        } + "</channel></rss>"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(("HTTP/1.1 ${if (broken) "503 Unavailable" else "200 OK"}\r\n" +
                        "Content-Type: application/rss+xml; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    it.getOutputStream().write(bytes)
                }
            }
        }
        val sourceIds = mutableListOf<Long>()
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val healthy = app.repository.addSubscription("$base/healthy").getOrThrow().also { sourceIds += it }
            val broken = app.repository.addSubscription("$base/broken").getOrThrow().also { sourceIds += it }
            app.repository.markRead(app.repository.unreadKeys(healthy))
            count.set(2)
            val firstResult = TestListenableWorkerBuilder<RssRefreshWorker>(app).build().doWork()
            assertTrue(firstResult is ListenableWorker.Result.Retry)
            val rows = app.repository.articles(healthy).first()
            assertEquals(2, rows.size)
            assertNotNull(rows.single { it.sourceItemId == "background-1" }.readAt)
            assertNull(rows.single { it.sourceItemId == "background-2" }.readAt)
            assertTrue(app.repository.subscription(broken)!!.error!!.contains("503"))
            assertEquals(1, app.repository.articles(broken).first().size)
            val logs = app.runtimeLogs.readEntries()
            assertTrue(logs.any { it.event == "后台诊断 · 任务开始" && it.details.contains("深度 Doze") })
            assertTrue(logs.any { it.event == "后台诊断 · 任务返回结果前" && it.details.contains("网络已验证") })
            assertTrue(logs.any { it.event == "后台任务结束" && it.details.contains("等待重试") })
            assertTrue(logs.any { it.event == "后台刷新来源失败" && it.details.contains("ID $broken") })
            assertTrue(logs.any { it.event == "后台刷新来源成功" && it.details.contains("ID $healthy") && it.details.contains("新增 1 篇") })
            val lastResult = TestListenableWorkerBuilder<RssRefreshWorker>(app).setRunAttemptCount(2).build().doWork()
            assertTrue(lastResult is ListenableWorker.Result.Success)
            assertEquals(2, app.repository.articles(healthy).first().size)
            assertTrue(app.runtimeLogs.readEntries().any { it.event == "后台任务结束" && it.details.contains("等待下次周期") })
        } finally {
            sourceIds.forEach { app.repository.deleteSubscription(it) }
            server.close()
            serverThread.join(1000)
        }
    }
}
