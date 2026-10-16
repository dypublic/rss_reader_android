package com.codex.rssreader

import android.app.Application
import android.util.Log
import com.codex.rssreader.data.ReaderRepository
import com.codex.rssreader.diagnostics.LogLevel
import com.codex.rssreader.diagnostics.RuntimeLogStore
import com.codex.rssreader.diagnostics.BackgroundDiagnostics
import com.codex.rssreader.sync.BackgroundRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ReaderApplication : Application() {
    val runtimeLogs by lazy {
        RuntimeLogStore(File(noBackupFilesDir, "runtime-logs"), onWriteError = {
            Log.w("RSSRuntimeLog", "Cannot write runtime log: ${it.javaClass.simpleName}")
        })
    }
    // One Room instance and one refresh mutex for both foreground and background work.
    val repository by lazy { ReaderRepository(this, runtimeLogs) }
    val backgroundDiagnostics by lazy { BackgroundDiagnostics(this, runtimeLogs) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recordBackgroundDiagnostics(trigger: String) {
        applicationScope.launch { backgroundDiagnostics.captureAndRecord(trigger) }
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runCatching { BackgroundRefresh.schedule(this@ReaderApplication) }
                .onSuccess {
                    runtimeLogs.record("后台刷新任务已注册或确认", "周期 15 分钟；需要网络；已有任务保留原调度时间")
                    recordBackgroundDiagnostics("进程启动／注册后")
                }.onFailure {
                    runtimeLogs.record("后台刷新任务注册失败", it.javaClass.simpleName, LogLevel.ERROR)
                }
        }
    }
}
