package com.codex.rssreader.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.codex.rssreader.BuildConfig
import java.io.File
import java.security.MessageDigest

class UpdateVerifier(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(apk: File, release: ReleaseInfo) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("无法读取更新包")
        require(info.packageName == context.packageName) { "更新包的应用 ID 不匹配" }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        require(versionCode > BuildConfig.VERSION_CODE.toLong()) { "更新包的 versionCode 不高于当前版本" }
        require(info.versionName == release.versionName) { "更新包版本与 Release 不一致" }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        require(signatures.size == 1) { "更新包签名者数量异常" }
        val certificate = MessageDigest.getInstance("SHA-256")
            .digest(signatures.single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(certificate == OFFICIAL_CERTIFICATE_SHA256) { "更新包签名证书不匹配" }
    }

    companion object {
        const val OFFICIAL_CERTIFICATE_SHA256 = "1d5719d4426f0b1d1faedb5fb5aef75884024a2d379caee9edfd950c401aaf87"
    }
}
