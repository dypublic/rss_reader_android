package com.codex.rssreader

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.rssreader.data.ReaderDatabase
import com.codex.rssreader.sync.RefreshTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AutomaticRefreshTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReaderApplication>()

    @Test fun recentSourcesAreSkippedAndManualAndBackgroundSuccessCountAsUpdates() = runBlocking {
        FeedServer().use { server ->
            val ids = mutableListOf<Long>()
            try {
                val fresh = app.repository.addSubscription(server.address("fresh")).getOrThrow().also { ids += it }
                val stale = app.repository.addSubscription(server.address("stale")).getOrThrow().also { ids += it }
                makeStale(stale)
                app.repository.refreshAll(RefreshTrigger.APP_START)
                assertEquals(3, server.requests.get()) // Two imports, only one stale source fetched again.

                app.repository.refreshSource(fresh)
                assertEquals(4, server.requests.get()) // Manual fetch bypasses the 15-minute guard.
                app.repository.refreshAll(RefreshTrigger.APP_START)
                assertEquals(4, server.requests.get())

                app.repository.refreshAll(RefreshTrigger.BACKGROUND)
                assertEquals(6, server.requests.get())
                app.repository.refreshAll(RefreshTrigger.APP_START)
                assertEquals(6, server.requests.get())
                assertTrue(app.runtimeLogs.readEntries().any { it.event == "打开应用使用缓存" })
            } finally { ids.forEach { app.repository.deleteSubscription(it) } }
        }
    }

    @Test fun automaticFetchLeavesProgressOffAndANewViewModelUsesTheStoredTimestamp() = runBlocking {
        FeedServer().use { server ->
            val id = app.repository.addSubscription(server.address("silent")).getOrThrow()
            val store = ViewModelStore()
            try {
                makeStale(id)
                server.holdNextResponse.set(true)
                val model = withContext(Dispatchers.Main) {
                    AppViewModel(app.repository).also { store.put("first", it) }
                }
                val job = withContext(Dispatchers.Main) { model.onAppStart() }
                assertTrue("Automatic network request did not begin", server.responseWaiting.await(5, TimeUnit.SECONDS))
                withContext(Dispatchers.Main) { assertFalse("Automatic refresh must not show UI progress", model.refreshing) }
                assertEquals(1, app.repository.articles(id).first().size) // Cached content remains readable while waiting.
                server.releaseResponse.countDown()
                job.join()
                assertEquals(2, server.requests.get())

                val reopened = withContext(Dispatchers.Main) {
                    store.clear()
                    AppViewModel(app.repository).also { store.put("reopened", it) }.onAppStart()
                }
                reopened.join()
                assertEquals("Recreating UI must not force a fetch", 2, server.requests.get())

                makeStale(id)
                server.fail.set(true)
                val lastSuccess = app.repository.subscription(id)!!.lastRefreshedAt
                app.repository.refreshAll(RefreshTrigger.APP_START)
                assertEquals(lastSuccess, app.repository.subscription(id)!!.lastRefreshedAt)
                assertEquals(1, app.repository.articles(id).first().size)
            } finally {
                server.releaseResponse.countDown()
                withContext(Dispatchers.Main) { store.clear() }
                app.repository.deleteSubscription(id)
            }
        }
    }

    private suspend fun makeStale(id: Long) {
        val database = Room.databaseBuilder(app, ReaderDatabase::class.java, "rss_reader.db").build()
        try {
            val source = database.dao().subscription(id)!!
            database.dao().updateSubscription(source.copy(lastRefreshedAt = System.currentTimeMillis() - 16 * 60_000L))
        } finally { database.close() }
    }

    private class FeedServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val requests = AtomicInteger()
        val fail = AtomicBoolean(false)
        val holdNextResponse = AtomicBoolean(false)
        val responseWaiting = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        private val serverThread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use {
                    val input = it.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* drain HTTP request */ }
                    requests.incrementAndGet()
                    if (holdNextResponse.getAndSet(false)) {
                        responseWaiting.countDown()
                        releaseResponse.await(10, TimeUnit.SECONDS)
                    }
                    val broken = fail.get()
                    val body = if (broken) "unavailable" else "<rss version=\"2.0\"><channel><title>自动刷新测试</title>" +
                        "<item><guid>cached</guid><title>已缓存文章</title><description>可离线阅读</description></item></channel></rss>"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(("HTTP/1.1 ${if (broken) "503 Unavailable" else "200 OK"}\r\n" +
                        "Content-Type: application/rss+xml; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    it.getOutputStream().write(bytes)
                }
            }
        }

        fun address(path: String) = "http://127.0.0.1:${socket.localPort}/$path"
        override fun close() {
            releaseResponse.countDown()
            socket.close()
            serverThread.join(1000)
        }
    }
}
