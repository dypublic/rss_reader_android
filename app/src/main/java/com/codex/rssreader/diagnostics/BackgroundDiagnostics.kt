package com.codex.rssreader.diagnostics

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.codex.rssreader.BuildConfig
import com.codex.rssreader.sync.BackgroundRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class BackgroundSnapshot(val capturedAt: Long, val lines: List<String>) {
    fun asText() = "采样时间 ${diagnosticTime(capturedAt)}\n" + lines.joinToString("\n")
}

/** Read-only, event-driven diagnostics. Never schedules work or wakes the device. */
class BackgroundDiagnostics(private val context: Context, private val logs: RuntimeLogStore) {
    suspend fun captureAndRecord(trigger: String): BackgroundSnapshot = withContext(Dispatchers.IO) {
        val snapshot = BackgroundSnapshot(System.currentTimeMillis(), buildList {
            add("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · App ${BuildConfig.VERSION_NAME}")
            add(readSection("电源状态") {
                val power = context.getSystemService(PowerManager::class.java)
                val lightIdle = if (Build.VERSION.SDK_INT >= 33) "；轻度 Doze ${yesNo(power.isDeviceLightIdleMode)}" else ""
                "亮屏交互 ${yesNo(power.isInteractive)}；省电模式 ${yesNo(power.isPowerSaveMode)}；" +
                    "深度 Doze ${yesNo(power.isDeviceIdleMode)}$lightIdle；电池优化豁免 ${yesNo(power.isIgnoringBatteryOptimizations(context.packageName))}"
            })
            add(readSection("后台状态") {
                val restricted = if (Build.VERSION.SDK_INT >= 28) {
                    yesNo(context.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
                } else "系统不支持查询"
                val bucket = if (Build.VERSION.SDK_INT >= 28) {
                    val value = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
                    "${standbyBucketLabel(value)} ($value)"
                } else "系统不支持查询"
                "系统后台限制 $restricted；待机分组 $bucket"
            })
            add(readSection("网络状态") {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                "网络连接 ${yesNo(caps != null)}；互联网能力 ${yesNo(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)}；" +
                    "网络已验证 ${yesNo(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)}"
            })
            add(readSection("周期任务") {
                val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackgroundRefresh.WORK_NAME)
                    .get(3, TimeUnit.SECONDS).filter { !it.state.isFinished }
                if (work.isEmpty()) "未查到有效周期任务" else work.joinToString("\n") { info ->
                    "任务 ${info.id}；${workStateLabel(info.state)}；已尝试 ${info.runAttemptCount} 次；" +
                        "最早可运行 ${if (info.state == WorkInfo.State.ENQUEUED && info.nextScheduleTimeMillis != Long.MAX_VALUE) diagnosticTime(info.nextScheduleTimeMillis) else "暂无"}；" +
                        "上次停止 ${stopReasonLabel(info.stopReason)}"
                }
            })
            addAll(schedulerLines())
        })
        // One bounded entry per line keeps every reason readable within the existing size cap.
        snapshot.lines.forEach { logs.record("后台诊断 · $trigger", it) }
        snapshot
    }

    private fun schedulerLines(): List<String> = try {
        val base = context.getSystemService(JobScheduler::class.java)
        val scheduler = if (Build.VERSION.SDK_INT >= 34) base.forNamespace("androidx.work.systemjobscheduler") else base
        val jobs = scheduler.allPendingJobs.filter {
            it.service.packageName == context.packageName &&
                it.service.className == "androidx.work.impl.background.systemjob.SystemJobService"
        }
        buildList {
            if (jobs.isEmpty()) add("系统调度：未查到已登记任务（运行完成与重新登记之间可能暂时为空）")
            for (job in jobs) {
                val reasons = when {
                    Build.VERSION.SDK_INT >= 36 -> scheduler.getPendingJobReasons(job.id).toList()
                    Build.VERSION.SDK_INT >= 34 -> listOf(scheduler.getPendingJobReason(job.id))
                    else -> emptyList()
                }
                add("系统任务 ${job.id}：${if (reasons.isEmpty()) "此系统不支持查询等待原因" else reasons.joinToString("、", transform = ::pendingReasonLabel)}")
                if (Build.VERSION.SDK_INT >= 36) {
                    scheduler.getPendingJobReasonsHistory(job.id).takeLast(4).forEach { history ->
                        add("系统等待历史 ${diagnosticTime(history.timestampMillis)}：${history.pendingJobReasons.joinToString("、", transform = ::pendingReasonLabel)}")
                    }
                }
            }
        }
    } catch (error: Exception) {
        listOf("系统调度：读取失败 ${error.javaClass.simpleName}")
    }

    private fun readSection(name: String, block: () -> String): String = try {
        "$name：${block()}"
    } catch (error: Exception) {
        "$name：读取失败 ${error.javaClass.simpleName}"
    }
}

private fun yesNo(value: Boolean) = if (value) "是" else "否"

internal fun standbyBucketLabel(value: Int) = when (value) {
    5 -> "豁免"
    10 -> "活跃"
    20 -> "工作集"
    30 -> "常用"
    40 -> "很少使用"
    45 -> "受限"
    50 -> "从未使用"
    else -> "未知"
}

internal fun workStateLabel(state: WorkInfo.State) = when (state) {
    WorkInfo.State.ENQUEUED -> "等待运行"
    WorkInfo.State.RUNNING -> "正在运行"
    WorkInfo.State.SUCCEEDED -> "已完成"
    WorkInfo.State.FAILED -> "失败"
    WorkInfo.State.BLOCKED -> "等待前置任务"
    WorkInfo.State.CANCELLED -> "已取消"
}

internal fun stopReasonLabel(reason: Int) = when (reason) {
    WorkInfo.STOP_REASON_NOT_STOPPED -> "无中止记录"
    WorkInfo.STOP_REASON_UNKNOWN -> "原因未知"
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "应用取消"
    WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "前台服务超时"
    0 -> "未分类"
    WorkInfo.STOP_REASON_PREEMPT -> "系统抢占"
    WorkInfo.STOP_REASON_TIMEOUT -> "执行超时"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "设备状态（如 Doze／省电）"
    WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "电量不足"
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "充电条件不满足"
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "网络条件不满足"
    WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "设备空闲条件不满足"
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "存储空间不足"
    WorkInfo.STOP_REASON_QUOTA -> "配额不足"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "后台限制"
    WorkInfo.STOP_REASON_APP_STANDBY -> "待机分组限制"
    WorkInfo.STOP_REASON_USER -> "用户停止"
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "系统处理"
    WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "预估启动时间变化"
    else -> "未知原因 ($reason)"
}

internal fun pendingReasonLabel(reason: Int): String = when (reason) {
    JobScheduler.PENDING_JOB_REASON_UNDEFINED -> "系统未给出明确原因"
    JobScheduler.PENDING_JOB_REASON_EXECUTING -> "正在执行"
    JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID -> "任务已移除"
    JobScheduler.PENDING_JOB_REASON_APP -> "应用状态"
    JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "待机分组限制"
    JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "后台限制"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "电量不足"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "等待充电"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "网络条件不满足"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER -> "等待内容变化"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> "等待设备空闲"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY -> "尚未到最早运行时间"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH -> "预取条件"
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "存储空间不足"
    JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "设备状态（如 Doze／省电）"
    JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION -> "系统合并调度／优化"
    JobScheduler.PENDING_JOB_REASON_QUOTA -> "配额不足"
    JobScheduler.PENDING_JOB_REASON_USER -> "用户状态"
    else -> "系统原因 ($reason)"
}

internal fun diagnosticTime(millis: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
