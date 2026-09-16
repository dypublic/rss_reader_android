package com.codex.rssreader.rules

import com.codex.rssreader.data.FeedItem

object ArticleRules {
    fun newestInitialKeys(items: List<FeedItem>, fetchedAt: Long, keyOf: (FeedItem) -> String): Set<String> =
        items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<FeedItem>> { it.value.publishedAt ?: fetchedAt }.thenBy { it.index })
            .take(20)
            .map { keyOf(it.value) }
            .toSet()

    fun crossedKeys(previousTop: Int?, currentTop: Int, orderedKeys: List<String>, userScrolledDown: Boolean): List<String> {
        if (!userScrolledDown || previousTop == null || currentTop <= previousTop) return emptyList()
        val from = previousTop.coerceIn(0, orderedKeys.size)
        val until = currentTop.coerceIn(0, orderedKeys.size)
        return if (until > from) orderedKeys.subList(from, until) else emptyList()
    }

}
