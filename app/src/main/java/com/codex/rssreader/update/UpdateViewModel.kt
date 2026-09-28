package com.codex.rssreader.update

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.rssreader.BuildConfig
import com.codex.rssreader.data.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant

class UpdateViewModel(
    context: Context,
    private val preferences: ReaderPreferences,
) : ViewModel() {
    private val client = UpdateClient(context.applicationContext)
    private val verifier = UpdateVerifier(context.applicationContext)
    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var autoCheckJob: Job? = null

    fun autoCheck() {
        if (autoCheckJob?.isActive == true) return
        autoCheckJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val last = preferences.lastUpdateCheckAt.first()
            if (now - last < AUTO_CHECK_INTERVAL_MS) return@launch
            preferences.setLastUpdateCheckAt(now)
            check(manual = false)
        }
    }

    fun check(manual: Boolean = true) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            mutableState.value = UpdateUiState.Checking(manual)
            try {
                val release = client.latestRelease()
                mutableState.value = if (VersionOrder.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                    UpdateUiState.Available(release)
                } else if (manual) {
                    UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                } else {
                    UpdateUiState.Idle
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = UpdateUiState.Idle
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = if (manual) failure("检查更新", error) else UpdateUiState.Idle
            }
        }
    }

    fun download(release: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            mutableState.value = UpdateUiState.Downloading(release, null)
            try {
                val apk = client.download(release) { progress ->
                    mutableState.value = UpdateUiState.Downloading(release, progress)
                }
                verifier.verify(apk, release)
                mutableState.value = UpdateUiState.ReadyToInstall(release, apk)
            } catch (cancelled: CancellationException) {
                mutableState.value = UpdateUiState.Idle
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = failure("下载并校验更新", error, release)
            }
        }
    }

    fun continueInstall(activity: Activity, ready: UpdateUiState.ReadyToInstall) {
        if (BuildConfig.DEBUG) {
            val error = IllegalStateException("调试版签名与正式版不同，不能覆盖安装正式更新。请在正式版本中验证升级。")
            mutableState.value = failure("打开系统安装界面", error, ready.release)
            return
        }
        try {
            if (UpdateInstaller.continueInstall(activity, ready.apk) == UpdateInstaller.Result.OPENED_INSTALLER) {
                mutableState.value = UpdateUiState.Idle
            }
        } catch (error: Throwable) {
            mutableState.value = failure("打开系统安装界面", error, ready.release)
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        mutableState.value = UpdateUiState.Idle
    }

    private fun failure(stage: String, error: Throwable, release: ReleaseInfo? = null): UpdateUiState.Failed {
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        val message = when {
            causes.any { it is UnknownHostException } -> "无法解析更新服务器，请检查网络或 DNS 后重试"
            causes.any { it is SocketTimeoutException } -> "连接更新服务器超时，请稍后重试"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "更新失败"
        }
        val diagnostics = buildString {
            appendLine("RSS Reader 更新错误")
            appendLine("时间: ${Instant.now()}")
            appendLine("阶段: $stage")
            appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("构建类型: ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            release?.let {
                appendLine("目标版本: ${it.versionName} (${it.tagName})")
                appendLine("APK: ${it.apkName}")
                appendLine("下载域名: ${runCatching { URI(it.apkUrl).host }.getOrNull() ?: "未知"}")
            }
            causes.forEachIndexed { index, cause ->
                appendLine("异常${index + 1}: ${cause.javaClass.name}: ${cause.message.orEmpty()}")
            }
            appendLine("堆栈:")
            error.stackTrace.take(8).forEach { appendLine("  at $it") }
        }.trimEnd()
        return UpdateUiState.Failed(message, diagnostics)
    }

    class Factory(
        private val context: Context,
        private val preferences: ReaderPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UpdateViewModel(context, preferences) as T
    }

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60_000L
    }
}
