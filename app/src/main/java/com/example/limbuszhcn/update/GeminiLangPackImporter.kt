package com.example.limbuszhcn.update

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.limbuszhcn.container.ContainerPatchInstaller
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.stream.Collectors
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * 本地汉化包全格式导入与热投递扫描引擎。
 *
 * 专门适配 `GEMINI_zh-CN` 目录结构（无语言前缀 JSON、6 大子目录、`Info/version.json`、`Font/Context/ChineseFont.ttf`），
 * 同时兼容 `.zip`、`.7z`、SAF 文件夹、固定热投递目录 `/sdcard/Android/data/io.limbuspe.box/files/DropIn_Lang_Pack`
 * 以及 v1.0.0 旧版缓存的自动无缝迁移。
 */
class GeminiLangPackImporter(
    private val cacheRoot: Path
) {
    private val patchCacheRoot: Path = cacheRoot.resolve("patch-cache")
    private val runtimeFontDir: Path = cacheRoot.resolve("runtime-font")
    private val stagingRoot: Path = cacheRoot.resolve("local-import-staging")

    data class LocalImportResult(
        val extraction: ExtractionResult,
        val version: String,
        val notice: String,
        val customFontInstalled: Boolean,
        val customFontName: String,
        val sourceLabel: String
    )

    /**
     * 确保固定热投递目录存在并生成使用说明文件。
     */
    fun ensureDropInDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DROP_IN_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val readme = File(dir, DROP_IN_README_NAME)
        if (!readme.exists()) {
            runCatching {
                readme.writeText(
                    buildString {
                        appendLine("【Limbus PE 汉化包自动热投递目录】")
                        appendLine("将新版 GEMINI_zh-CN.zip（或 .7z）压缩包，或解压后的 GEMINI_zh-CN 文件夹放入本目录。")
                        appendLine("打开「边狱公司」或点击「启动游戏」时，将自动识别版本号覆盖更新汉化与字体。")
                    },
                    Charsets.UTF_8
                )
            }
        }
        return dir
    }

    /**
     * 描述当前生效的运行时中文字体状态。
     */
    fun describeActiveFont(): String {
        val custom = runtimeFontDir.resolve(CUSTOM_FONT_FILE_NAME)
        if (custom.isRegularFile()) {
            val sizeMb = Files.size(custom).toDouble() / (1024.0 * 1024.0)
            if (sizeMb > 0.1) {
                return String.format(Locale.US, "Context 主字体已就绪 (%.1f MB)", sizeMb)
            }
        }
        return "内置 Sarasa 中文主字体已就绪"
    }

    /**
     * 从用户通过 SAF 选择的 `.zip` 或 `.7z` 压缩包 URI 导入汉化包。
     */
    fun importFromArchiveUri(
        context: Context,
        uri: Uri,
        sourceLabel: String = "本地压缩包导入"
    ): LocalImportResult {
        val displayName = queryDisplayName(context, uri).orEmpty()
        val tempArchive = cacheRoot.resolve("temp-archive-${System.nanoTime()}").toFile()
        cacheRoot.createDirectories()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempArchive.outputStream().buffered().use { output ->
                    copyBounded(input, output, MAX_ARCHIVE_BYTES, "汉化压缩包")
                }
            } ?: error("无法打开所选压缩包文件")
            return importFromArchiveFile(tempArchive, displayName, sourceLabel)
        } finally {
            tempArchive.delete()
        }
    }

    /**
     * 从本地 `.zip` 或 `.7z` 文件导入汉化包。
     */
    fun importFromArchiveFile(
        archiveFile: File,
        originalNameHint: String = archiveFile.name,
        sourceLabel: String = "本地压缩包导入"
    ): LocalImportResult {
        require(archiveFile.isFile) { "压缩包不存在: ${archiveFile.absolutePath}" }
        val archiveSha256 = sha256(archiveFile.toPath())
        resetDirectory(stagingRoot)
        try {
            if (isSevenZArchive(archiveFile, originalNameHint)) {
                extractSevenZToDirectory(archiveFile, stagingRoot)
            } else {
                extractZipToDirectory(archiveFile, stagingRoot)
            }
            return importFromLocalDirectory(
                sourceDir = stagingRoot,
                sourceLabel = sourceLabel,
                overrideArchiveSha256 = archiveSha256
            )
        } finally {
            deleteQuietly(stagingRoot)
        }
    }

    /**
     * 从用户通过 SAF `OpenDocumentTree` 选择的文件夹导入汉化包。
     */
    fun importFromDirectoryUri(
        context: Context,
        treeUri: Uri,
        sourceLabel: String = "本地文件夹导入"
    ): LocalImportResult {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法访问所选文件夹")
        resetDirectory(stagingRoot)
        try {
            var totalBytes = 0L
            var fileCount = 0
            fun copyTree(doc: DocumentFile, relativePrefix: String) {
                for (child in doc.listFiles()) {
                    val name = child.name ?: continue
                    if (name.startsWith(".") && name != ".nomedia") continue
                    val rel = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                    if (child.isDirectory) {
                        if (!name.equals("__MACOSX", ignoreCase = true)) {
                            copyTree(child, rel)
                        }
                    } else if (child.isFile) {
                        val lower = name.lowercase(Locale.ROOT)
                        if (!lower.endsWith(".json") && !lower.endsWith(".ttf") && !lower.equals("license", true)) {
                            continue
                        }
                        fileCount += 1
                        if (fileCount > MAX_ARCHIVE_FILES) {
                            error("文件夹内文件数量超过安全上限 ($MAX_ARCHIVE_FILES)")
                        }
                        val safeRel = normalizeSafeRelativePath(rel)
                            ?: error("不安全的相对路径: $rel")
                        val target = stagingRoot.resolve(safeRel)
                        target.parent.createDirectories()
                        val maxSingle = if (lower.endsWith(".ttf")) MAX_FONT_FILE_BYTES else MAX_JSON_FILE_BYTES
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            BufferedOutputStream(target.outputStream()).use { output ->
                                totalBytes += copyBounded(input, output, maxSingle, safeRel)
                            }
                        }
                        if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            error("汉化文件夹总大小超过安全上限")
                        }
                    }
                }
            }
            copyTree(rootDoc, "")
            return importFromLocalDirectory(
                sourceDir = stagingRoot,
                sourceLabel = sourceLabel
            )
        } finally {
            deleteQuietly(stagingRoot)
        }
    }

    /**
     * 自动扫描固定热投递目录 `DropIn_Lang_Pack` 以及 v1.0.0 旧版缓存目录。
     * 若发现新放入的压缩包/文件夹或待迁移旧缓存，自动导入并返回结果；否则返回 `null`。
     */
    fun scanAutoImportSources(context: Context): LocalImportResult? {
        val dropInDir = ensureDropInDirectory(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 优先扫描 DropIn_Lang_Pack 中的新 .zip / .7z 归档
        val archives = dropInDir.listFiles()
            ?.filter { file ->
                file.isFile && (
                    file.name.endsWith(".zip", ignoreCase = true) ||
                        file.name.endsWith(".7z", ignoreCase = true)
                    )
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        for (archive in archives) {
            val importedResult = runCatching {
                importFromArchiveFile(
                    archiveFile = archive,
                    originalNameHint = archive.name,
                    sourceLabel = "DropIn 热投递压缩包 (${archive.name})"
                )
            }.getOrNull()
            if (importedResult != null) {
                val marker = File(archive.parentFile, "${archive.name}.imported")
                if (marker.exists()) marker.delete()
                archive.renameTo(marker)
                return importedResult
            }
        }

        // 2. 扫描 DropIn_Lang_Pack 下的汉化文件夹（如 DropIn_Lang_Pack/GEMINI_zh-CN）
        val candidateDirs = buildList {
            dropInDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.let(::addAll)
            if (File(dropInDir, "Info/version.json").isFile || File(dropInDir, "StoryData").isDirectory) {
                add(dropInDir)
            }
        }
        for (dir in candidateDirs) {
            val signature = computeDirectoryQuickSignature(dir) ?: continue
            val lastImportedSig = prefs.getString(KEY_LAST_DROPIN_DIR_SIG, null)
            val hasAnyCachedPatch = hasAnyCachedPatch()
            if (signature != lastImportedSig || !hasAnyCachedPatch) {
                val imported = runCatching {
                    importFromLocalDirectory(
                        sourceDir = dir.toPath(),
                        sourceLabel = "DropIn 热投递目录 (${dir.name})"
                    )
                }.getOrNull()
                if (imported != null) {
                    prefs.edit().putString(KEY_LAST_DROPIN_DIR_SIG, signature).apply()
                    return imported
                }
            }
        }

        // 3. 无缝继承 v1.0.0 已导入在 externalFilesDir/virtual_storage/lang_workspace/Raw_Lang_Pack 的汉化包
        if (!hasAnyCachedPatch()) {
            val externalBase = context.getExternalFilesDir(null)
            if (externalBase != null) {
                val legacyDir = File(externalBase, "virtual_storage/lang_workspace/Raw_Lang_Pack")
                if (legacyDir.isDirectory) {
                    val migrated = runCatching {
                        importFromLocalDirectory(
                            sourceDir = legacyDir.toPath(),
                            sourceLabel = "自动继承已导入汉化包"
                        )
                    }.getOrNull()
                    if (migrated != null) {
                        return migrated
                    }
                }
            }
        }

        return null
    }

    /**
     * 核心目录导入实现：剥离任意外层包裹目录，解析 `Info/version.json`，部署 `ChineseFont.ttf`，
     * 并将全部本地化 JSON 转换为 `ContainerPatchInstaller` 标准 `PatchManifest` 缓存结构。
     */
    fun importFromLocalDirectory(
        sourceDir: Path,
        sourceLabel: String = "本地目录导入",
        overrideArchiveSha256: String? = null
    ): LocalImportResult {
        require(sourceDir.isDirectory()) { "汉化源目录不存在: $sourceDir" }
        val normalizedRoot = sourceDir.toAbsolutePath().normalize()
        val allFiles = Files.walk(normalizedRoot).use { stream ->
            stream.filter { it.isRegularFile() }.collect(Collectors.toList())
        }
        if (allFiles.isEmpty()) {
            throw ArchiveValidationException("所选汉化包为空")
        }

        val relMap = linkedMapOf<String, Path>()
        for (file in allFiles) {
            val rel = normalizedRoot.relativize(file).toString().replace('\\', '/')
            if (rel.startsWith("__MACOSX/", ignoreCase = true)) continue
            relMap[rel] = file
        }

        val packPrefix = detectPackRootPrefix(relMap.keys)
        val strippedFiles = linkedMapOf<String, Path>()
        for ((rel, file) in relMap) {
            if (packPrefix.isNotEmpty() && !rel.startsWith(packPrefix)) continue
            val sub = rel.removePrefix(packPrefix).trimStart('/')
            if (sub.isNotEmpty()) {
                strippedFiles[sub] = file
            }
        }

        // 1. 解析 Info/version.json（忽略大小写）
        val versionEntry = strippedFiles.entries.firstOrNull { (k, _) ->
            k.equals("Info/version.json", ignoreCase = true)
        }
        var parsedVersionLong: Long? = null
        var parsedVersionStr: String? = null
        var noticeText = ""
        if (versionEntry != null) {
            runCatching {
                val raw = String(Files.readAllBytes(versionEntry.value), Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                val obj = JSONObject(raw)
                val vOpt = obj.opt("version")
                when (vOpt) {
                    is Number -> {
                        parsedVersionLong = vOpt.toLong()
                        parsedVersionStr = parsedVersionLong.toString()
                    }
                    is String -> {
                        val trimmed = vOpt.trim()
                        if (trimmed.isNotEmpty()) {
                            parsedVersionLong = trimmed.toLongOrNull()
                            parsedVersionStr = sanitizeVersionSegment(trimmed)
                        }
                    }
                }
                noticeText = obj.optString("notice", "").trim()
            }
        }
        val effectiveVersion = parsedVersionStr
            ?: SimpleDateFormat("yyyyMMddHH", Locale.US).format(Date())

        // 2. 检测并安装中文字体（优先 Font/Context/*.ttf，其次任意 Font/**/*.ttf 或根级 *.ttf）
        val fontEntry = selectBestFontEntry(strippedFiles)
        var customFontInstalled = false
        var customFontName = ""
        if (fontEntry != null) {
            val fontSize = Files.size(fontEntry.value)
            if (fontSize > 100 * 1024L) {
                runtimeFontDir.createDirectories()
                val tmpFont = runtimeFontDir.resolve("$CUSTOM_FONT_FILE_NAME.tmp")
                val finalFont = runtimeFontDir.resolve(CUSTOM_FONT_FILE_NAME)
                Files.copy(fontEntry.value, tmpFont, StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmpFont, finalFont, StandardCopyOption.REPLACE_EXISTING)
                customFontInstalled = true
                customFontName = fontEntry.key
            }
        }

        // 3. 收集并规范化全部本地化 JSON 文件
        patchCacheRoot.createDirectories()
        val tempPatchDir = patchCacheRoot.resolve("$effectiveVersion.tmp")
        val finalPatchDir = patchCacheRoot.resolve(effectiveVersion)
        resetDirectory(tempPatchDir)

        try {
            if (versionEntry != null) {
                val infoDest = tempPatchDir.resolve("info/version.json")
                infoDest.parent.createDirectories()
                Files.copy(versionEntry.value, infoDest, StandardCopyOption.REPLACE_EXISTING)
            }

            val entries = mutableListOf<PatchFileEntry>()
            val combinedDigest = MessageDigest.getInstance("SHA-256")

            for ((rel, file) in strippedFiles) {
                if (!rel.endsWith(".json", ignoreCase = true)) continue
                if (rel.startsWith("Info/", ignoreCase = true) || rel.startsWith("Font/", ignoreCase = true)) {
                    continue
                }
                val cleanRel = stripLanguageFolderAndFilePrefix(rel)
                if (cleanRel.isBlank()) continue
                val enRel = withEnglishPrefix(cleanRel)
                val cachePath = "files/en/$enRel"
                val targetPath = "en/$enRel"

                val dest = tempPatchDir.resolve(cachePath)
                dest.parent.createDirectories()
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)

                val fileSha = sha256(dest)
                val fileSize = Files.size(dest)
                combinedDigest.update("$targetPath:$fileSha\n".toByteArray(Charsets.UTF_8))

                entries += PatchFileEntry(
                    sourcePath = rel,
                    cachePath = cachePath,
                    targetPath = targetPath,
                    size = fileSize,
                    sha256 = fileSha
                )
            }

            if (entries.isEmpty()) {
                throw ArchiveValidationException("未在所选包中检测到有效的本地化 JSON 文件（请确认选择了 GEMINI_zh-CN 汉化包）")
            }

            val archiveSha = overrideArchiveSha256
                ?: combinedDigest.digest().joinToString("") { "%02x".format(it) }

            val sortedEntries = entries.sortedBy { it.targetPath }
            val manifest = PatchManifest(
                requestedVersion = effectiveVersion,
                archiveVersion = parsedVersionLong,
                archiveSha256 = archiveSha,
                archiveFileCount = strippedFiles.size,
                extractedFileCount = sortedEntries.size,
                entries = sortedEntries
            )
            Files.write(
                tempPatchDir.resolve(TranslationArchiveExtractor.MANIFEST_FILE),
                manifest.toJson().toByteArray(Charsets.UTF_8)
            )

            // 保存本地包扩展元数据（公告、来源、自定义字体名）
            val metaProps = Properties().apply {
                setProperty("version", effectiveVersion)
                setProperty("notice", noticeText)
                setProperty("sourceLabel", sourceLabel)
                setProperty("customFontName", customFontName)
            }
            tempPatchDir.resolve(ContainerPatchInstaller.LOCAL_PACK_META_FILE).outputStream().use { out ->
                metaProps.store(out, "GEMINI_zh-CN local pack metadata")
            }

            if (finalPatchDir.exists()) {
                deleteQuietly(finalPatchDir)
            }
            Files.move(tempPatchDir, finalPatchDir, StandardCopyOption.REPLACE_EXISTING)

            val extraction = ExtractionResult(outputDirectory = finalPatchDir, manifest = manifest)
            return LocalImportResult(
                extraction = extraction,
                version = effectiveVersion,
                notice = noticeText,
                customFontInstalled = customFontInstalled,
                customFontName = customFontName,
                sourceLabel = sourceLabel
            )
        } catch (error: Throwable) {
            deleteQuietly(tempPatchDir)
            throw error
        }
    }

    private fun hasAnyCachedPatch(): Boolean {
        if (!patchCacheRoot.isDirectory()) return false
        return Files.list(patchCacheRoot).use { stream ->
            stream.anyMatch { it.isDirectory() && it.resolve("manifest.json").isRegularFile() }
        }
    }

    private fun computeDirectoryQuickSignature(dir: File): String? {
        val versionFile = File(dir, "Info/version.json").takeIf { it.isFile }
            ?: dir.listFiles()?.firstOrNull { it.isDirectory }?.let { File(it, "Info/version.json") }?.takeIf { it.isFile }
        var jsonCount = 0
        var maxMtime = 0L
        dir.walkTopDown().maxDepth(4).forEach { f ->
            if (f.isFile && f.name.endsWith(".json", ignoreCase = true)) {
                jsonCount += 1
                if (f.lastModified() > maxMtime) maxMtime = f.lastModified()
            }
        }
        if (jsonCount == 0) return null
        val verText = versionFile?.let { runCatching { it.readText(Charsets.UTF_8).trim() }.getOrNull() }.orEmpty()
        return "${verText.hashCode()}:$jsonCount:$maxMtime"
    }

    private fun extractZipToDirectory(archiveFile: File, targetRoot: Path) {
        var totalBytes = 0L
        var count = 0
        ZipInputStream(BufferedInputStream(archiveFile.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val rawName = entry.name.replace('\\', '/')
                if (rawName.startsWith("__MACOSX/", ignoreCase = true)) continue
                val safeRel = normalizeSafeRelativePath(rawName)
                    ?: throw ArchiveValidationException("压缩包含有不安全路径: $rawName")
                val lower = safeRel.lowercase(Locale.ROOT)
                if (!lower.endsWith(".json") && !lower.endsWith(".ttf") && !lower.endsWith("license")) {
                    continue
                }
                count += 1
                if (count > MAX_ARCHIVE_FILES) {
                    throw ArchiveValidationException("压缩包文件数量超过上限 ($MAX_ARCHIVE_FILES)")
                }
                val dest = targetRoot.resolve(safeRel)
                dest.parent.createDirectories()
                val maxSingle = if (lower.endsWith(".ttf")) MAX_FONT_FILE_BYTES else MAX_JSON_FILE_BYTES
                BufferedOutputStream(dest.outputStream()).use { output ->
                    totalBytes += copyBounded(zis, output, maxSingle, safeRel)
                }
                if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw ArchiveValidationException("解压总大小超过上限")
                }
            }
        }
    }

    private fun extractSevenZToDirectory(archiveFile: File, targetRoot: Path) {
        var totalBytes = 0L
        var count = 0
        val buffer = ByteArray(64 * 1024)
        SevenZFile.builder().setFile(archiveFile).get().use { sevenZ ->
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (entry.isDirectory) continue
                val rawName = entry.name.replace('\\', '/')
                if (rawName.startsWith("__MACOSX/", ignoreCase = true)) continue
                val safeRel = normalizeSafeRelativePath(rawName)
                    ?: throw ArchiveValidationException("7z 压缩包含有不安全路径: $rawName")
                val lower = safeRel.lowercase(Locale.ROOT)
                if (!lower.endsWith(".json") && !lower.endsWith(".ttf") && !lower.endsWith("license")) {
                    continue
                }
                count += 1
                if (count > MAX_ARCHIVE_FILES) {
                    throw ArchiveValidationException("7z 压缩包文件数量超过上限 ($MAX_ARCHIVE_FILES)")
                }
                val maxSingle = if (lower.endsWith(".ttf")) MAX_FONT_FILE_BYTES else MAX_JSON_FILE_BYTES
                val dest = targetRoot.resolve(safeRel)
                dest.parent.createDirectories()
                var written = 0L
                BufferedOutputStream(dest.outputStream()).use { output ->
                    while (true) {
                        val r = sevenZ.read(buffer)
                        if (r <= 0) break
                        written += r
                        if (written > maxSingle) {
                            throw ArchiveValidationException("单个文件超过大小限制: $safeRel")
                        }
                        output.write(buffer, 0, r)
                    }
                }
                totalBytes += written
                if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw ArchiveValidationException("7z 解压总大小超过上限")
                }
            }
        }
    }

    private fun isSevenZArchive(file: File, nameHint: String): Boolean {
        if (nameHint.endsWith(".7z", ignoreCase = true)) return true
        val header = ByteArray(6)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(0)
        return read == 6 &&
            header[0] == 0x37.toByte() &&
            header[1] == 0x7A.toByte() &&
            header[2] == 0xBC.toByte() &&
            header[3] == 0xAF.toByte() &&
            header[4] == 0x27.toByte() &&
            header[5] == 0x1C.toByte()
    }

    private fun detectPackRootPrefix(paths: Collection<String>): String {
        // 1. 优先以 Info/version.json 定位根前缀
        for (p in paths) {
            val lower = p.lowercase(Locale.ROOT)
            if (lower == "info/version.json") return ""
            if (lower.endsWith("/info/version.json")) {
                return p.substring(0, p.length - "Info/version.json".length)
            }
        }
        // 2. 其次以典型子目录或核心表 Skills.json 定位根前缀
        val markers = listOf(
            "storydata/",
            "personalityvoicedlg/",
            "egovoicedig/",
            "battleannouncerdlg/",
            "bgmlyrics/",
            "rpgsystem/",
            "skills.json"
        )
        for (p in paths) {
            val lower = p.lowercase(Locale.ROOT)
            for (marker in markers) {
                if (lower.startsWith(marker)) return ""
                val idx = lower.indexOf("/$marker")
                if (idx >= 0) {
                    return p.substring(0, idx + 1)
                }
            }
        }
        return ""
    }

    private fun selectBestFontEntry(files: Map<String, Path>): Map.Entry<String, Path>? {
        val ttfEntries = files.entries.filter { it.key.endsWith(".ttf", ignoreCase = true) }
        if (ttfEntries.isEmpty()) return null
        return ttfEntries.firstOrNull {
            it.key.replace('\\', '/').lowercase(Locale.ROOT).startsWith("font/context/")
        } ?: ttfEntries.firstOrNull {
            it.key.replace('\\', '/').lowercase(Locale.ROOT).startsWith("font/")
        } ?: ttfEntries.first()
    }

    private fun stripLanguageFolderAndFilePrefix(relPath: String): String {
        var cleaned = relPath.replace('\\', '/').trimStart('/')
        val lower = cleaned.lowercase(Locale.ROOT)
        for (folderPrefix in listOf("localize/en/", "localize/jp/", "localize/kr/", "en/", "jp/", "kr/")) {
            if (lower.startsWith(folderPrefix)) {
                cleaned = cleaned.substring(folderPrefix.length)
                break
            }
        }
        val slash = cleaned.lastIndexOf('/')
        val dir = if (slash >= 0) cleaned.substring(0, slash + 1) else ""
        val name = if (slash >= 0) cleaned.substring(slash + 1) else cleaned
        val baseName = LANG_FILE_PREFIX_REGEX.replace(name, "")
        return dir + baseName
    }

    private fun withEnglishPrefix(cleanRelPath: String): String {
        val slash = cleanRelPath.lastIndexOf('/')
        val dir = if (slash >= 0) cleanRelPath.substring(0, slash + 1) else ""
        val name = if (slash >= 0) cleanRelPath.substring(slash + 1) else cleanRelPath
        return dir + if (name.startsWith("EN_")) name else "EN_$name"
    }

    private fun normalizeSafeRelativePath(path: String): String? {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains('\u0000')) {
            return null
        }
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts += part
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun sanitizeVersionSegment(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "local" }

    private fun copyBounded(
        input: InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        label: String
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buffer)
            if (r < 0) break
            total += r
            if (total > maxBytes) {
                throw ArchiveValidationException("$label 超过允许大小上限")
            }
            output.write(buffer, 0, r)
        }
        return total
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resetDirectory(path: Path) {
        deleteQuietly(path)
        path.createDirectories()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun deleteQuietly(path: Path) {
        runCatching {
            if (path.exists()) {
                path.deleteRecursively()
            }
        }
    }

    companion object {
        const val DROP_IN_DIR_NAME = "DropIn_Lang_Pack"
        const val CUSTOM_FONT_FILE_NAME = "CustomChineseFont.ttf"
        private const val DROP_IN_README_NAME = "使用说明_将汉化包ZIP或文件夹放入此处即可自动导入.txt"
        private const val PREFS_NAME = "limbuspe_local_pack"
        private const val KEY_LAST_DROPIN_DIR_SIG = "last_dropin_dir_sig"
        private const val MAX_ARCHIVE_FILES = 6_000
        private const val MAX_JSON_FILE_BYTES = 16L * 1024L * 1024L
        private const val MAX_FONT_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
        private val LANG_FILE_PREFIX_REGEX = Regex("^(EN|JP|KR)_", RegexOption.IGNORE_CASE)
    }
}
