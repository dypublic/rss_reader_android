package com.codex.rssreader.sync

import android.content.Context
import android.os.SystemClock
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codex.rssreader.ReaderApplication
import com.codex.rssreader.diagnostics.LogLevel
import com.codex.rssreader.diagnostics.stopReasonLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RssRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as ReaderApplication
        val startedAt = SystemClock.elapsedRealtime()
        app.runtimeLogs.record("后台任务开始", "任务 $id；第 ${runAttemptCount + 1} 次尝试")
        try {
            app.backgroundDiagnostics.captureAndRecord("任务开始")
            app.repository.clearExpired()
            val summary = app.repository.refreshAll(RefreshTrigger.BACKGROUND)
            val retry = summary.shouldRetry(runAttemptCount)
            val outcome = if (retry) "等待重试" else if (summary.failed > 0 || summary.skipped) "本轮结束，等待下次周期" else "完成"
            app.runtimeLogs.record(
                "后台任务结束",
                "耗时 ${SystemClock.elapsedRealtime() - startedAt} ms；$outcome",
                if (summary.failed > 0 || summary.skipped) LogLevel.WARNING else LogLevel.INFO,
            )
            app.backgroundDiagnostics.captureAndRecord("任务返回结果前")
            if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            val reason = if (Build.VERSION.SDK_INT >= 31) "${stopReasonLabel(stopReason)} ($stopReason)" else "系统未提供"
            app.runtimeLogs.record("后台任务被系统中止", "停止原因 $reason；耗时 ${SystemClock.elapsedRealtime() - startedAt} ms", LogLevel.WARNING)
            throw cancelled
        } catch (error: Exception) {
            val retry = runAttemptCount < 2
            app.runtimeLogs.record("后台任务异常", "${error.javaClass.simpleName}；${if (retry) "等待重试" else "等待下次周期"}", LogLevel.ERROR)
            if (retry) Result.retry() else Result.success()
        }
    }
}
