package com.codex.rssreader.sync

internal object AutoRefreshPolicy {
    private const val INTERVAL_MILLIS = 15 * 60_000L

    fun isDue(lastRefreshedAt: Long?, now: Long): Boolean =
        lastRefreshedAt == null || now < lastRefreshedAt || now - lastRefreshedAt >= INTERVAL_MILLIS
}
