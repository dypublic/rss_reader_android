package com.codex.rssreader.rules

import com.codex.rssreader.data.FeedItem

object ArticleRules {
    fun newestInitialKeys(items: List<FeedItem>, fetchedAt: Long, keyOf: (FeedItem) -> String): Set<String> =
        items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<FeedItem>> { it.value.publishedAt ?: fetchedAt }.thenBy { it.index })
            .take(20)
            .map { keyOf(it.value) }
            .toSet()

    fun exitedKeys(previousTop: Int?, currentTop: Int, orderedKeys: List<String>): List<String> {
        if (previousTop == null || currentTop <= previousTop) return emptyList()
        val from = previousTop.coerceIn(0, orderedKeys.size)
        val until = currentTop.coerceIn(0, orderedKeys.size)
        return if (until > from) orderedKeys.subList(from, until) else emptyList()
    }

    fun appendNewKeys(currentKeys: List<String>?, selectedKeys: List<String>): List<String> {
        if (currentKeys == null) return selectedKeys
        val existing = currentKeys.toHashSet()
        return currentKeys + selectedKeys.filter(existing::add)
    }
}

class ScrollReadTracker {
    private var orderedKeys: List<String> = emptyList()
    private var previousTop: Int? = null
    private val emittedKeys = mutableSetOf<String>()

    fun updateKeys(keys: List<String>) {
        if (orderedKeys == keys) return
        // A removal or reorder changes what an index means. The next position becomes a new baseline.
        if (orderedKeys.isEmpty() || keys.size < orderedKeys.size || keys.take(orderedKeys.size) != orderedKeys) {
            previousTop = null
        }
        orderedKeys = keys
    }

    fun onPosition(firstVisibleItemIndex: Int): List<String> {
        val exited = ArticleRules.exitedKeys(
            previousTop = previousTop,
            currentTop = firstVisibleItemIndex,
            orderedKeys = orderedKeys,
        ).filter(emittedKeys::add)
        previousTop = firstVisibleItemIndex
        return exited
    }
}
