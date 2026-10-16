package com.codex.rssreader

import com.codex.rssreader.sync.AutoRefreshPolicy
import org.junit.Assert.*
import org.junit.Test

class AutoRefreshPolicyTest {
    @Test fun refreshBecomesDueAtFifteenMinutes() {
        val last = 10_000_000L
        assertFalse(AutoRefreshPolicy.isDue(last, last))
        assertFalse(AutoRefreshPolicy.isDue(last, last + 15 * 60_000L - 1))
        assertTrue(AutoRefreshPolicy.isDue(last, last + 15 * 60_000L))
    }

    @Test fun missingTimestampAndClockRollbackCannotSuppressRefresh() {
        assertTrue(AutoRefreshPolicy.isDue(null, 10_000_000L))
        assertTrue(AutoRefreshPolicy.isDue(10_000_000L, 9_999_999L))
    }
}
