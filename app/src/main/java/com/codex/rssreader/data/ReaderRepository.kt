package com.codex.rssreader.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.codex.rssreader.rules.ArticleRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ReaderRepository(context: Context) {
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

    suspend fun refreshAll() {
        if (!refreshMutex.tryLock()) return
        try {
            dao.allSubscriptions().forEach { refreshOne(it) }
        } finally {
            refreshMutex.unlock()
        }
    }

    suspend fun refreshSource(sourceId: Long) {
        if (!refreshMutex.tryLock()) return
        try {
            dao.subscription(sourceId)?.let { refreshOne(it) }
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun refreshOne(source: SubscriptionEntity) {
        runCatching {
            val parsed = client.fetch(source.url)
            val now = System.currentTimeMillis()
            syncParsed(source.id, parsed, initial = false, now = now)
            dao.subscription(source.id)?.let { dao.updateSubscription(it.copy(iconUrl = parsed.iconUrl ?: it.iconUrl, lastRefreshedAt = now, error = null)) }
        }.onFailure { error ->
            dao.subscription(source.id)?.let { dao.updateSubscription(it.copy(error = error.message ?: "刷新失败")) }
        }
    }

    private suspend fun syncParsed(sourceId: Long, parsed: ParsedFeed, initial: Boolean, now: Long) {
        val feedItems = parsed.items.distinctBy { stableKey(sourceId, it.stableId) }
        val newestKeys = ArticleRules.newestInitialKeys(feedItems, now) { stableKey(sourceId, it.stableId) }
        database.withTransaction {
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
                    dao.insertArticle(ArticleEntity(
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
                }
            }
        }
    }

    suspend fun markRead(keys: List<String>) {
        if (keys.isNotEmpty()) dao.markRead(keys.distinct(), System.currentTimeMillis())
    }

    suspend fun saveReadingPosition(key: String, position: Int) = dao.saveReadingPosition(key, position.coerceAtLeast(0))

    suspend fun clearExpired(now: Long = System.currentTimeMillis()) {
        val deadline = now - TimeUnit.DAYS.toMillis(30)
        database.withTransaction {
            val expired = dao.expiredArticles(deadline)
            if (expired.isNotEmpty()) {
                dao.insertSeen(expired.map { SeenArticleEntity(it.articleKey, it.sourceId) })
                dao.deleteArticles(expired.map { it.articleKey })
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
