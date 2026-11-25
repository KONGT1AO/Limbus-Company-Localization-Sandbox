package com.example.limbuszhcn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSelfUpdatePolicyTest {

    private val sampleSha = "b4f7db74d9aeec9d4ca6d0e03fd1746ec8cec82bf55c7f289821895844e42878"

    private fun sampleApiJson(
        tag: String = "v1.0.11",
        assetName: String = "LimbusLocalizer-v1.0.11-arm64-v8a.apk",
        assetUrl: String = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/download/v1.0.11/LimbusLocalizer-v1.0.11-arm64-v8a.apk",
        htmlUrl: String = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/tag/v1.0.11",
        size: Long = 141_083_139L,
        draft: Boolean = false,
        prerelease: Boolean = false,
        digest: String? = "sha256:$sampleSha",
        body: String = "### 更新内容\n- 修复若干兼容问题\n- versionCode = 278\n- SHA-256: $sampleSha"
    ): String {
        val digestField = if (digest != null) "\"digest\": \"$digest\"," else ""
        return """
            {
              "tag_name": "$tag",
              "html_url": "$htmlUrl",
              "draft": $draft,
              "prerelease": $prerelease,
              "body": ${org.json.JSONObject.quote(body)},
              "assets": [
                {
                  "name": "$assetName",
                  "browser_download_url": "$assetUrl",
                  $digestField
                  "size": $size
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun parseApiRelease_acceptsValidOfficialRelease() {
        val release = LauncherSelfUpdatePolicy.parseApiRelease(sampleApiJson())
        assertEquals("v1.0.11", release.tag)
        assertEquals("1.0.11", release.versionName)
        assertEquals(278L, release.versionCodeHint)
        assertEquals("LimbusLocalizer-v1.0.11-arm64-v8a.apk", release.assetName)
        assertEquals(sampleSha, release.sha256)
        assertEquals("v1.0.11#278", release.updateKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseApiRelease_rejectsPrerelease() {
        LauncherSelfUpdatePolicy.parseApiRelease(sampleApiJson(prerelease = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseApiRelease_rejectsForeignRepoUrl() {
        LauncherSelfUpdatePolicy.parseApiRelease(
            sampleApiJson(
                assetUrl = "https://github.com/other/repo/releases/download/v1.0.11/LimbusLocalizer-v1.0.11-arm64-v8a.apk"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseApiRelease_rejectsUnexpectedApkName() {
        LauncherSelfUpdatePolicy.parseApiRelease(
            sampleApiJson(
                assetName = "OtherApp-v1.0.11.apk",
                assetUrl = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/download/v1.0.11/OtherApp-v1.0.11.apk"
            )
        )
    }

    @Test
    fun isUpdateAvailable_supportsMajorAndSameVersionBuildHotfix() {
        val newerMinor = LauncherSelfUpdatePolicy.parseApiRelease(sampleApiJson(tag = "v1.0.11"))
        assertTrue(LauncherSelfUpdatePolicy.isUpdateAvailable(newerMinor, "1.0.10", 277L))
        assertFalse(LauncherSelfUpdatePolicy.isUpdateAvailable(newerMinor, "1.0.11", 278L))
        assertFalse(LauncherSelfUpdatePolicy.isUpdateAvailable(newerMinor, "1.0.12", 279L))

        // 同版本号热修（build 278 > 277）也应判定存在可用更新
        val sameVerHotfix = LauncherSelfUpdatePolicy.parseApiRelease(
            sampleApiJson(
                tag = "v1.0.10",
                assetName = "LimbusLocalizer-v1.0.10-arm64-v8a.apk",
                assetUrl = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/download/v1.0.10/LimbusLocalizer-v1.0.10-arm64-v8a.apk",
                htmlUrl = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/tag/v1.0.10",
                body = "补充热修 build 278"
            )
        )
        assertTrue(LauncherSelfUpdatePolicy.isUpdateAvailable(sameVerHotfix, "1.0.10", 277L))
        assertFalse(LauncherSelfUpdatePolicy.isUpdateAvailable(sameVerHotfix, "1.0.10", 278L))
    }

    @Test
    fun reminderDot_turnsOffPermanentlyOnceAcknowledgedUntilHigherRelease() {
        val v11 = LauncherSelfUpdatePolicy.parseApiRelease(sampleApiJson(tag = "v1.0.11"))
        assertTrue(LauncherSelfUpdatePolicy.shouldShowReminderDot(v11, "1.0.10", 277L, null))
        assertFalse(LauncherSelfUpdatePolicy.shouldShowReminderDot(v11, "1.0.10", 277L, v11.updateKey))

        val v12 = LauncherSelfUpdatePolicy.parseApiRelease(
            sampleApiJson(
                tag = "v1.0.12",
                assetName = "LimbusLocalizer-v1.0.12-arm64-v8a.apk",
                assetUrl = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/download/v1.0.12/LimbusLocalizer-v1.0.12-arm64-v8a.apk",
                htmlUrl = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/tag/v1.0.12",
                body = "versionCode = 279"
            )
        )
        assertTrue(LauncherSelfUpdatePolicy.shouldShowReminderDot(v12, "1.0.10", 277L, v11.updateKey))
    }

    @Test
    fun parseReleasePageFallback_parsesExpandedAssetsHtml() {
        val tag = LauncherSelfUpdatePolicy.extractTagFromReleaseUrl(
            "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/tag/v1.0.11"
        )
        assertEquals("v1.0.11", tag)
        val html = """<p>build 278 · SHA-256: $sampleSha</p>"""
        val assetsHtml = """
            <a href="/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/download/v1.0.11/LimbusLocalizer-v1.0.11-arm64-v8a.apk">APK</a>
        """.trimIndent()
        val release = LauncherSelfUpdatePolicy.parseReleasePageFallback(tag, html, assetsHtml)
        assertEquals("1.0.11", release.versionName)
        assertEquals(278L, release.versionCodeHint)
        assertEquals(sampleSha, release.sha256)
        assertNull(release.sizeBytes)
    }

    @Test
    fun redirectWhitelist_allowsOfficialAssetCdnAndBlocksExternalHosts() {
        val original = "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/download/v1.0.11/LimbusLocalizer-v1.0.11-arm64-v8a.apk"
        assertTrue(
            LauncherSelfUpdatePolicy.allowsAssetRedirect(
                original,
                "https://objects.githubusercontent.com/github-production-release-asset-2e65be/demo.apk?token=1"
            )
        )
        assertFalse(
            LauncherSelfUpdatePolicy.allowsAssetRedirect(
                original,
                "https://evil.example.com/LimbusLocalizer-v1.0.11-arm64-v8a.apk"
            )
        )
        assertTrue(
            LauncherSelfUpdatePolicy.allowsReleasePageRedirect(
                LauncherSelfUpdatePolicy.RELEASE_LATEST_PAGE_URL,
                "https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases/tag/v1.0.11"
            )
        )
        assertFalse(
            LauncherSelfUpdatePolicy.allowsReleasePageRedirect(
                LauncherSelfUpdatePolicy.RELEASE_LATEST_PAGE_URL,
                "https://github.com/other/repo/releases/tag/v1.0.11"
            )
        )
    }

    @Test
    fun autoCheckCooldown_respectsSixHourWindow() {
        assertTrue(LauncherSelfUpdatePolicy.shouldRunAutoCheck(nowMs = 1_000L, lastCheckMs = 0L))
        assertFalse(LauncherSelfUpdatePolicy.shouldRunAutoCheck(nowMs = 10_000_000L, lastCheckMs = 5_000_000L))
        assertTrue(
            LauncherSelfUpdatePolicy.shouldRunAutoCheck(
                nowMs = 5_000_000L + LauncherSelfUpdatePolicy.AUTO_CHECK_COOLDOWN_MS,
                lastCheckMs = 5_000_000L
            )
        )
    }
}
