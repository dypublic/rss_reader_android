package com.codex.rssreader

import com.codex.rssreader.diagnostics.LogLevel
import com.codex.rssreader.diagnostics.RuntimeLogStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

class RuntimeLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun persistsAndReadsNewestFirstAcrossRestart() {
        val directory = temporary.newFolder()
        val logs = RuntimeLogStore(directory)
        logs.record("开始", "刷新 1")
        logs.record("失败", "HTTP 503", LogLevel.ERROR)
        val entries = RuntimeLogStore(directory).readEntries()
        assertEquals(listOf("失败", "开始"), entries.map { it.event })
        assertEquals(LogLevel.ERROR, entries.first().level)
        assertEquals("HTTP 503", entries.first().details)
    }

    @Test fun longRunningUnicodeLogsNeverExceedThreeBoundedFiles() {
        val directory = temporary.newFolder()
        val logs = RuntimeLogStore(directory, maxFileBytes = 2048)
        repeat(1000) { logs.record("刷新 $it", "中文日志".repeat(2000)) }
        val files = directory.listFiles()!!
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 2048 })
        assertTrue(files.sumOf { it.length() } <= 3 * 2048)
        val entries = logs.readEntries()
        assertEquals("刷新 999", entries.first().event)
        assertTrue(entries.none { it.event == "刷新 0" })
        assertTrue(entries.all { it.details.length <= 512 })
    }

    @Test fun concurrentWritesRemainCompleteAndBounded() {
        val directory = temporary.newFolder()
        val logs = RuntimeLogStore(directory, maxFileBytes = 4096)
        val executor = Executors.newFixedThreadPool(4)
        try {
            (0 until 400).map { index -> executor.submit { logs.record("刷新 $index", "详情".repeat(100)) } }
                .forEach { it.get() }
        } finally { executor.shutdown() }
        val files = directory.listFiles()!!
        assertTrue(files.size <= 3)
        assertTrue(files.all { it.length() <= 4096 })
        assertEquals(files.sumOf { it.readLines().size }, logs.readEntries().size)
    }

    @Test fun corruptRecordDoesNotHideValidRecords() {
        val directory = temporary.newFolder()
        val logs = RuntimeLogStore(directory)
        logs.record("开始", "换行\n不会变成另一条记录\t")
        File(directory, "runtime-0.jsonl").appendText("not valid json\n")
        logs.record("结束")
        assertEquals(listOf("结束", "开始"), logs.readEntries().map { it.event })
        assertFalse(logs.readEntries().last().details.contains('\n'))
    }

    @Test fun unwritableLogPathDoesNotBreakRefreshCaller() {
        var writeErrors = 0
        val logs = RuntimeLogStore(temporary.newFile(), onWriteError = { writeErrors++ })
        logs.record("后台刷新")
        assertEquals(1, writeErrors)
        assertTrue(logs.readEntries().isEmpty())
    }

    @Test fun partialWriteDoesNotSwallowTheFirstRecordAfterRestart() {
        val directory = temporary.newFolder()
        val logs = RuntimeLogStore(directory)
        logs.record("之前的记录")
        File(directory, "runtime-0.jsonl").appendText("{\"timestamp\":123")
        val restarted = RuntimeLogStore(directory)
        restarted.record("重启后的记录")
        assertEquals(listOf("重启后的记录", "之前的记录"), restarted.readEntries().map { it.event })
    }
}
