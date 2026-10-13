package com.codex.rssreader.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

enum class LogLevel { INFO, WARNING, ERROR }

data class RuntimeLogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val event: String,
    val details: String,
)

/** Three bounded UTF-8 files, shared by the UI and worker in the same process. */
class RuntimeLogStore(
    private val directory: File,
    private val maxFileBytes: Int = 128 * 1024,
    private val onWriteError: (Throwable) -> Unit = {},
) {
    init { require(maxFileBytes >= 2048) }

    private val revision = MutableStateFlow(0L)
    val entries = revision.map { readEntries() }.flowOn(Dispatchers.IO)

    @Synchronized
    fun record(event: String, details: String = "", level: LogLevel = LogLevel.INFO) {
        // Logging failures must never prevent a refresh or crash the application.
        runCatching {
            val entry = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("level", level.name)
                .put("event", singleLine(event, 80))
                .put("details", singleLine(details, 512))
            val bytes = "$entry\n".toByteArray(Charsets.UTF_8)
            check(bytes.size <= maxFileBytes)
            check(directory.isDirectory || directory.mkdirs())
            // A process can be killed midway through a write. Separate its partial line
            // so it cannot swallow the first valid record after the next start.
            val current = file(0)
            val needsSeparator = current.length() > 0 && RandomAccessFile(current, "r").use {
                it.seek(it.length() - 1)
                it.read() != '\n'.code
            }
            if (current.length() + bytes.size + (if (needsSeparator) 1 else 0) > maxFileBytes) {
                rotate()
            } else if (needsSeparator) {
                current.appendText("\n", Charsets.UTF_8)
            }
            current.appendBytes(bytes)
            revision.update { it + 1 }
        }.onFailure(onWriteError)
    }

    @Synchronized
    fun readEntries(): List<RuntimeLogEntry> = (0..2).flatMap { index ->
        val lines = runCatching { file(index).readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        lines.asReversed().mapNotNull { line ->
            runCatching {
                val entry = JSONObject(line)
                RuntimeLogEntry(
                    entry.getLong("timestamp"),
                    LogLevel.valueOf(entry.getString("level")),
                    entry.getString("event"),
                    entry.getString("details"),
                )
            }.getOrNull()
        }
    }

    private fun file(index: Int) = File(directory, "runtime-$index.jsonl")

    private fun rotate() {
        if (file(2).exists()) check(file(2).delete())
        if (file(1).exists()) check(file(1).renameTo(file(2)))
        if (file(0).exists()) check(file(0).renameTo(file(1)))
    }

    private fun singleLine(value: String, maxChars: Int) =
        value.take(maxChars).replace(Regex("[\\p{Cntrl}]"), " ")
}
