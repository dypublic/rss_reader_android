package com.codex.rssreader

import com.codex.rssreader.data.FeedItem
import com.codex.rssreader.rules.ArticleRules
import com.codex.rssreader.rules.ScrollReadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleRulesTest {
    @Test fun firstImportMarksOnlyTheTwentyNewestByDate() {
        val items = (1..25).reversed().map { number ->
            FeedItem("item-$number", null, "Title $number", "", "", null, number * 1_000L)
        }
        val unread = ArticleRules.newestInitialKeys(items, 100_000L) { it.stableId }
        assertEquals(20, unread.size)
        assertEquals((6..25).map { "item-$it" }.toSet(), unread)
        assertEquals(5, ArticleRules.newestInitialKeys(items.take(5), 100_000L) { it.stableId }.size)
    }

    @Test fun fastScrollIncludesUnrenderedIntermediateItemsAndIgnoresReverseMoves() {
        val keys = (0 until 100).map { "article-$it" }
        assertEquals(keys.take(70), ArticleRules.exitedKeys(0, 70, keys))
        assertTrue(ArticleRules.exitedKeys(70, 50, keys).isEmpty())
        assertTrue(ArticleRules.exitedKeys(null, 70, keys).isEmpty())
        assertEquals(keys.drop(90), ArticleRules.exitedKeys(90, 500, keys))
    }

    @Test fun refreshKeepsTheCurrentOrderAndAppendsNewArticles() {
        assertEquals(listOf("a", "b", "d", "c"),
            ArticleRules.appendNewKeys(listOf("a", "b"), listOf("b", "d", "c")))
        assertEquals(listOf("a", "b"), ArticleRules.appendNewKeys(null, listOf("a", "b")))
    }

    @Test fun completedScrollMarksItemsThatLeftTheTop() {
        val keys = (0 until 10).map { "article-$it" }
        val tracker = ScrollReadTracker()
        tracker.updateKeys(keys)

        assertTrue(tracker.onPosition(0).isEmpty())
        assertEquals(keys.take(3), tracker.onPosition(3))
        assertEquals(listOf(keys[3]), tracker.onPosition(4))
    }

    @Test fun scrollTrackerHandlesFlingAndDoesNotEmitAnArticleTwice() {
        val keys = (0 until 100).map { "article-$it" }
        val tracker = ScrollReadTracker()
        tracker.updateKeys(keys)

        tracker.onPosition(0)
        assertEquals(keys.take(40), tracker.onPosition(40))
        assertTrue(tracker.onPosition(40).isEmpty())

        assertTrue(tracker.onPosition(20).isEmpty())
        assertTrue(tracker.onPosition(40).isEmpty())
        assertEquals(keys.subList(40, 50), tracker.onPosition(50))
    }

    @Test fun restoredPositionDoesNotMarkArticlesBeforeTheFirstVisibleItem() {
        val keys = (0 until 100).map { "article-$it" }
        val tracker = ScrollReadTracker()
        tracker.updateKeys(keys)

        assertTrue(tracker.onPosition(50).isEmpty())
        assertEquals(keys.subList(50, 55), tracker.onPosition(55))
        assertTrue(tracker.onPosition(30).isEmpty())
        assertEquals(keys.subList(30, 35), tracker.onPosition(35))
    }

    @Test fun loadingArticlesRebasesAnEarlierEmptyListPosition() {
        val tracker = ScrollReadTracker()
        tracker.onPosition(0)
        tracker.updateKeys(listOf("a", "b", "c"))

        assertTrue(tracker.onPosition(2).isEmpty())
        assertEquals(listOf("c"), tracker.onPosition(3))
    }

    @Test fun appendedKeysKeepProgressButReorderingStartsAnewFromCurrentPosition() {
        val tracker = ScrollReadTracker()
        tracker.updateKeys(listOf("a", "b", "c"))
        tracker.onPosition(0)
        assertEquals(listOf("a"), tracker.onPosition(1))

        tracker.updateKeys(listOf("a", "b", "c", "d"))
        assertEquals(listOf("b", "c"), tracker.onPosition(3))

        tracker.updateKeys(listOf("a", "b", "c", "e", "d"))
        assertTrue(tracker.onPosition(3).isEmpty())
        assertEquals(listOf("e"), tracker.onPosition(4))
    }
}
