package com.codex.rssreader.sync

enum class RefreshTrigger(val label: String) {
    APP_START("打开应用"), MANUAL("手动刷新"), BACKGROUND("后台刷新"),
}

data class RefreshSummary(
    val succeeded: Int = 0,
    val failed: Int = 0,
    val newArticles: Int = 0,
    val skipped: Boolean = false,
) {
    // At most three attempts in one cycle; permanently broken feeds wait for the next cycle.
    fun shouldRetry(runAttemptCount: Int): Boolean = (failed > 0 || skipped) && runAttemptCount < 2
}
