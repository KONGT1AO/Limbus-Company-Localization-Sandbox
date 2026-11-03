package com.example.limbuszhcn.update

import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile

class GeminiLangPackImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `imports GEMINI_zh-CN zip with custom font and pairs case-insensitive Japanese sources`() {
        val root = tempFolder.newFolder("workspace").toPath()
        val cacheRoot = root.resolve("translation-cache")
        val localizeRoot = root.resolve("Localize")

        // 构造带外层目录包裹、自定义 TTF 字体、大小写差异文件名与顺序对话的 GEMINI_zh-CN.zip
        val zipFile = root.resolve("GEMINI_zh-CN_v2026101501.zip").toFile()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            fun addText(entryName: String, content: String) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            addText(
                "GEMINI_zh-CN/Info/version.json",
                """{"version": 2026101501, "notice": "MD8 本地化测试公告"}"""
            )
            // 超过 100 KB 的模拟 TTF 字节流
            zos.putNextEntry(ZipEntry("GEMINI_zh-CN/Font/Context/ChineseFont.ttf"))
            zos.write(ByteArray(128 * 1024) { 0x42 })
            zos.closeEntry()

            // 注意小写 q: Donquixote
            addText(
                "GEMINI_zh-CN/PersonalityVoiceDlg/Voice_Donquixote_LCB_10301.json",
                """{"dataList":[{"id":"battle_win_1","desc":"胜利语音","dlg":"吾乃堂吉诃德！"}]}"""
            )
            // 无 id 顺序对话
            addText(
                "GEMINI_zh-CN/StoryData/S901B.json",
                """{"dataList":[{"model":"단테","content":"滴答……滴答……"},{"model":"파우스트","content":"浮士德明白。"}]}"""
            )
        }

        val importer = GeminiLangPackImporter(cacheRoot)
        val result = importer.importFromArchiveFile(zipFile)

        assertEquals("2026101501", result.version)
        assertEquals("MD8 本地化测试公告", result.notice)
        assertTrue(result.customFontInstalled)
        assertTrue(cacheRoot.resolve("runtime-font/CustomChineseFont.ttf").isRegularFile())
        assertEquals(2, result.extraction.manifest.extractedFileCount)

        // 模拟容器首次启动前尚未下载日文资源
        val storage = ContainerWorkspaceStorage(localizeRoot)
        val installer = ContainerPatchInstaller(cacheRoot, storage)
        val initialSummary = installer.activateRedirect(result.extraction.outputDirectory, result.extraction.manifest)
        assertFalse(initialSummary.indexActivated)
        assertEquals(0, initialSummary.availableSourceFiles)
        assertEquals("2026101501", installer.latestCachedPatchMetadata()?.version)

        // 模拟进入游戏后下载完成日文资源（注意官方文件名为大写 Q: DonQuixote）
        val jpVoice = localizeRoot.resolve("jp/PersonalityVoiceDlg/JP_Voice_DonQuixote_LCB_10301.json")
        Files.createDirectories(jpVoice.parent)
        Files.write(
            jpVoice,
            """{"dataList":[{"id":"battle_win_1","desc":"勝利ボイス","dlg":"我が名はドンキホーテなり！"}]}""".toByteArray(Charsets.UTF_8)
        )
        val jpStory = localizeRoot.resolve("jp/StoryData/JP_S901B.json")
        Files.createDirectories(jpStory.parent)
        Files.write(
            jpStory,
            """{"dataList":[{"model":"단테","content":"カチッ……カチッ……"},{"model":"파우스트","content":"ファウストは理解しています。"}]}""".toByteArray(Charsets.UTF_8)
        )

        // 第二次点击启动前应自动检测到日文资源已就绪并触发重编
        assertTrue(installer.shouldRecompileForCurrentResources())
        val activatedSummary = installer.refreshActiveRedirects()
        assertTrue(activatedSummary.indexActivated)
        assertEquals(2, activatedSummary.availableSourceFiles)
        assertFalse(installer.shouldRecompileForCurrentResources())

        // 模拟周四游戏内热更新增日文文件，应再次触发自动重编检测
        val jpNew = localizeRoot.resolve("jp/JP_NewEvent.json")
        Files.write(jpNew, """{"dataList":[{"id":1,"content":"新規イベント"}]}""".toByteArray(Charsets.UTF_8))
        assertTrue(installer.shouldRecompileForCurrentResources())
    }
}
