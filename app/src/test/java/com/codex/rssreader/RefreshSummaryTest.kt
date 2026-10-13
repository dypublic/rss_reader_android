package com.codex.rssreader

import com.codex.rssreader.sync.RefreshSummary
import org.junit.Assert.*
import org.junit.Test

class RefreshSummaryTest {
    @Test fun successfulAndEmptyRefreshesDoNotRetry() {
        assertFalse(RefreshSummary().shouldRetry(0))
        assertFalse(RefreshSummary(succeeded = 3, newArticles = 20).shouldRetry(0))
    }

    @Test fun partialFailuresAndBusyRefreshesRetryWithoutAnEndlessLoop() {
        listOf(RefreshSummary(succeeded = 2, failed = 1), RefreshSummary(skipped = true)).forEach { summary ->
            assertTrue(summary.shouldRetry(0))
            assertTrue(summary.shouldRetry(1))
            assertFalse(summary.shouldRetry(2))
            assertFalse(summary.shouldRetry(100))
        }
    }
}
