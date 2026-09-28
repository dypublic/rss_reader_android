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

    @Test fun fastScrollIncludesUnrenderedIntermediateItemsAndIgnoresOtherMoves() {
        val keys = (0 until 100).map { "article-$it" }
        assertEquals(keys.take(70), ArticleRules.crossedKeys(0, 70, keys, true))
        assertTrue(ArticleRules.crossedKeys(70, 50, keys, true).isEmpty())
        assertTrue(ArticleRules.crossedKeys(0, 70, keys, false).isEmpty())
        assertTrue(ArticleRules.crossedKeys(null, 70, keys, true).isEmpty())
        assertEquals(keys.drop(90), ArticleRules.crossedKeys(90, 500, keys, true))
    }

    @Test fun completedScrollStillMarksItemsThatLeftTheTop() {
        val keys = (0 until 10).map { "article-$it" }
        val tracker = ScrollReadTracker(keys)

        assertTrue(tracker.onPosition(0, false).isEmpty())
        tracker.armDownwardScroll()
        assertEquals(keys.take(3), tracker.onPosition(3, false))
        assertTrue(tracker.onPosition(4, false).isEmpty())
    }

    @Test fun scrollTrackerHandlesFlingAndDoesNotEmitAnArticleTwice() {
        val keys = (0 until 100).map { "article-$it" }
        val tracker = ScrollReadTracker(keys)

        tracker.onPosition(0, false)
        tracker.armDownwardScroll()
        assertTrue(tracker.onPosition(0, true).isEmpty())
        assertEquals(keys.take(40), tracker.onPosition(40, true))
        assertTrue(tracker.onPosition(40, false).isEmpty())

        tracker.armDownwardScroll()
        assertTrue(tracker.onPosition(20, true).isEmpty())
        assertTrue(tracker.onPosition(40, false).isEmpty())
    }
}
