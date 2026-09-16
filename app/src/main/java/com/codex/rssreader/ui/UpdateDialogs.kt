package com.codex.rssreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.rssreader.update.UpdateUiState

@Composable
fun UpdateDialogHost(
    state: UpdateUiState,
    isDebugBuild: Boolean,
    onDownload: (UpdateUiState.Available) -> Unit,
    onInstall: (UpdateUiState.ReadyToInstall) -> Unit,
    onOpenRelease: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${state.release.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("更新包 ${(state.release.apkSize / 1024f / 1024f).let { "%.1f MB".format(it) }}，下载后会验证文件摘要、应用 ID、版本号和签名证书。")
                    if (isDebugBuild) Text("当前是调试版，签名与正式版不同，不能覆盖升级；可以打开 Release 页面手动查看。", color = ReaderOrange)
                    state.release.notes.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = ReaderMuted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isDebugBuild) {
                        onOpenRelease(state.release.releaseUrl)
                        onDismiss()
                    } else onDownload(state)
                }) { Text(if (isDebugBuild) "查看 Release" else "下载更新") }
            },
            dismissButton = {
                if (!isDebugBuild) TextButton(onClick = {
                    onOpenRelease(state.release.releaseUrl)
                    onDismiss()
                }) { Text("查看详情") }
                TextButton(onClick = onDismiss) { Text("稍后") }
            },
        )
        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正在下载 ${state.release.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val progress = state.progress
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(progress?.let { "已完成 ${(it * 100).toInt()}%" } ?: "正在连接下载服务器…", color = ReaderMuted)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        is UpdateUiState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isDebugBuild) "更新包验证完成" else "可以安装更新") },
            text = {
                Text(if (isDebugBuild) {
                    "当前运行的是调试版，签名与正式版不同，Android 不允许覆盖安装。正式版中会继续进入系统安装界面。"
                } else {
                    "更新包已通过全部校验。首次使用时，Android 会要求允许本应用安装未知来源应用；随后仍需在系统安装界面确认。"
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isDebugBuild) {
                        onOpenRelease(state.release.releaseUrl)
                        onDismiss()
                    } else onInstall(state)
                }) { Text(if (isDebugBuild) "查看 Release" else "继续安装") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        is UpdateUiState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前版本 ${state.versionName}。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )
        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新失败") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        UpdateUiState.Idle, is UpdateUiState.Checking -> Unit
    }
}
