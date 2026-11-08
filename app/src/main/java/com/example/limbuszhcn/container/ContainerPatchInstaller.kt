package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameStorageInstaller
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.gamefs.PatchUninstallSummary
import com.example.limbuszhcn.update.InstallGitHubReleaseRequest
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.TranslationUpdateManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.LinkedHashSet
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * 将 GitHub 汉化包编译成运行时索引，并通过容器存储管理激活、刷新与卸载状态。
 *
 * @param cacheRoot 汉化下载、解压和运行时索引的宿主缓存根目录。
 * @param storage 容器内游戏本地化资源的访问边界。
 */
class ContainerPatchInstaller(
    private val cacheRoot: Path,
    private val storage: GameStorage
) {
    /**
     * 从指定 GitHub Releases 页面获取最新汉化包并激活完整运行时索引。
     *
     * @param releaseUrl 公开的 GitHub Releases 页面或兼容的 GitHub API 地址。
     * @param installedVersion 已知的本地汉化版本；非空时仅接受更高版本。
     * @return 本次索引编译、资源就绪度和激活状态摘要。
     */
    fun installFromGitHub(
        releaseUrl: String,
        installedVersion: Long? = null,
        downloadDirectory: Path = cacheRoot.resolve("downloads")
    ): PatchInstallSummary {
        val result = TranslationUpdateManager().installFromGitHub(
            InstallGitHubReleaseRequest(
                releaseUrl = releaseUrl,
                downloadCacheRoot = downloadDirectory,
                patchCacheRoot = cacheRoot.resolve("patch-cache"),
                installedVersion = installedVersion
            )
        )
        pinActivePatch(result.manifest.requestedVersion)
        return activateRedirect(result.outputDirectory, result.manifest)
    }

    /**
     * 安装已经在本地准备好的补丁目录，主要用于离线测试和兼容旧补丁流程。
     *
     * @param patchDirectory 已解压且与清单匹配的补丁目录。
     * @param manifest 描述补丁版本、文件哈希和目标路径的清单。
     * @return 实际写入容器存储的文件摘要。
     */
    fun installPrepared(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        saveBaseline(patchDirectory, manifest)
        return GameStorageInstaller(storage).install(patchDirectory, manifest)
    }

    /**
     * 读取当前已激活的运行时索引或旧版重定向清单状态。
     *
     * @return 存在有效激活状态时返回安装摘要，否则返回 `null`。
     */
    fun activeInstallSummary(): PatchInstallSummary? {
        if (activeIndexPointer().isRegularFile()) {
            val indexFile = runCatching {
                // Path.of 在 Android 14 才可用；索引恢复也必须兼容最低支持的 Android 8。
                Paths.get(activeIndexPointer().readText(Charsets.UTF_8).trim())
            }.getOrNull()
            val indexManifest = indexFile?.parent?.resolve(INDEX_MANIFEST_FILE)
            if (indexManifest != null && indexManifest.isRegularFile()) {
                val properties = java.util.Properties().apply {
                    indexManifest.inputStream().use(::load)
                }
                return PatchInstallSummary(
                    version = properties.getProperty("version", "unknown"),
                    writtenFiles = properties.getProperty("availableSourceFileCount")
                        ?.toIntOrNull() ?: 0,
                    indexActivated = true,
                    candidateSourceFiles = properties.getProperty("candidateSourceFileCount")
                        ?.toIntOrNull() ?: 0,
                    availableSourceFiles = properties.getProperty("availableSourceFileCount")
                        ?.toIntOrNull() ?: 0,
                    sourceRecordCount = properties.getProperty("sourceRecordCount")
                        ?.toIntOrNull() ?: 0
                )
            }
        }
        if (!activeRedirectFile().isRegularFile()) {
            return null
        }
        val patchDirectory = latestPatchDirectory() ?: return null
        if (!patchDirectory.resolve(ACTIVE_MARKER_FILE).isRegularFile()) {
            return null
        }
        val manifestFile = patchDirectory.resolve("manifest.json")
        if (!manifestFile.isRegularFile()) {
            return null
        }
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        return PatchInstallSummary(version = manifest.requestedVersion, writtenFiles = manifest.entries.size)
    }

    /**
     * 使用最近一次缓存的 GitHub 汉化包重新编译并激活运行时索引。
     *
     * @param auditDirectory 可选的开发诊断输出目录；为空时不生成覆盖审计。
     * @return 重新编译后的资源统计和索引激活摘要。
     */
    fun refreshActiveRedirects(auditDirectory: Path? = null): PatchInstallSummary {
        val patchDirectory = latestPatchDirectory()
            ?: throw IllegalStateException("没有可刷新重定向的汉化缓存，请先安装汉化包")
        val manifestFile = patchDirectory.resolve("manifest.json")
        require(manifestFile.isRegularFile()) {
            "汉化缓存缺少 manifest.json: $manifestFile"
        }
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        return activateRedirect(patchDirectory, manifest, auditDirectory)
    }

    /**
     * 将汉化 JSON 与设备上的日文资源配对，生成只包含明确文本映射的运行时索引。
     *
     * @param patchDirectory 已解压的汉化包缓存目录。
     * @param manifest 汉化包文件清单和版本信息。
     * @param auditDirectory 可选的开发诊断输出目录；为空时不生成覆盖审计。
     * @return 候选资源、可读日文源文件和索引激活结果。
     */
    fun activateRedirect(
        patchDirectory: Path,
        manifest: PatchManifest,
        auditDirectory: Path? = null
    ): PatchInstallSummary {
        var activeFiles = 0
        var candidateFiles = 0
        var availableFiles = 0
        val indexCompiler = TranslationIndexCompiler()
        val caseInsensitiveJp = buildCaseInsensitiveJapaneseLookup()
        manifest.entries.forEach { entry ->
            val source = patchDirectory.resolve(entry.cachePath).toAbsolutePath().normalize()
            if (!source.isRegularFile() || !source.name.endsWith(".json", ignoreCase = true)) {
                return@forEach
            }
            candidateFiles += 1
            val target = storage.resolvePatchTarget(entry.targetPath)
            val japaneseSource = resolveJapaneseSource(target, caseInsensitiveJp)
            val japaneseJson = storage.read(japaneseSource) ?: return@forEach
            availableFiles += 1
            val translationText = source.readText(Charsets.UTF_8)
            val originalText = japaneseJson.toString(Charsets.UTF_8)
            val records = collectTranslationIndexRecords(
                path = japaneseSource,
                originalJson = originalText,
                translationJson = translationText
            )
            if (records.isNotEmpty()) {
                records.forEach(indexCompiler::add)
                activeFiles += 1
            }
        }
        // The production path is runtime-only. Stale merged-file redirects must not survive an
        // upgrade from the earlier experiment.
        Files.deleteIfExists(activeRedirectFile())
        Files.deleteIfExists(pendingRedirectFile())
        val compiledIndex = indexCompiler.compile(
            version = manifest.requestedVersion,
            archiveSha256 = manifest.archiveSha256,
            candidateSourceFileCount = candidateFiles,
            availableSourceFileCount = availableFiles
        )
        if (auditDirectory != null) {
            writeCoverageAudit(patchDirectory, manifest, compiledIndex, auditDirectory)
        }
        val resourcesReady = hasSufficientJapaneseSources(availableFiles, candidateFiles)
        val previousIndexAvailable = activeIndexPointer().isRegularFile()
        val activated = resourcesReady && compiledIndex.uniqueEntries.isNotEmpty()
        if (activated) {
            val activeIndex = TranslationIndexStore(indexRoot()).activate(compiledIndex)
            saveJapaneseResourcesFingerprint()
            runCatching {
                android.util.Log.i(
                    TAG,
                    "Activated Japanese full-text translation index version=${manifest.requestedVersion}" +
                        " unique=${compiledIndex.uniqueEntries.size}" +
                        " terms=${compiledIndex.termEntries.size}" +
                        " fallback=${compiledIndex.fallbackEntries.size}" +
                        " context=${compiledIndex.contextEntries.size}" +
                        " battleHints=${compiledIndex.battleHintEntries.size}" +
                        " sources=${compiledIndex.sourceRecordCount}" +
                        " conflicts=${compiledIndex.conflictCount}" +
                        " dominant=${compiledIndex.dominantEntryCount}" +
                        " path=$activeIndex"
                )
            }
            patchDirectory.resolve(ACTIVE_MARKER_FILE).writeText("runtime-index-jp-ui\n", Charsets.UTF_8)
            pinActivePatch(manifest.requestedVersion)
        } else if (!resourcesReady) {
            runCatching {
                android.util.Log.w(
                    TAG,
                    "Japanese resources not ready version=${manifest.requestedVersion}" +
                        " available=$availableFiles candidate=$candidateFiles" +
                        " preserved=$previousIndexAvailable"
                )
            }
        } else {
            runCatching {
                android.util.Log.w(
                    TAG,
                    "Translation index has no unambiguous entries version=${manifest.requestedVersion}" +
                        " preserved=$previousIndexAvailable"
                )
            }
        }
        return PatchInstallSummary(
            version = manifest.requestedVersion,
            writtenFiles = activeFiles,
            indexActivated = activated,
            candidateSourceFiles = candidateFiles,
            availableSourceFiles = availableFiles,
            sourceRecordCount = compiledIndex.sourceRecordCount,
            previousIndexPreserved = !activated && previousIndexAvailable
        )
    }

    /**
     * 停用当前汉化索引，并尽可能恢复旧版文件补丁留下的基线内容。
     *
     * @return 删除或恢复文件的卸载摘要。
     */
    fun uninstallLatest(): PatchUninstallSummary {
        val patchDirectory = latestPatchDirectory()
            ?: throw IllegalStateException("没有可卸载的汉化缓存，请先安装一次汉化")
        val manifestFile = patchDirectory.resolve("manifest.json")
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        if (activeRedirectFile().isRegularFile()) {
            Files.deleteIfExists(activeRedirectFile())
        }
        if (pendingRedirectFile().isRegularFile()) {
            Files.deleteIfExists(pendingRedirectFile())
        }
        TranslationIndexStore(indexRoot()).deactivate()
        removeLangOverlay(manifest)
        val restored = restoreBaseline(manifest)
        if (restored != null) {
            return restored
        }
        if (patchDirectory.resolve(ACTIVE_MARKER_FILE).isRegularFile()) {
            Files.deleteIfExists(patchDirectory.resolve(ACTIVE_MARKER_FILE))
            return PatchUninstallSummary(version = manifest.requestedVersion, deletedFiles = manifest.entries.size)
        }
        return GameStorageInstaller(storage).uninstall(manifest)
    }

    /**
     * 仅停用运行时翻译索引指针，保留 patch-cache 中的汉化包以便随时重新开启。
     */
    fun deactivateIndexOnly(): String? {
        val prev = activeInstallSummary()?.version ?: latestPatchDirectory()?.name
        Files.deleteIfExists(activeRedirectFile())
        Files.deleteIfExists(pendingRedirectFile())
        TranslationIndexStore(indexRoot()).deactivate()
        latestPatchDirectory()?.let { Files.deleteIfExists(it.resolve(ACTIVE_MARKER_FILE)) }
        return prev
    }

    /**
     * 读取最新已导入缓存目录的元数据摘要（即使容器尚未下载日文资源也可展示已导入版本和文件数）。
     */
    fun latestCachedPatchMetadata(): CachedPatchMetadata? {
        val patchDir = latestPatchDirectory() ?: return null
        val manifestFile = patchDir.resolve("manifest.json")
        if (!manifestFile.isRegularFile()) return null
        val manifest = runCatching {
            PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val metaFile = patchDir.resolve(LOCAL_PACK_META_FILE)
        val props = java.util.Properties().apply {
            if (metaFile.isRegularFile()) {
                runCatching { metaFile.inputStream().use(::load) }
            }
        }
        return CachedPatchMetadata(
            version = manifest.requestedVersion,
            jsonFileCount = manifest.extractedFileCount,
            notice = props.getProperty("notice").orEmpty(),
            sourceLabel = props.getProperty("sourceLabel", "本地汉化包"),
            customFontName = props.getProperty("customFontName").orEmpty()
        )
    }

    /**
     * 判断启动游戏前是否需要针对当前容器内的日文资源重新编译并激活索引。
     * 典型触发场景：
     * 1. 首次进游戏下载完资源后第二次启动（之前无索引，现在日文资源已就绪）；
     * 2. 每周四游戏在沙箱内热更了新的 Localize/jp/JP_*.json 文件；
     * 3. 导入了更新版本的汉化包但尚未完成激活。
     */
    fun shouldRecompileForCurrentResources(): Boolean {
        val latest = latestPatchDirectory() ?: return false
        val active = activeInstallSummary() ?: return true
        val activeManifest = activeIndexManifestProperties() ?: return true
        // 先检查 schema、版本和归档摘要；这些轻量元数据已经能确定索引过期时，
        // 不再提前遍历整个日文资源树。
        if (!activeManifest.usesCurrentTranslationIndexCompiler()) return true
        val latestManifest = runCatching {
            PatchManifest.fromJson(latest.resolve("manifest.json").readText(Charsets.UTF_8))
        }.getOrNull() ?: return false
        if (active.version != latestManifest.requestedVersion) return true
        // 汉化包可能保持同一版本号但替换内容；必须比较归档摘要，避免继续使用旧索引。
        if (!activeManifest.matchesTranslationArchive(latestManifest.archiveSha256)) return true
        val jpPaths = storage.listJapaneseResourcePaths()
        if (jpPaths.isEmpty()) return false
        val savedFp = runCatching {
            val fpFile = indexRoot().resolve(JP_FINGERPRINT_FILE)
            if (fpFile.isRegularFile()) fpFile.readText(Charsets.UTF_8).trim() else ""
        }.getOrDefault("")
        return savedFp != computeJapaneseFingerprint(jpPaths)
    }

    /**
     * 索引 schema 或译名选择策略变化后，旧索引即使汉化包版本没变也必须重建。
     */
    private fun activeIndexManifestProperties(): java.util.Properties? {
        val pointer = activeIndexPointer()
        if (!pointer.isRegularFile()) return null
        val indexFile = runCatching {
            Paths.get(pointer.readText(Charsets.UTF_8).trim())
        }.getOrNull() ?: return null
        val manifest = indexFile.parent?.resolve(INDEX_MANIFEST_FILE)
        if (manifest == null || !manifest.isRegularFile()) return null
        return runCatching {
            java.util.Properties().apply { manifest.inputStream().use(::load) }
        }.getOrNull()
    }

    private fun saveJapaneseResourcesFingerprint() {
        runCatching {
            val jpPaths = storage.listJapaneseResourcePaths()
            val dir = indexRoot()
            Files.createDirectories(dir)
            dir.resolve(JP_FINGERPRINT_FILE).writeText(computeJapaneseFingerprint(jpPaths), Charsets.UTF_8)
        }
    }

    private fun computeJapaneseFingerprint(jpPaths: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        jpPaths.forEach { p ->
            digest.update(p.toByteArray(Charsets.UTF_8))
            val f = java.io.File(p)
            if (f.isFile) {
                digest.update(":${f.length()}:${f.lastModified()}\n".toByteArray(Charsets.UTF_8))
            }
        }
        val hex = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        return "${jpPaths.size}:$hex"
    }

    private fun buildCaseInsensitiveJapaneseLookup(): Map<String, String> {
        val jpPaths = storage.listJapaneseResourcePaths()
        if (jpPaths.isEmpty()) return emptyMap()
        return buildMap(jpPaths.size) {
            jpPaths.forEach { actual ->
                put(actual.replace('\\', '/').lowercase(java.util.Locale.ROOT), actual)
            }
        }
    }

    private fun resolveJapaneseSource(
        resolvedTarget: String,
        caseInsensitiveLookup: Map<String, String>
    ): String {
        val exact = japaneseSourceFor(resolvedTarget)
        if (storage.exists(exact)) return exact
        return caseInsensitiveLookup[exact.replace('\\', '/').lowercase(java.util.Locale.ROOT)] ?: exact
    }

    private fun activeRedirectFile(): Path =
        cacheRoot.resolve(ACTIVE_REDIRECT_FILE)

    private fun pendingRedirectFile(): Path =
        cacheRoot.resolve(PENDING_REDIRECT_FILE)

    private fun indexRoot(): Path =
        cacheRoot.resolve(TRANSLATION_INDEX_DIR)

    private fun activeIndexPointer(): Path =
        indexRoot().resolve(TranslationIndexStore.ACTIVE_POINTER)

    private fun japaneseSourceFor(resolvedTarget: String): String {
        val normalized = resolvedTarget.replace('\\', '/')
        val root = storage.rootPath().trimEnd('/').replace('\\', '/')
        val relative = normalized.removePrefix(root).removePrefix("/")
        val withoutLanguage = relative
            .removePrefix("en/")
            .removePrefix("kr/")
            .removePrefix("jp/")
        val slash = withoutLanguage.lastIndexOf('/')
        val directory = if (slash >= 0) withoutLanguage.substring(0, slash + 1) else ""
        val fileName = if (slash >= 0) withoutLanguage.substring(slash + 1) else withoutLanguage
        val baseName = LANGUAGE_PREFIX_REGEX.replace(fileName, "")
        return "$root/jp/${directory}JP_$baseName"
    }

    private fun collectTranslationIndexRecords(
        path: String,
        originalJson: String,
        translationJson: String
    ): List<TranslationIndexRecord> {
        return runCatching {
            TranslationRecordCollector().collect(path, originalJson, translationJson)
        }.getOrElse {
            runCatching { android.util.Log.w(TAG, "Skip invalid Japanese translation source path=$path", it) }
            emptyList()
        }
    }

    private fun writeCoverageAudit(
        patchDirectory: Path,
        manifest: PatchManifest,
        index: CompiledTranslationIndex,
        directory: Path
    ) {
        TranslationCoverageAudit(directory).use { audit ->
            val visitedSources = mutableSetOf<String>()
            val caseInsensitiveJp = buildCaseInsensitiveJapaneseLookup()
            manifest.entries.forEach { entry ->
                val translation = patchDirectory.resolve(entry.cachePath).toAbsolutePath().normalize()
                if (!translation.name.endsWith(".json", ignoreCase = true)) return@forEach
                val sourcePath = resolveJapaneseSource(storage.resolvePatchTarget(entry.targetPath), caseInsensitiveJp)
                visitedSources += sourcePath
                val resource = sourcePath.substringAfter("/Localize/", sourcePath)
                if (!translation.isRegularFile()) {
                    audit.file(resource, "missing_patch_file")
                    return@forEach
                }
                val source = storage.read(sourcePath)
                if (source == null) {
                    audit.file(resource, "missing_source_file")
                    return@forEach
                }
                runCatching {
                    audit.file(resource, "paired_resource")
                    TranslationRecordCollector(audit = true).collect(
                        resource, source.toString(Charsets.UTF_8), translation.readText(Charsets.UTF_8)
                    ).forEach { audit.record(it, index) }
                }.onFailure { audit.file(resource, "invalid_json") }
            }
            // Manifest-only checks miss entire new resource tables absent from an older patch.
            storage.listJapaneseResourcePaths().filterNot(visitedSources::contains).forEach { sourcePath ->
                val resource = sourcePath.substringAfter("/Localize/", sourcePath)
                audit.file(resource, "unpackaged_source_file")
                val source = storage.read(sourcePath) ?: return@forEach
                runCatching {
                    TranslationRecordCollector(audit = true).collect(resource, source.toString(Charsets.UTF_8), "{}")
                        .forEach { audit.record(it, index) }
                }.onFailure { audit.file(resource, "invalid_json") }
            }
        }
    }

    private fun saveBaseline(patchDirectory: Path, manifest: PatchManifest) {
        val baselineDirectory = patchDirectory.resolve(BASELINE_DIR)
        if (baselineDirectory.isDirectory()) {
            return
        }
        Files.createDirectories(baselineDirectory)
        manifest.entries.forEach { entry ->
            val target = storage.resolvePatchTarget(entry.targetPath)
            val snapshot = baselineDirectory.resolve(target.baselineName())
            val existing = storage.read(target)
            if (existing == null) {
                snapshot.resolveSibling("${snapshot.fileName}.missing").writeText(target, Charsets.UTF_8)
            } else {
                snapshot.outputStream().use { output -> output.write(existing) }
                snapshot.resolveSibling("${snapshot.fileName}.path").writeText(target, Charsets.UTF_8)
            }
        }
    }

    private fun restoreBaseline(manifest: PatchManifest): PatchUninstallSummary? {
        val baselineDirectory = latestPatchDirectory()?.resolve(BASELINE_DIR)
            ?: return null
        if (!baselineDirectory.isDirectory()) {
            return null
        }
        var restored = 0
        manifest.entries.forEach { entry ->
            val target = storage.resolvePatchTarget(entry.targetPath)
            val snapshot = baselineDirectory.resolve(target.baselineName())
            val missingMarker = snapshot.resolveSibling("${snapshot.fileName}.missing")
            if (missingMarker.isRegularFile()) {
                if (storage.exists(target) && storage.delete(target)) {
                    restored += 1
                }
            } else if (snapshot.isRegularFile()) {
                storage.write(target, snapshot)
                restored += 1
            }
        }
        return PatchUninstallSummary(version = manifest.requestedVersion, deletedFiles = restored)
    }

    /**
     * 锁定指定版本为当前用户主动选定的生效补丁。
     */
    fun pinActivePatch(version: String) {
        runCatching {
            cacheRoot.resolve(PINNED_PATCH_FILE).writeText(version.trim(), Charsets.UTF_8)
        }
    }

    /**
     * 获取当前被用户主动锁定的补丁版本（若有）。
     */
    fun pinnedPatchVersion(): String? {
        val file = cacheRoot.resolve(PINNED_PATCH_FILE)
        if (file.isRegularFile()) {
            val ver = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
            if (!ver.isNullOrBlank()) return ver
        }
        return null
    }

    /**
     * 激活并切换至指定版本的已导入补丁，锁定为当前生效包。
     */
    fun activatePatchByVersion(version: String, auditDirectory: Path? = null): PatchInstallSummary {
        val patchDirectory = cacheRoot.resolve("patch-cache").resolve(version)
        require(patchDirectory.isDirectory()) { "未找到指定的汉化包缓存: $version" }
        val manifestFile = patchDirectory.resolve("manifest.json")
        require(manifestFile.isRegularFile()) { "汉化包缺少 manifest.json: $manifestFile" }
        val manifest = PatchManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
        pinActivePatch(manifest.requestedVersion)
        return activateRedirect(patchDirectory, manifest, auditDirectory)
    }

    /**
     * 删除指定版本的已导入汉化包缓存，释放存储空间。
     * 若该版本当前处于激活或锁定状态，会自动安全停用汉化索引并解绑锁定。
     *
     * @param version 待删除的汉化包版本号
     * @return 是否成功删除该目录
     */
    fun deleteInstalledPatch(version: String): Boolean {
        val patchDirectory = cacheRoot.resolve("patch-cache").resolve(version)
        if (!patchDirectory.isDirectory()) return false

        val pinned = pinnedPatchVersion()
        if (pinned == version) {
            val pinFile = cacheRoot.resolve(PINNED_PATCH_FILE)
            Files.deleteIfExists(pinFile)
        }

        val activeVer = activeInstallSummary()?.version ?: pinned
        if (activeVer == version) {
            deactivateIndexOnly()
        }

        return patchDirectory.toFile().deleteRecursively()
    }

    /**
     * 列出所有已导入缓存的汉化包，并标注当前哪个包处于激活生效状态。
     */
    fun listInstalledPatches(): List<CachedPatchMetadata> {
        val patchRootDir = cacheRoot.resolve("patch-cache").toFile()
        if (!patchRootDir.isDirectory) return emptyList()
        val activeVer = activeInstallSummary()?.version ?: pinnedPatchVersion()
        val subDirs = patchRootDir.listFiles()?.filter { it.isDirectory && File(it, "manifest.json").isFile } ?: return emptyList()
        return subDirs.mapNotNull { dir ->
            runCatching {
                val manifestFile = File(dir, "manifest.json")
                val header = readManifestHeader(manifestFile) ?: return@runCatching null
                val metaFile = File(dir, LOCAL_PACK_META_FILE)
                val props = java.util.Properties().apply {
                    if (metaFile.isFile) metaFile.inputStream().use(::load)
                }
                CachedPatchMetadata(
                    version = header.first,
                    jsonFileCount = header.second,
                    notice = props.getProperty("notice").orEmpty(),
                    sourceLabel = props.getProperty("sourceLabel", "已导入汉化包"),
                    customFontName = props.getProperty("customFontName").orEmpty(),
                    isActive = (header.first == activeVer)
                )
            }.getOrNull()
        }.sortedWith(
            compareByDescending<CachedPatchMetadata> { it.isActive }
                .thenByDescending { it.version.toLongOrNull() ?: 0L }
        )
    }

    private fun readManifestHeader(manifestFile: File): Pair<String, Int>? {
        var requestedVersion: String? = null
        var extractedFileCount: Int? = null
        manifestFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (requestedVersion == null) {
                    val m = Regex(""""requestedVersion"\s*:\s*"([^"]+)"""").find(line)
                    if (m != null) requestedVersion = m.groupValues[1]
                }
                if (extractedFileCount == null) {
                    val m = Regex(""""extractedFileCount"\s*:\s*(\d+)""").find(line)
                    if (m != null) extractedFileCount = m.groupValues[1].toIntOrNull()
                }
                if (requestedVersion != null && extractedFileCount != null) {
                    break
                }
                if (line.contains(""""entries"""")) {
                    break
                }
            }
        }
        return if (requestedVersion != null) Pair(requestedVersion, extractedFileCount ?: 0) else null
    }

    private fun latestPatchDirectory(): Path? {
        val patchRoot = cacheRoot.resolve("patch-cache")
        if (!patchRoot.isDirectory()) {
            return null
        }
        val pinned = pinnedPatchVersion()
        if (pinned != null) {
            val target = patchRoot.resolve(pinned)
            if (target.isDirectory() && target.resolve("manifest.json").isRegularFile()) {
                return target
            }
        }
        return Files.list(patchRoot).use { paths ->
            paths
                .filter { it.isDirectory() && it.resolve("manifest.json").isRegularFile() }
                .sorted { left, right -> comparePatchDirectories(right, left) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun comparePatchDirectories(left: Path, right: Path): Int {
        val timeCmp = Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right))
        if (timeCmp != 0) return timeCmp
        val leftVersion = left.name.toLongOrNull()
        val rightVersion = right.name.toLongOrNull()
        if (leftVersion != null && rightVersion != null) {
            return leftVersion.compareTo(rightVersion)
        }
        return left.name.compareTo(right.name)
    }

    private fun removeLangOverlay(manifest: PatchManifest) {
        storage.deleteResource("Lang/config.json")
        manifest.entries.forEach { entry ->
            val relative = entry.sourcePath.removePrefix(UPSTREAM_LANG_PREFIX)
            if (relative != entry.sourcePath && relative.isNotBlank() && !relative.startsWith("Info/")) {
                storage.deleteResource("Lang/LLC_zh-CN/$relative")
            }
        }
    }

    private fun String.baselineName(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    companion object {
        private const val BASELINE_DIR = "baseline"
        private const val ACTIVE_REDIRECT_FILE = "active-redirects.tsv"
        private const val PENDING_REDIRECT_FILE = "pending-redirects.tsv"
        private const val ACTIVE_MARKER_FILE = ".active-redirect"
        private const val TRANSLATION_INDEX_DIR = "translation-index"
        private const val INDEX_MANIFEST_FILE = "manifest.properties"
        private const val JP_FINGERPRINT_FILE = "jp-fingerprint.txt"
        private const val PINNED_PATCH_FILE = "pinned_patch.name"
        const val LOCAL_PACK_META_FILE = "local-pack-meta.properties"
        private const val TAG = "LimbusZhCN"
        private const val UPSTREAM_LANG_PREFIX = "LimbusCompany_Data/Lang/LLC_zh-CN/"
        private val LANGUAGE_PREFIX_REGEX = Regex("^(EN|KR|JP)_")
    }

}

data class CachedPatchMetadata(
    val version: String,
    val jsonFileCount: Int,
    val notice: String = "",
    val sourceLabel: String = "",
    val customFontName: String = "",
    val isActive: Boolean = false
)

internal fun hasSufficientJapaneseSources(available: Int, candidate: Int): Boolean =
    candidate > 0 && available * 100L >= candidate * 90L
