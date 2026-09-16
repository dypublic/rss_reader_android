package com.codex.rssreader.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions", indices = [Index(value = ["url"], unique = true)])
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val iconUrl: String? = null,
    val addedAt: Long,
    val lastRefreshedAt: Long? = null,
    val error: String? = null,
)

data class SubscriptionRow(
    @Embedded val subscription: SubscriptionEntity,
    val unreadCount: Int,
)

@Entity(tableName = "articles", indices = [Index("sourceId"), Index("sourceId", "readAt")])
data class ArticleEntity(
    @PrimaryKey val articleKey: String,
    val sourceId: Long,
    val sourceItemId: String,
    val link: String?,
    val title: String,
    val summary: String,
    val contentHtml: String,
    val imageUrl: String?,
    val publishedAt: Long?,
    val firstFetchedAt: Long,
    val sortAt: Long,
    val readAt: Long? = null,
    val readingPosition: Int = 0,
)

@Entity(tableName = "seen_articles", indices = [Index("sourceId")])
data class SeenArticleEntity(
    @PrimaryKey val articleKey: String,
    val sourceId: Long,
)

@Dao
interface ReaderDao {
    @Query("SELECT s.*, (SELECT COUNT(*) FROM articles a WHERE a.sourceId = s.id AND a.readAt IS NULL) AS unreadCount FROM subscriptions s ORDER BY s.addedAt ASC, s.id ASC")
    fun observeSubscriptions(): Flow<List<SubscriptionRow>>

    @Query("SELECT * FROM subscriptions ORDER BY addedAt ASC, id ASC")
    suspend fun allSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun subscription(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun subscriptionByUrl(url: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubscription(row: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(row: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: Long)

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId ORDER BY sortAt ASC, firstFetchedAt ASC, articleKey ASC")
    fun observeArticles(sourceId: Long): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE articleKey = :key LIMIT 1")
    suspend fun article(key: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE articleKey = :key LIMIT 1")
    fun observeArticle(key: String): Flow<ArticleEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticle(row: ArticleEntity): Long

    @Update
    suspend fun updateArticle(row: ArticleEntity)

    @Query("SELECT articleKey FROM articles WHERE sourceId = :sourceId AND readAt IS NULL")
    suspend fun unreadKeys(sourceId: Long): List<String>

    @Query("UPDATE articles SET readAt = :now WHERE articleKey IN (:keys) AND readAt IS NULL")
    suspend fun markRead(keys: List<String>, now: Long)

    @Query("UPDATE articles SET readingPosition = :position WHERE articleKey = :key")
    suspend fun saveReadingPosition(key: String, position: Int)

    @Query("SELECT * FROM articles WHERE readAt IS NOT NULL AND readAt <= :deadline")
    suspend fun expiredArticles(deadline: Long): List<ArticleEntity>

    @Query("DELETE FROM articles WHERE articleKey IN (:keys)")
    suspend fun deleteArticles(keys: List<String>)

    @Query("DELETE FROM articles WHERE sourceId = :sourceId")
    suspend fun deleteSourceArticles(sourceId: Long)

    @Query("DELETE FROM seen_articles WHERE sourceId = :sourceId")
    suspend fun deleteSourceSeen(sourceId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM seen_articles WHERE articleKey = :key)")
    suspend fun wasSeen(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeen(rows: List<SeenArticleEntity>)
}

@Database(entities = [SubscriptionEntity::class, ArticleEntity::class, SeenArticleEntity::class], version = 1, exportSchema = false)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun dao(): ReaderDao
}
