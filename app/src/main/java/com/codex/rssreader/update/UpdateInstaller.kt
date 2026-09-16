package com.codex.rssreader.update

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

object UpdateInstaller {
    enum class Result { OPENED_PERMISSION_SETTINGS, OPENED_INSTALLER }

    fun continueInstall(activity: Activity, apk: File): Result {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${activity.packageName}".toUri(),
            ))
            return Result.OPENED_PERMISSION_SETTINGS
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return Result.OPENED_INSTALLER
    }
}
