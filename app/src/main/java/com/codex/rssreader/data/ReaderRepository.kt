package com.codex.rssreader.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.codex.rssreader.rules.ArticleRules
import com.codex.rssreader.diagnostics.LogLevel
import com.codex.rssreader.diagnostics.RuntimeLogStore
import com.codex.rssreader.sync.RefreshSummary
import com.codex.rssreader.sync.RefreshTrigger
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ReaderRepository(context: Context, private val logs: RuntimeLogStore) {
    private val database = Room.databaseBuilder(context.applicationContext, ReaderDatabase::class.java, "rss_reader.db").build()
    private val dao = database.dao()
    private val client = FeedClient()
    val preferences = ReaderPreferences(context.applicationContext)
    private val refreshMutex = Mutex()

    fun subscriptions(): Flow<List<SubscriptionRow>> = dao.observeSubscriptions()
    fun articles(sourceId: Long): Flow<List<ArticleEntity>> = dao.observeArticles(sourceId)
    fun article(key: String): Flow<ArticleEntity?> = dao.observeArticle(key)
    suspend fun subscription(id: Long): SubscriptionEntity? = dao.subscription(id)
    suspend fun unreadKeys(id: Long): List<String> = dao.unreadKeys(id)

    suspend fun previewSubscription(address: String): Result<ParsedFeed> = runCatching {
        val normalized = normalizeAddress(address)
        require(dao.subscriptionByUrl(normalized) == null) { "该订阅源已添加" }
        client.fetch(normalized)
    }

    suspend fun addSubscription(address: String, preview: ParsedFeed? = null): Result<Long> = runCatching {
        val normalized = normalizeAddress(address)
        require(dao.subscriptionByUrl(normalized) == null) { "该订阅源已添加" }
        val parsed = preview ?: client.fetch(normalized)
        val now = System.currentTimeMillis()
        val id = database.withTransaction {
            require(dao.subscriptionByUrl(normalized) == null) { "该订阅源已添加" }
            val newId = dao.insertSubscription(SubscriptionEntity(url = normalized, title = parsed.title, iconUrl = parsed.iconUrl, addedAt = now, lastRefreshedAt = now))
            syncParsed(newId, parsed, initial = true, now = now)
            newId
        }
        id
    }

    suspend fun renameSubscription(id: Long, newTitle: String) {
        val title = newTitle.trim()
        require(title.isNotBlank()) { "名称不能为空" }
        dao.subscription(id)?.let { dao.updateSubscription(it.copy(title = title)) }
    }

    suspend fun deleteSubscription(id: Long) = database.withTransaction {
        dao.deleteSourceArticles(id)
        dao.deleteSourceSeen(id)
        dao.deleteSubscription(id)
    }

    suspend fun refreshAll(trigger: RefreshTrigger = RefreshTrigger.MANUAL): RefreshSummary = refresh(trigger)

    suspend fun refreshSource(sourceId: Long): RefreshSummary = refresh(RefreshTrigger.MANUAL, sourceId)

    private suspend fun refresh(trigger: RefreshTrigger, sourceId: Long? = null): RefreshSummary = withContext(Dispatchers.IO) {
        if (!refreshMutex.tryLock()) {
            logs.record("${trigger.label}跳过", "已有刷新正在执行", LogLevel.WARNING)
            return@withContext RefreshSummary(skipped = true)
        }
        val startedAt = SystemClock.elapsedRealtime()
        logs.record("${trigger.label}开始", sourceId?.let { "来源 ID $it" } ?: "全部订阅源")
        try {
            val sources = if (sourceId == null) dao.allSubscriptions() else listOfNotNull(dao.subscription(sourceId))
            var succeeded = 0
            var failed = 0
            var newArticles = 0
            sources.forEach { source ->
                val added = refreshOne(source, trigger)
                if (added == null) failed++ else {
                    succeeded++
                    newArticles += added
                }
            }
            logs.record(
                "${trigger.label}结束",
                "来源 ${sources.size} 个；成功 $succeeded；失败 $failed；新增 $newArticles 篇；耗时 ${SystemClock.elapsedRealtime() - startedAt} ms",
                if (failed > 0) LogLevel.WARNING else LogLevel.INFO,
            )
            RefreshSummary(succeeded, failed, newArticles)
        } catch (cancelled: CancellationException) {
            logs.record("${trigger.label}中止", "耗时 ${SystemClock.elapsedRealtime() - startedAt} ms", LogLevel.WARNING)
            throw cancelled
        } catch (error: Exception) {
            logs.record("${trigger.label}异常", error.javaClass.simpleName, LogLevel.ERROR)
            throw error
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshOne(source: SubscriptionEntity, trigger: RefreshTrigger): Int? {
        val startedAt = SystemClock.elapsedRealtime()
        // Do not include private feed URLs, query tokens, or response bodies in diagnostics.
        val label = "${source.title.take(80)}（ID ${source.id}）"
        return try {
            val parsed = client.fetch(source.url)
            val now = System.currentTimeMillis()
            val added = database.withTransaction {
                val current = dao.subscription(source.id) ?: return@withTransaction 0
                val inserted = syncParsed(source.id, parsed, initial = false, now = now)
                dao.updateSubscription(current.copy(iconUrl = parsed.iconUrl ?: current.iconUrl, lastRefreshedAt = now, error = null))
                inserted
            }
            logs.record("${trigger.label}来源成功", "$label；获取 ${parsed.items.size} 篇；新增 $added 篇；耗时 ${SystemClock.elapsedRealtime() - startedAt} ms")
            added
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            dao.subscription(source.id)?.let { dao.updateSubscription(it.copy(error = error.message ?: "刷新失败")) }
            val briefError = error.message.orEmpty().take(300).replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[订阅地址]")
            logs.record("${trigger.label}来源失败", "$label；${error.javaClass.simpleName} $briefError；耗时 ${SystemClock.elapsedRealtime() - startedAt} ms", LogLevel.ERROR)
            null
        }
    }

    private suspend fun syncParsed(sourceId: Long, parsed: ParsedFeed, initial: Boolean, now: Long): Int {
        val feedItems = parsed.items.distinctBy { stableKey(sourceId, it.stableId) }
        val newestKeys = ArticleRules.newestInitialKeys(feedItems, now) { stableKey(sourceId, it.stableId) }
        return database.withTransaction {
            var inserted = 0
            feedItems.forEach { item ->
                val key = stableKey(sourceId, item.stableId)
                val existing = dao.article(key)
                if (existing != null) {
                    dao.updateArticle(existing.copy(
                        link = item.link ?: existing.link,
                        title = item.title,
                        summary = item.summary,
                        contentHtml = item.contentHtml,
                        imageUrl = item.imageUrl ?: existing.imageUrl,
                        publishedAt = item.publishedAt ?: existing.publishedAt,
                        sortAt = item.publishedAt ?: existing.sortAt,
                    ))
                } else if (!dao.wasSeen(key)) {
                    val rowId = dao.insertArticle(ArticleEntity(
                        articleKey = key,
                        sourceId = sourceId,
                        sourceItemId = item.stableId,
                        link = item.link,
                        title = item.title,
                        summary = item.summary,
                        contentHtml = item.contentHtml,
                        imageUrl = item.imageUrl,
                        publishedAt = item.publishedAt,
                        firstFetchedAt = now,
                        sortAt = item.publishedAt ?: now,
                        readAt = if (initial && key !in newestKeys) now else null,
                    ))
                    if (rowId != -1L) inserted++
                }
            }
            inserted
        }
    }

    suspend fun markRead(keys: List<String>) {
        if (keys.isNotEmpty()) dao.markRead(keys.distinct(), System.currentTimeMillis())
    }

    suspend fun saveReadingPosition(key: String, position: Int) = dao.saveReadingPosition(key, position.coerceAtLeast(0))

    suspend fun clearExpired(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val deadline = now - TimeUnit.DAYS.toMillis(30)
        database.withTransaction {
            val expired = dao.expiredArticles(deadline)
            if (expired.isNotEmpty()) {
                dao.insertSeen(expired.map { SeenArticleEntity(it.articleKey, it.sourceId) })
                dao.deleteArticles(expired.map { it.articleKey })
                logs.record("清理过期已读文章", "删除 ${expired.size} 篇；去重记录保留")
            }
        }
    }

    private fun normalizeAddress(address: String): String {
        val uri = URI(address.trim())
        require(uri.scheme == "http" || uri.scheme == "https") { "请输入 http 或 https 订阅地址" }
        require(!uri.host.isNullOrBlank()) { "订阅地址无效" }
        return URI(uri.scheme.lowercase(), uri.userInfo, uri.host.lowercase(), uri.port, uri.path, uri.query, null).toASCIIString()
    }

    private fun stableKey(sourceId: Long, sourceItemId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$sourceId:$sourceItemId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
