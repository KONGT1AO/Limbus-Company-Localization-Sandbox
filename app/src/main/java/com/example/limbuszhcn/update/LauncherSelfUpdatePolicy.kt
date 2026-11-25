package com.example.limbuszhcn.update

import org.json.JSONObject
import java.math.BigInteger
import java.net.URI

/**
 * 已通过来源、版本与资产校验的启动器正式发布信息。
 */
internal data class LauncherUpdateRelease(
    val tag: String,
    val versionName: String,
    val versionCodeHint: Long?,
    val assetName: String,
    val assetUrl: String,
    val sizeBytes: Long?,
    val sha256: String?,
    val releaseNotesSummary: String
) {
    /** 用于“看过即消”的唯一提醒键，确保用户忽略后不会对同一版本反复亮红点。 */
    val updateKey: String
        get() = buildString {
            append(tag)
            append('#')
            when {
                versionCodeHint != null -> append(versionCodeHint)
                !sha256.isNullOrBlank() -> append(sha256.take(12))
                else -> append(assetName)
            }
        }

    /** 面向界面展示的友好版本标签。 */
    val displayLabel: String
        get() = if (versionCodeHint != null) {
            "v$versionName (build $versionCodeHint)"
        } else {
            "v$versionName"
        }
}

/**
 * 约束启动器应用内更新的官方仓库边界、版本比较与安全重定向规则（纯 JVM 可单测）。
 */
internal object LauncherSelfUpdatePolicy {
    const val REPO_OWNER = "KONGT1AO"
    const val REPO_NAME = "Limbus-Company-Localization-Sandbox"
    const val RELEASE_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASE_LATEST_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val EXPECTED_PACKAGE_NAME = "io.limbuspe.box"
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val AUTO_CHECK_COOLDOWN_MS = 6L * 60L * 60L * 1000L

    private const val RELEASE_ROOT = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
    private val versionPattern = Regex("^v?([0-9]+(?:\\.[0-9]+)*)$")
    private val apkAssetNamePattern = Regex("^LimbusLocalizer-v[0-9]+(?:\\.[0-9]+)*-arm64-v8a\\.apk$")
    private val digestPattern = Regex("^sha256:([0-9a-fA-F]{64})$")
    private val bodyVersionCodePattern = Regex(
        "(?:versionCode\\s*=\\s*|\\bbuild\\s+)([0-9]{2,9})\\b",
        RegexOption.IGNORE_CASE
    )
    private val bodySha256Pattern = Regex(
        "SHA-?256[^0-9a-fA-F]{0,16}([0-9a-fA-F]{64})\\b",
        RegexOption.IGNORE_CASE
    )
    private val expandedAssetsHrefPattern = Regex(
        "href=\"(/$REPO_OWNER/$REPO_NAME/releases/download/([^\"/]+)/([^\"/]+\\.apk))\"",
        RegexOption.IGNORE_CASE
    )
    private val allowedAssetCdnHosts = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )

    /**
     * 解析 GitHub `/releases/latest` JSON，拒绝草稿、预发布或越出本仓库范围的资产链接。
     */
    fun parseApiRelease(json: String): LauncherUpdateRelease {
        try {
            val root = JSONObject(json)
            require(root.opt("draft") == false && root.opt("prerelease") == false) {
                "仅接受正式版 Release，忽略草稿与预发布版本"
            }
            val tag = requiredString(root, "tag_name")
            val versionName = parsePureVersionName(tag)
            if (root.has("html_url")) {
                require(requiredString(root, "html_url") == "$RELEASE_ROOT/tag/$tag") {
                    "Release 页面地址不属于官方仓库"
                }
            }
            val body = root.optString("body", "")
            val assets = root.getJSONArray("assets")
            val apkAssets = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { requiredString(it, "name").endsWith(".apk", ignoreCase = true) }
            require(apkAssets.size == 1) { "正式版 Release 必须包含且仅包含一个安装包 APK" }

            val asset = apkAssets.single()
            val assetName = requiredString(asset, "name")
            require(apkAssetNamePattern.matches(assetName)) {
                "安装包命名不符合官方规范: $assetName"
            }
            val assetUrl = validateAssetUrl(requiredString(asset, "browser_download_url"), tag, assetName)
            val sizeValue = asset.opt("size")
            require(sizeValue is Number && sizeValue.toString().matches(Regex("^[0-9]+$"))) {
                "安装包字节大小格式无效"
            }
            val sizeBytes = sizeValue.toString().toLongOrNull()
            require(sizeBytes != null && sizeBytes > 0L && sizeBytes < MAX_APK_BYTES) {
                "安装包体积超出安全限制"
            }
            val apiDigest = when (val raw = asset.opt("digest")) {
                null, JSONObject.NULL -> null
                is String -> raw.takeIf { it.isNotBlank() }?.let {
                    digestPattern.matchEntire(it)?.groupValues?.get(1)?.lowercase()
                        ?: throw IllegalArgumentException("安装包 SHA-256 摘要格式无效")
                }
                else -> throw IllegalArgumentException("安装包摘要字段类型无效")
            }
            val sha256 = apiDigest ?: extractSha256FromBody(body)
            return LauncherUpdateRelease(
                tag = tag,
                versionName = versionName,
                versionCodeHint = extractVersionCodeFromBody(body),
                assetName = assetName,
                assetUrl = assetUrl,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                releaseNotesSummary = summarizeReleaseBody(body)
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("无法解析 GitHub Release 更新数据", error)
        }
    }

    /**
     * API 限流回退：从已验证的 `/releases/tag/<tag>` 页面与 `expanded_assets/<tag>` 片段解析安装包信息。
     */
    fun parseReleasePageFallback(
        resolvedTag: String,
        releasePageHtml: String,
        expandedAssetsHtml: String
    ): LauncherUpdateRelease {
        val versionName = parsePureVersionName(resolvedTag)
        val matches = expandedAssetsHrefPattern.findAll(expandedAssetsHtml).toList()
        require(matches.size == 1) { "Release 资产列表中未找到唯一的官方安装包" }
        val match = matches.single()
        val rawPath = match.groupValues[1]
        val linkTag = match.groupValues[2]
        val assetName = match.groupValues[3]
        require(linkTag == resolvedTag) { "资产链接标签与当前 Release 标签不一致" }
        require(apkAssetNamePattern.matches(assetName)) { "安装包命名不符合官方规范: $assetName" }
        val assetUrl = validateAssetUrl("https://github.com$rawPath", resolvedTag, assetName)
        return LauncherUpdateRelease(
            tag = resolvedTag,
            versionName = versionName,
            versionCodeHint = extractVersionCodeFromBody(releasePageHtml),
            assetName = assetName,
            assetUrl = assetUrl,
            sizeBytes = null,
            sha256 = extractSha256FromBody(releasePageHtml),
            releaseNotesSummary = "检测到官方正式版更新，可在应用内直接下载并覆盖安装。"
        )
    }

    /**
     * 从 `/releases/latest` 跳转后的最终 URL 提取正式版 tag。
     */
    fun extractTagFromReleaseUrl(url: String): String {
        val uri = parseHttpsUri(url)
        require(uri.host.equals("github.com", ignoreCase = true)) { "Release 地址主机无效" }
        val prefix = "/$REPO_OWNER/$REPO_NAME/releases/tag/"
        require(uri.rawPath != null && uri.rawPath.startsWith(prefix)) { "未重定向到本仓库的正式版页面" }
        val tag = uri.rawPath.removePrefix(prefix).trimEnd('/')
        require(!tag.contains('/')) { "Release 标签路径非法" }
        parsePureVersionName(tag)
        return tag
    }

    /**
     * 构造与官方页面一致的 `expanded_assets` 片段地址。
     */
    fun expandedAssetsUrl(tag: String): String {
        parsePureVersionName(tag)
        return "https://github.com/$REPO_OWNER/$REPO_NAME/releases/expanded_assets/$tag"
    }

    /**
     * 判断远端正式版是否严格新于当前安装版本：
     * 1) 优先按点分数字版本比较（如 `1.0.11 > 1.0.10`）；
     * 2) 若版本名相同，且远端包含更高 `versionCodeHint`（同版本热修），同样判定有更新。
     */
    fun isUpdateAvailable(
        release: LauncherUpdateRelease,
        currentVersionName: String,
        currentVersionCode: Long
    ): Boolean {
        val cmp = compareVersions(release.versionName, currentVersionName)
        if (cmp > 0) return true
        if (cmp < 0) return false
        val remoteCode = release.versionCodeHint ?: return false
        return remoteCode > currentVersionCode
    }

    /**
     * 克制红点判定：仅当存在可用更新且用户尚未在设置页查看/知晓该版本时返回真。
     */
    fun shouldShowReminderDot(
        release: LauncherUpdateRelease?,
        currentVersionName: String,
        currentVersionCode: Long,
        acknowledgedUpdateKey: String?
    ): Boolean {
        if (release == null) return false
        if (!isUpdateAvailable(release, currentVersionName, currentVersionCode)) return false
        return release.updateKey != acknowledgedUpdateKey
    }

    /**
     * 仅允许冷却时间已过的自动静默检查，避免频繁切回前台重复请求网络。
     */
    fun shouldRunAutoCheck(nowMs: Long, lastCheckMs: Long): Boolean {
        if (lastCheckMs <= 0L) return true
        return nowMs - lastCheckMs >= AUTO_CHECK_COOLDOWN_MS
    }

    /**
     * 严格校验版本名称大小（按整数段比较，避免 `1.10 < 1.9` 字符串误判）。
     */
    fun compareVersions(left: String, right: String): Int {
        val lParts = parsePureVersionName(left).split('.').map { BigInteger(it) }
        val rParts = parsePureVersionName(right).split('.').map { BigInteger(it) }
        val count = maxOf(lParts.size, rParts.size)
        for (i in 0 until count) {
            val c = (lParts.getOrNull(i) ?: BigInteger.ZERO)
                .compareTo(rParts.getOrNull(i) ?: BigInteger.ZERO)
            if (c != 0) return c
        }
        return 0
    }

    /**
     * 校验下载 APK 时的 302 重定向目标：仅允许官方仓库原路径或 GitHub 官方资产 CDN。
     */
    fun allowsAssetRedirect(originalUrl: String, targetUrl: String): Boolean {
        val original = runCatching { parseHttpsUri(originalUrl) }.getOrNull() ?: return false
        val target = runCatching { parseHttpsUri(targetUrl) }.getOrNull() ?: return false
        if (!original.host.equals("github.com", ignoreCase = true) || target.rawFragment != null) {
            return false
        }
        val targetHost = target.host.lowercase()
        if (targetHost in allowedAssetCdnHosts) {
            return true
        }
        return targetHost == "github.com" && target.rawPath == original.rawPath && target.rawQuery == null
    }

    /**
     * 校验网页回退查询 `/releases/latest` 时的跳转边界：仅允许本仓库 releases 路径。
     */
    fun allowsReleasePageRedirect(originalUrl: String, targetUrl: String): Boolean {
        val original = runCatching { parseHttpsUri(originalUrl) }.getOrNull() ?: return false
        val target = runCatching { parseHttpsUri(targetUrl) }.getOrNull() ?: return false
        if (!original.host.equals("github.com", ignoreCase = true)
            || !target.host.equals("github.com", ignoreCase = true)
            || target.rawQuery != null
            || target.rawFragment != null
        ) {
            return false
        }
        val path = target.rawPath ?: return false
        return path == "/$REPO_OWNER/$REPO_NAME/releases/latest"
            || path.startsWith("/$REPO_OWNER/$REPO_NAME/releases/tag/")
    }

    internal fun extractVersionCodeFromBody(body: String): Long? {
        return bodyVersionCodePattern.find(body)?.groupValues?.get(1)?.toLongOrNull()
    }

    internal fun extractSha256FromBody(body: String): String? {
        return bodySha256Pattern.find(body)?.groupValues?.get(1)?.lowercase()
    }

    internal fun summarizeReleaseBody(body: String): String {
        val cleaned = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("---") }
            .map { line ->
                line.removePrefix("### ").removePrefix("## ").removePrefix("# ")
                    .replace("**", "")
                    .replace("`", "")
                    .trim()
            }
            .filter { line ->
                line.isNotEmpty()
                    && !line.startsWith("SHA-256", ignoreCase = true)
                    && !line.startsWith("- SHA-256", ignoreCase = true)
            }
            .take(4)
            .toList()
        return if (cleaned.isEmpty()) {
            "包含问题修复与体验优化，建议直接覆盖安装。"
        } else {
            cleaned.joinToString("\n")
        }
    }

    private fun parsePureVersionName(raw: String): String {
        val match = versionPattern.matchEntire(raw.trim())
            ?: throw IllegalArgumentException("版本号格式无效: $raw")
        return match.groupValues[1]
    }

    private fun validateAssetUrl(url: String, tag: String, assetName: String): String {
        val uri = parseHttpsUri(url)
        val expectedPath = "/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$assetName"
        require(
            uri.host.equals("github.com", ignoreCase = true)
                && uri.rawPath == expectedPath
                && uri.rawQuery == null
                && uri.rawFragment == null
        ) { "安装包下载地址不属于本仓库官方 Release" }
        return uri.toASCIIString()
    }

    private fun parseHttpsUri(url: String): URI {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "仅允许 HTTPS 地址" }
        require(uri.rawUserInfo == null && uri.port == -1 && !uri.host.isNullOrBlank()) {
            "URL 主机或端口不符合安全策略"
        }
        return uri
    }

    private fun requiredString(obj: JSONObject, key: String): String {
        val value = obj.opt(key) as? String
        require(!value.isNullOrBlank()) { "缺少必要字段: $key" }
        return value
    }
}
