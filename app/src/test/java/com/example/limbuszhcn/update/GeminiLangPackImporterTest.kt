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

    @Test
    fun `switching active pack pins user choice and does not get reverted by older higher version patch`() {
        val root = tempFolder.newFolder("workspace_multi").toPath()
        val cacheRoot = root.resolve("translation-cache")
        val localizeRoot = root.resolve("Localize")

        // 1. 准备官方日文资源
        val jpSkill = localizeRoot.resolve("jp/JP_Skills.json")
        Files.createDirectories(jpSkill.parent)
        Files.write(jpSkill, """{"dataList":[{"id":101,"name":"日文技能"}]}""".toByteArray(Charsets.UTF_8))

        val storage = ContainerWorkspaceStorage(localizeRoot)
        val installer = ContainerPatchInstaller(cacheRoot, storage)
        val importer = GeminiLangPackImporter(cacheRoot)

        // 2. 先导入高版本号的旧包（v2026110101）
        val highZip = root.resolve("OldHighVersion.zip").toFile()
        ZipOutputStream(FileOutputStream(highZip)).use { zos ->
            zos.putNextEntry(ZipEntry("Info/version.json"))
            zos.write("""{"version": 2026110101}""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Skills.json"))
            zos.write("""{"dataList":[{"id":101,"name":"高版本旧译文"}]}""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val highResult = importer.importFromArchiveFile(highZip, sourceLabel = "旧高版本")
        val highSummary = installer.activateRedirect(highResult.extraction.outputDirectory, highResult.extraction.manifest)
        assertTrue(highSummary.indexActivated)
        assertEquals("2026110101", installer.activeInstallSummary()?.version)

        // 3. 用户手动导入较低版本号的新包（零协会 2026103002）
        val llcZip = root.resolve("LimbusLocalize_2026103002.zip").toFile()
        ZipOutputStream(FileOutputStream(llcZip)).use { zos ->
            zos.putNextEntry(ZipEntry("LimbusCompany_Data/Lang/LLC_zh-CN/Info/version.json"))
            zos.write("""{"version": 2026103002}""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("LimbusCompany_Data/Lang/LLC_zh-CN/Skills.json"))
            zos.write("""{"dataList":[{"id":101,"name":"零协会新译文"}]}""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val llcResult = importer.importFromArchiveFile(llcZip, sourceLabel = "零协会")
        val llcSummary = installer.activateRedirect(llcResult.extraction.outputDirectory, llcResult.extraction.manifest)
        assertTrue(llcSummary.indexActivated)

        // 验证当前生效版本已被切换并锁定为零协会 2026103002
        assertEquals("2026103002", installer.activeInstallSummary()?.version)
        assertEquals("2026103002", installer.pinnedPatchVersion())

        // 启动游戏前的重编检查：绝对不能因为 patch-cache 中存在数值更大的 2026110101 就反冲触发重编！
        assertFalse(installer.shouldRecompileForCurrentResources())
        val refreshed = installer.refreshActiveRedirects()
        assertEquals("2026103002", refreshed.version)
        assertEquals("2026103002", installer.activeInstallSummary()?.version)

        // 验证已安装补丁列表能够列出两个包，且零协会包处于 active 状态
        val installedList = installer.listInstalledPatches()
        assertEquals(2, installedList.size)
        val activePack = installedList.first { it.isActive }
        assertEquals("2026103002", activePack.version)

        // 4. 用户若主动点击切回高版本旧包，可成功自由切换
        val switchedBack = installer.activatePatchByVersion("2026110101")
        assertEquals("2026110101", switchedBack.version)
        assertEquals("2026110101", installer.pinnedPatchVersion())
    }

    @Test
    fun `deleting installed patch removes cache directory and gracefully deactivates active patch`() {
        val root = tempFolder.newFolder("workspace_delete").toPath()
        val cacheRoot = root.resolve("translation-cache")
        val localizeRoot = root.resolve("Localize")

        val jpSkill = localizeRoot.resolve("jp/JP_Skills.json")
        Files.createDirectories(jpSkill.parent)
        Files.write(jpSkill, """{"dataList":[{"id":101,"name":"日文技能"}]}""".toByteArray(Charsets.UTF_8))

        val storage = ContainerWorkspaceStorage(localizeRoot)
        val installer = ContainerPatchInstaller(cacheRoot, storage)
        val importer = GeminiLangPackImporter(cacheRoot)

        // 1. 准备两个包：v2026110101 与 v2026110202
        fun createZip(ver: String): java.io.File {
            val f = root.resolve("pack_$ver.zip").toFile()
            ZipOutputStream(FileOutputStream(f)).use { zos ->
                zos.putNextEntry(ZipEntry("Info/version.json"))
                zos.write("""{"version": $ver}""".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("Skills.json"))
                zos.write("""{"dataList":[{"id":101,"name":"译文_$ver"}]}""".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            return f
        }

        val zip1 = createZip("2026110101")
        val zip2 = createZip("2026110202")

        val res1 = importer.importFromArchiveFile(zip1, sourceLabel = "包1")
        installer.activateRedirect(res1.extraction.outputDirectory, res1.extraction.manifest)

        val res2 = importer.importFromArchiveFile(zip2, sourceLabel = "包2")
        installer.activateRedirect(res2.extraction.outputDirectory, res2.extraction.manifest)

        // 当前激活的是包2
        assertEquals("2026110202", installer.activeInstallSummary()?.version)
        assertEquals(2, installer.listInstalledPatches().size)

        // 2. 删除未激活的历史包1（2026110101）
        val del1Success = installer.deleteInstalledPatch("2026110101")
        assertTrue(del1Success)
        assertFalse(cacheRoot.resolve("patch-cache/2026110101").toFile().exists())
        assertEquals(1, installer.listInstalledPatches().size)
        // 当前激活的包2不受影响
        assertEquals("2026110202", installer.activeInstallSummary()?.version)

        // 3. 删除当前处于激活/锁定状态的包2（2026110202）
        val del2Success = installer.deleteInstalledPatch("2026110202")
        assertTrue(del2Success)
        assertFalse(cacheRoot.resolve("patch-cache/2026110202").toFile().exists())
        // 索引与锁定标记已被安全停用与清理
        assertEquals(null, installer.activeInstallSummary())
        assertEquals(null, installer.pinnedPatchVersion())
        assertTrue(installer.listInstalledPatches().isEmpty())

        // 4. 测试删除 drop-in 候选文件
        val dropInFile = root.resolve("candidate.zip").toFile().apply { writeText("dummy zip") }
        val candidate = GeminiLangPackImporter.DropInCandidate(
            file = dropInFile,
            isArchive = true,
            name = "candidate.zip",
            sizeBytes = 9,
            lastModified = System.currentTimeMillis(),
            versionHint = "20261109"
        )
        assertTrue(dropInFile.exists())
        val delCandidateSuccess = importer.deleteDropInCandidate(candidate)
        assertTrue(delCandidateSuccess)
        assertFalse(dropInFile.exists())
    }
}
