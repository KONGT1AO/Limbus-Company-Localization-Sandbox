package com.example.limbuszhcn.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.limbuszhcn.PersistentDiagnosticLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal sealed class LauncherUpdateState {
    data object Idle : LauncherUpdateState()
    data object Checking : LauncherUpdateState()
    data class UpToDate(val checkedAtSummary: String) : LauncherUpdateState()
    data class Available(val release: LauncherUpdateRelease) : LauncherUpdateState()
    data class Downloading(
        val release: LauncherUpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : LauncherUpdateState()

    data class ReadyToInstall(
        val release: LauncherUpdateRelease,
        val apkFile: File
    ) : LauncherUpdateState()

    data class Error(
        val message: String,
        val release: LauncherUpdateRelease? = null
    ) : LauncherUpdateState()
}

/**
 * 负责直连 GitHub Releases 检查更新、应用内流式下载、四重安全校验并调起系统覆盖安装。
 */
internal class LauncherSelfUpdateManager(
    private val context: Context,
    private val preferences: SharedPreferences
) {
    private val updateCacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun acknowledgedUpdateKey(): String? = preferences.getString(KEY_ACKNOWLEDGED_UPDATE, null)

    fun acknowledgeUpdate(updateKey: String) {
        preferences.edit().putString(KEY_ACKNOWLEDGED_UPDATE, updateKey).apply()
    }

    fun shouldRunAutoCheck(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastMs = preferences.getLong(KEY_LAST_AUTO_CHECK_MS, 0L)
        return LauncherSelfUpdatePolicy.shouldRunAutoCheck(nowMs, lastMs)
    }

    fun markAutoCheckCompleted(nowMs: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_LAST_AUTO_CHECK_MS, nowMs).apply()
    }

    /** 清理已安装或过期的临时缓存安装包，避免占用存储。 */
    fun cleanupStaleUpdates(currentVersionCode: Long) {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part")) {
                file.delete()
                return@forEach
            }
            if (file.name.endsWith(".apk", ignoreCase = true)) {
                val archive = readArchiveInfo(file)
                val archiveCode = archive?.let(::extractVersionCode) ?: -1L
                if (archive == null || archive.packageName != context.packageName || archiveCode <= currentVersionCode) {
                    file.delete()
                }
            }
        }
    }

    /**
     * 检查官方仓库正式版更新；API 遭遇限流时自动回退解析 Releases 官方页面。
     */
    fun fetchLatestRelease(): LauncherUpdateRelease {
        val apiResponse = runCatching {
            fetchText(
                url = LauncherSelfUpdatePolicy.RELEASE_API_URL,
                accept = "application/vnd.github+json",
                allowRedirect = { _, _ -> false }
            )
        }
        apiResponse.getOrNull()?.let { res ->
            if (res.code == HttpURLConnection.HTTP_OK) {
                return LauncherSelfUpdatePolicy.parseApiRelease(res.body)
            }
            if (res.code != 403 && res.code != 429) {
                throw IOException("GitHub API 返回状态码 ${res.code}，请稍后重试")
            }
            PersistentDiagnosticLog.warn(
                TAG,
                "GitHub API rate limited (${res.code}), falling back to official release page"
            )
        } ?: run {
            val err = apiResponse.exceptionOrNull()
            PersistentDiagnosticLog.warn(TAG, "GitHub API request failed, trying release page fallback", err)
        }

        val pageRes = fetchText(
            url = LauncherSelfUpdatePolicy.RELEASE_LATEST_PAGE_URL,
            accept = "text/html",
            allowRedirect = LauncherSelfUpdatePolicy::allowsReleasePageRedirect
        )
        if (pageRes.code != HttpURLConnection.HTTP_OK) {
            throw IOException("无法访问 GitHub Releases 页面（HTTP ${pageRes.code}），请检查网络连接")
        }
        val tag = LauncherSelfUpdatePolicy.extractTagFromReleaseUrl(pageRes.finalUrl)
        val assetsRes = fetchText(
            url = LauncherSelfUpdatePolicy.expandedAssetsUrl(tag),
            accept = "text/html",
            allowRedirect = { _, _ -> false }
        )
        if (assetsRes.code != HttpURLConnection.HTTP_OK) {
            throw IOException("无法获取安装包列表（HTTP ${assetsRes.code}）")
        }
        return LauncherSelfUpdatePolicy.parseReleasePageFallback(
            resolvedTag = tag,
            releasePageHtml = pageRes.body,
            expandedAssetsHtml = assetsRes.body
        )
    }

    /**
     * 应用内流式下载安装包并执行四重校验（大小、SHA-256、包名与版本号、同签名证书一致）。
     */
    fun downloadAndVerify(
        release: LauncherUpdateRelease,
        currentVersionCode: Long,
        cancelled: AtomicBoolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val dir = updateCacheDir
        val finalFile = File(dir, release.assetName)
        if (finalFile.isFile) {
            val reused = runCatching {
                verifyDownloadedApk(finalFile, release, currentVersionCode)
                finalFile
            }.getOrNull()
            if (reused != null) {
                return reused
            }
            finalFile.delete()
        }

        val partFile = File(dir, "${release.assetName}.part")
        partFile.delete()

        val conn = openConnectionFollowingRedirects(
            initialUrl = release.assetUrl,
            accept = "application/octet-stream",
            allowRedirect = LauncherSelfUpdatePolicy::allowsAssetRedirect
        )
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("下载安装包失败（HTTP $code）")
            }
            val headerLength = conn.contentLengthLong.takeIf { it > 0L }
            val expectedTotal = release.sizeBytes ?: headerLength ?: -1L
            if (expectedTotal > LauncherSelfUpdatePolicy.MAX_APK_BYTES) {
                throw IOException("远端安装包体积超出安全限制")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastNotifyMs = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) {
                            throw CancelledDownloadException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        downloaded += read
                        if (downloaded > LauncherSelfUpdatePolicy.MAX_APK_BYTES) {
                            throw IOException("下载内容超出安全体积上限")
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= 120L || (expectedTotal > 0 && downloaded >= expectedTotal)) {
                            lastNotifyMs = now
                            onProgress(downloaded, expectedTotal)
                        }
                    }
                    output.flush()
                }
            }

            if (downloaded <= 0L) {
                throw IOException("下载的安装包为空")
            }
            if (release.sizeBytes != null && downloaded != release.sizeBytes) {
                throw IOException("安装包大小校验失败（期望 ${release.sizeBytes} 字节，实际 $downloaded 字节）")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!release.sha256.isNullOrBlank() && !actualSha.equals(release.sha256, ignoreCase = true)) {
                throw IOException("安装包 SHA-256 校验失败，已自动丢弃")
            }

            verifyApkPackageAndSignature(partFile, currentVersionCode)

            // 清理其它旧缓存 APK 并原子落盘
            dir.listFiles()?.forEach { existing ->
                if (existing != partFile && existing.name.endsWith(".apk", ignoreCase = true)) {
                    existing.delete()
                }
            }
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            PersistentDiagnosticLog.info(
                TAG,
                "Verified launcher update apk=${finalFile.name} size=$downloaded sha256=$actualSha"
            )
            return finalFile
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        } finally {
            conn.disconnect()
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun buildUnknownSourcesSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun buildInstallApkIntent(apkFile: File): Intent {
        val authority = "${context.packageName}.launcherupdate.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun verifyDownloadedApk(
        apkFile: File,
        release: LauncherUpdateRelease,
        currentVersionCode: Long
    ) {
        val size = apkFile.length()
        if (size <= 0L || (release.sizeBytes != null && size != release.sizeBytes)) {
            throw IOException("缓存安装包大小不匹配")
        }
        if (!release.sha256.isNullOrBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(release.sha256, ignoreCase = true)) {
                throw IOException("缓存安装包 SHA-256 不匹配")
            }
        }
        verifyApkPackageAndSignature(apkFile, currentVersionCode)
    }

    private fun verifyApkPackageAndSignature(apkFile: File, currentVersionCode: Long) {
        val archiveInfo = readArchiveInfo(apkFile)
            ?: throw IOException("无法解析下载的安装包，文件可能已损坏")
        if (archiveInfo.packageName != LauncherSelfUpdatePolicy.EXPECTED_PACKAGE_NAME
            || archiveInfo.packageName != context.packageName
        ) {
            throw IOException("安装包包名不匹配（${archiveInfo.packageName}），拒绝安装以保护容器数据")
        }
        val archiveVersionCode = extractVersionCode(archiveInfo)
        if (archiveVersionCode <= currentVersionCode) {
            throw IOException("下载的安装包版本号（$archiveVersionCode）不高于当前版本（$currentVersionCode）")
        }
        val installedDigests = readInstalledSigningDigests()
        val archiveDigests = extractSigningDigests(archiveInfo)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty() || installedDigests != archiveDigests) {
            throw IOException("安装包签名证书与当前应用不一致，拒绝覆盖安装以保护容器数据")
        }
    }

    @Suppress("DEPRECATION")
    private fun readArchiveInfo(apkFile: File): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalledSigningDigests(): Set<String> {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        return extractSigningDigests(info)
    }

    @Suppress("DEPRECATION")
    private fun extractSigningDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun extractVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private data class HttpTextResponse(val code: Int, val finalUrl: String, val body: String)

    private fun fetchText(
        url: String,
        accept: String,
        allowRedirect: (original: String, target: String) -> Boolean
    ): HttpTextResponse {
        val conn = openConnectionFollowingRedirects(url, accept, allowRedirect)
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpTextResponse(code = code, finalUrl = conn.url.toString(), body = body)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnectionFollowingRedirects(
        initialUrl: String,
        accept: String,
        allowRedirect: (original: String, target: String) -> Boolean
    ): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { hop ->
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || hop >= MAX_REDIRECTS) {
                    throw IOException("更新请求重定向异常")
                }
                val resolved = URI(currentUrl).resolve(location).toASCIIString()
                if (!allowRedirect(initialUrl, resolved)) {
                    throw IOException("更新下载地址超出官方安全域名白名单")
                }
                currentUrl = resolved
                return@repeat
            }
            return conn
        }
        throw IOException("更新请求重定向次数过多")
    }

    internal class CancelledDownloadException : IOException("已取消下载")

    companion object {
        private const val TAG = "LauncherSelfUpdate"
        private const val CACHE_DIR_NAME = "launcher_updates"
        private const val KEY_ACKNOWLEDGED_UPDATE = "launcher_update_acknowledged_key"
        private const val KEY_LAST_AUTO_CHECK_MS = "launcher_update_last_auto_check_ms"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "LimbusLocalizer-Android-Updater"
    }
}
