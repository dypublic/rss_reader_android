package com.codex.rssreader.update

import android.app.Activity
import android.content.Context
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
import java.net.UnknownHostException

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
                mutableState.value = if (manual) UpdateUiState.Failed(readable(error)) else UpdateUiState.Idle
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
                mutableState.value = UpdateUiState.Failed(readable(error))
            }
        }
    }

    fun continueInstall(activity: Activity, ready: UpdateUiState.ReadyToInstall) {
        if (BuildConfig.DEBUG) {
            mutableState.value = UpdateUiState.Failed("调试版签名与正式版不同，不能覆盖安装正式更新。请在正式版本中验证升级。")
            return
        }
        try {
            if (UpdateInstaller.continueInstall(activity, ready.apk) == UpdateInstaller.Result.OPENED_INSTALLER) {
                mutableState.value = UpdateUiState.Idle
            }
        } catch (error: Throwable) {
            mutableState.value = UpdateUiState.Failed(readable(error))
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        mutableState.value = UpdateUiState.Idle
    }

    private fun readable(error: Throwable): String = when (error) {
        is UnknownHostException -> "无法连接更新服务器，请检查网络后重试"
        is SocketTimeoutException -> "连接更新服务器超时，请稍后重试"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "更新失败"
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
