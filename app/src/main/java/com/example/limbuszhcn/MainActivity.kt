package com.example.limbuszhcn

import com.lody.virtual.client.hook.delegate.LimbusOrientationManager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.example.limbuszhcn.container.AndroidInstalledGameImporter
import com.example.limbuszhcn.container.CachedPatchMetadata
import com.example.limbuszhcn.container.ContainerPatchInstaller
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.container.GameImportRequest
import com.example.limbuszhcn.container.LocalizeSnapshotExporter
import com.example.limbuszhcn.container.MicrogContainerConfig
import com.example.limbuszhcn.container.TranslationRedirectGate
import com.example.limbuszhcn.gamefs.GameResourceTransfer
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.ui.theme.LimbusZhCNTheme
import com.example.limbuszhcn.update.GeminiLangPackImporter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 承载 Limbus PE 虚拟容器汉化启动器的主界面，支持：
 * 1. VirtualApp 完整系统栈过 AppSealing 壳引导《边狱公司》；
 * 2. 内置 microG 支持容器内 Google 账号直登；
 * 3. `GEMINI_zh-CN` 本地 `.zip`/`.7z` 导入、文件夹导入与 `DropIn_Lang_Pack` 固定路径热更新；
 * 4. 原生 `ChineseFont.ttf` 动态 SDF 黑边字体挂载与每周四热更自动重编索引。
 */
class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        PersistentDiagnosticLog.info(
            TAG,
            "Notification permission result granted=$granted"
        )
    }

    private val containerRuntime by lazy {
        AndroidContainerRuntimeFactory.create(
            applicationContext,
            filesDir.toPath().resolve("container-workspace")
        )
    }
    private val deviceCompatibility by lazy { DeviceCompatibility(applicationContext) }
    private val translationCacheRoot by lazy { filesDir.toPath().resolve("translation-cache") }
    private val geminiImporter by lazy { GeminiLangPackImporter(translationCacheRoot) }

    @Volatile
    private var pendingGameRestartForNewIndex: Boolean = false

    private var onResumeGameStateRefresh: (() -> Unit)? = null

    private fun readContainerStatus(): ContainerStatus =
        runCatching {
            val status = containerRuntime.status()
            val installedVersion = runCatching {
                AndroidInstalledGameImporter.installedVersionCode(packageManager)
            }.getOrNull()
            status.withInstalledVersion(installedVersion)
        }.getOrElse { error ->
            PersistentDiagnosticLog.error(TAG, "readContainerStatus failed", error)
            ContainerStatus(
                available = false,
                gameImported = false,
                message = "读取容器状态异常: ${error.userMessage()}"
            )
        }

    private fun createInstaller(): ContainerPatchInstaller =
        ContainerPatchInstaller(
            cacheRoot = translationCacheRoot,
            storage = containerRuntime.storage()
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistentDiagnosticLog.info(
            TAG,
            "MainActivity.onCreate pid=${Process.myPid()} taskId=$taskId intent=${intent.describeForLog()}"
        )
        LauncherTaskWatcherService.start(applicationContext)
        launchContainerGameFromDebugIntent(intent)
        enableEdgeToEdge()

        val dropInDir = runCatching {
            geminiImporter.ensureDropInDirectory(applicationContext)
        }.onFailure { error ->
            PersistentDiagnosticLog.warn(TAG, "ensureDropInDirectory failed", error)
        }.getOrElse { filesDir.resolve("DropIn_Lang_Pack").apply { mkdirs() } }
        val preferences = getSharedPreferences("limbus_settings", MODE_PRIVATE)
        preferences.edit()
            .remove(LEGACY_KEY_RELAY_BASE_URL)
            .remove(LEGACY_KEY_TOKEN)
            .remove(LEGACY_KEY_TRANSLATION_CHANNEL)
            .apply()
        requestNotificationPermissionOnce(preferences)

        setContent {
            var containerStatus by remember { mutableStateOf(readContainerStatus()) }
            var lastInstall by remember {
                mutableStateOf(runCatching { createInstaller().activeInstallSummary() }.getOrNull())
            }
            var cachedMeta by remember {
                mutableStateOf(runCatching { createInstaller().latestCachedPatchMetadata() }.getOrNull())
            }
            var fontStatus by remember {
                mutableStateOf(runCatching { geminiImporter.describeActiveFont() }.getOrDefault("未检测"))
            }
            var translationEnabled by remember {
                mutableStateOf(preferences.getBoolean(KEY_TRANSLATION_ENABLED, true))
            }
            var compatibilityReport by remember {
                mutableStateOf(runCatching { deviceCompatibility.report() }.getOrElse { DeviceCompatibility(applicationContext).report() })
            }
            var githubReleaseUrl by remember {
                mutableStateOf(preferences.nonBlankString(KEY_GITHUB_RELEASE_URL, DEFAULT_GITHUB_RELEASE_URL))
            }
            var busy by remember { mutableStateOf(false) }
            var busyMessage by remember { mutableStateOf("正在准备容器与汉化环境...") }
            var gameRunning by remember {
                mutableStateOf(runCatching { containerRuntime.isGameRunning() }.getOrDefault(false))
            }
            var resourceCancellation by remember { mutableStateOf<AtomicBoolean?>(null) }
            var pendingDiagnosticArchive by remember { mutableStateOf<File?>(null) }
            var showPackSelectionDialog by remember { mutableStateOf(false) }
            var isPackManageMode by remember { mutableStateOf(false) }
            var packToDelete by remember { mutableStateOf<CachedPatchMetadata?>(null) }
            var candidateToDelete by remember { mutableStateOf<GeminiLangPackImporter.DropInCandidate?>(null) }
            var screenOrientationMode by remember {
                mutableStateOf(LimbusOrientationManager.getOrientationMode(this@MainActivity))
            }
            var installedPatches by remember { mutableStateOf<List<CachedPatchMetadata>>(emptyList()) }
            var dropInCandidates by remember { mutableStateOf<List<GeminiLangPackImporter.DropInCandidate>>(emptyList()) }
            var message by remember {
                mutableStateOf(containerStatus.message)
            }

            fun syncUiSnapshots() {
                val installer = runCatching { createInstaller() }.getOrNull()
                val status = readContainerStatus()
                val running = runCatching { containerRuntime.isGameRunning() }.getOrDefault(false)
                val active = runCatching { installer?.activeInstallSummary() }.getOrNull()
                val meta = runCatching { installer?.latestCachedPatchMetadata() }.getOrNull()
                val font = runCatching { geminiImporter.describeActiveFont() }.getOrDefault("未检测")
                val report = runCatching { deviceCompatibility.report() }.getOrNull()
                val patches = runCatching { installer?.listInstalledPatches() }.getOrNull().orEmpty()
                val candidates = runCatching { geminiImporter.listDropInCandidates(applicationContext) }.getOrNull().orEmpty()
                runOnUiThread {
                    containerStatus = status
                    gameRunning = running
                    lastInstall = active
                    cachedMeta = meta
                    fontStatus = font
                    installedPatches = patches
                    dropInCandidates = candidates
                    if (report != null) {
                        compatibilityReport = report
                    }
                }
            }

            onResumeGameStateRefresh = {
                Thread {
                    val running = runCatching {
                        containerRuntime.isGameRunning()
                    }.getOrDefault(false)
                    val report = runCatching {
                        deviceCompatibility.report()
                    }.getOrNull()
                    runOnUiThread {
                        gameRunning = running
                        if (report != null) {
                            compatibilityReport = report
                        }
                    }
                }.start()
            }

            fun runTask(progressMessage: String = "正在处理，请稍候...", block: () -> String) {
                busy = true
                busyMessage = progressMessage
                message = progressMessage
                PersistentDiagnosticLog.info(TAG, "Task started stage=$progressMessage")
                Thread {
                    val result = runCatching(block).fold(
                        onSuccess = { resultMessage ->
                            PersistentDiagnosticLog.info(
                                TAG,
                                "Task completed stage=$progressMessage result=$resultMessage"
                            )
                            resultMessage
                        },
                        onFailure = { error ->
                            PersistentDiagnosticLog.error(TAG, "Task failed stage=$progressMessage", error)
                            error.userMessage()
                        }
                    )
                    syncUiSnapshots()
                    runOnUiThread {
                        busy = false
                        message = result
                    }
                }.start()
            }

            fun installResultMessage(summary: PatchInstallSummary, sourceNote: String = ""): String {
                val prefix = if (sourceNote.isNotBlank()) "[$sourceNote] " else ""
                return when {
                    summary.indexActivated ->
                        "${prefix}汉化 v${summary.version} 已编译激活（匹配日文表 ${summary.availableSourceFiles}/${summary.candidateSourceFiles}，共 ${summary.sourceRecordCount} 条记录）"
                    summary.previousIndexPreserved ->
                        "${prefix}汉化包 v${summary.version} 已导入缓存；容器内游戏资源尚未下载完整，保留现有索引"
                    else ->
                        "${prefix}汉化包 v${summary.version}（${summary.candidateSourceFiles} 个 JSON）与字体已导入就绪！首次请先点击下方启动进入游戏完成登录与资源下载，下载后再次启动即自动生效中文"
                }
            }

            val openArchiveLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    runTask("正在解压并导入本地汉化压缩包...") {
                        val imported = geminiImporter.importFromArchiveUri(
                            context = applicationContext,
                            uri = uri,
                            sourceLabel = "本地压缩包"
                        )
                        pendingGameRestartForNewIndex = true
                        if (translationEnabled) {
                            val summary = createInstaller().activateRedirect(
                                imported.extraction.outputDirectory,
                                imported.extraction.manifest
                            )
                            installResultMessage(summary, imported.sourceLabel)
                        } else {
                            "已导入汉化包 v${imported.version}（当前已关闭汉化开关，开启后生效）"
                        }
                    }
                }
            }

            val openFolderLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { treeUri: Uri? ->
                if (treeUri != null) {
                    runTask("正在扫描并导入本地汉化文件夹...") {
                        val imported = geminiImporter.importFromDirectoryUri(
                            context = applicationContext,
                            treeUri = treeUri,
                            sourceLabel = "本地文件夹"
                        )
                        pendingGameRestartForNewIndex = true
                        if (translationEnabled) {
                            val summary = createInstaller().activateRedirect(
                                imported.extraction.outputDirectory,
                                imported.extraction.manifest
                            )
                            installResultMessage(summary, imported.sourceLabel)
                        } else {
                            "已从文件夹导入汉化包 v${imported.version}（开启汉化开关后生效）"
                        }
                    }
                }
            }

            val createDiagnosticDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip")
            ) { uri ->
                val archive = pendingDiagnosticArchive
                pendingDiagnosticArchive = null
                if (uri == null || archive == null) {
                    message = "已取消保存诊断包"
                    return@rememberLauncherForActivityResult
                }
                busy = true
                busyMessage = "正在保存诊断包..."
                Thread {
                    val saveResult = runCatching {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            archive.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("无法打开所选保存位置")
                    }
                    archive.delete()
                    runOnUiThread {
                        busy = false
                        message = saveResult.fold(
                            onSuccess = { "诊断日志包已保存" },
                            onFailure = { error -> error.userMessage() }
                        )
                    }
                }.start()
            }

            fun prepareGame(forceSync: Boolean = false): String {
                val current = readContainerStatus()
                val installedVersion = runCatching {
                    AndroidInstalledGameImporter.installedVersionCode(packageManager)
                }.getOrElse { error ->
                    if (error is android.content.pm.PackageManager.NameNotFoundException) {
                        error("未检测到宿主机已安装的《边狱公司》，请先在手机安装 Google Play 正版游戏")
                    }
                    throw error
                }
                val shouldSync = forceSync ||
                    !current.gameImported ||
                    current.importedVersionCode == null ||
                    current.importedVersionCode != installedVersion ||
                    current.requiresInstalledGameResync

                if (!shouldSync) {
                    return "正版游戏版本已匹配 (v$installedVersion)"
                }

                val installed = AndroidInstalledGameImporter(
                    packageManager = packageManager,
                    importDirectory = File(filesDir, "container-imports")
                ).copyInstalledGame()
                val result = containerRuntime.importGame(
                    GameImportRequest(
                        splitApkPaths = installed.splitApkPaths,
                        versionCode = installed.versionCode,
                        source = "installed"
                    )
                )
                return "已无损同步正版游戏 v${installed.versionCode}（${result.importedSplits} 个分包）与内置 microG"
            }

            fun launchGameWithChecks(forceRestart: Boolean): String {
                val startedAt = SystemClock.elapsedRealtime()
                fun updateLaunchStage(stage: String) {
                    val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
                    PersistentDiagnosticLog.info(
                        TAG,
                        "Launch preparation stage=$stage elapsedMs=$elapsedMillis forceRestart=$forceRestart"
                    )
                    runOnUiThread {
                        busyMessage = stage
                        message = stage
                    }
                }

                var gameStopped = false
                if (forceRestart && (gameRunning || containerStatus.gameImported)) {
                    updateLaunchStage("步骤 1/4：停止当前游戏任务...")
                    containerRuntime.stopGameForResourceTransfer()
                    gameStopped = true
                }
                updateLaunchStage("步骤 1/4：校验容器和正版游戏版本...")
                val prepared = prepareGame(forceSync = false)
                val installer = createInstaller()

                updateLaunchStage("步骤 2/4：检查 DropIn 汉化更新...")
                val droppedIn = runCatching {
                    geminiImporter.scanAutoImportSources(applicationContext)
                }.getOrNull()
                if (droppedIn != null) {
                    pendingGameRestartForNewIndex = true
                }

                var patchNote = ""
                if (!translationEnabled) {
                    updateLaunchStage("步骤 3/4：检查汉化开关...")
                    if (installer.activeInstallSummary() != null) {
                        installer.deactivateIndexOnly()
                        pendingGameRestartForNewIndex = true
                    }
                    patchNote = "纯净官方原版模式"
                } else if (installer.latestCachedPatchMetadata() != null) {
                    updateLaunchStage("步骤 3/4：检查汉化索引状态...")
                    val needsRecompile =
                        droppedIn != null || installer.shouldRecompileForCurrentResources()
                    if (needsRecompile) {
                        updateLaunchStage("步骤 4/4：更新汉化索引...")
                        val summary = installer.refreshActiveRedirects()
                        if (summary.indexActivated) {
                            pendingGameRestartForNewIndex = true
                        }
                        patchNote = installResultMessage(summary)
                    } else {
                        val active = installer.activeInstallSummary()
                        patchNote = if (active != null && active.indexActivated) {
                            "汉化 v${active.version} 已挂载"
                        } else {
                            "汉化包已缓存（首次进入游戏下载资源后下次启动即自动生效）"
                        }
                    }
                } else {
                    updateLaunchStage("步骤 3/4：检查汉化包缓存...")
                    patchNote = "尚未导入汉化包（将以原版启动）"
                }

                if (!gameStopped && pendingGameRestartForNewIndex) {
                    runCatching { containerRuntime.stopGameForResourceTransfer() }
                    pendingGameRestartForNewIndex = false
                    gameStopped = true
                }

                updateLaunchStage("正在启动《边狱公司》...")
                containerRuntime.launchGame()
                val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
                return "$prepared；$patchNote；启动前检查耗时 ${elapsedMillis}ms"
            }

            fun runResourceTransfer(uri: Uri, exporting: Boolean) {
                val cancellation = AtomicBoolean(false)
                resourceCancellation = cancellation
                busy = true
                busyMessage = if (exporting) "正在准备迁出容器游戏资源" else "正在准备迁入容器游戏资源"
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Thread {
                    val result = runCatching {
                        if (!exporting) prepareGame()
                        val status = readContainerStatus()
                        require(status.gameImported) { "请先同步游戏到容器" }
                        containerRuntime.stopGameForResourceTransfer()
                        val transfer = GameResourceTransfer(
                            storage = containerRuntime.storage(),
                            stagingRoot = filesDir.toPath().resolve("resource-transfers"),
                            gameVersion = requireNotNull(status.importedVersionCode) { "游戏版本未知，请先重新同步游戏" },
                            cancelled = cancellation::get,
                            progress = { update ->
                                runOnUiThread {
                                    busyMessage = if (update.phase == "正在合并资源") {
                                        "${update.phase}：${update.bytes}/${update.total} 个文件"
                                    } else {
                                        "${update.phase}：${update.bytes / (1024 * 1024)} / ${update.total / (1024 * 1024)} MiB"
                                    }
                                }
                            }
                        )
                        if (exporting) {
                            try {
                                contentResolver.openOutputStream(uri, "w").use { output ->
                                    transfer.exportResources(requireNotNull(output) { "无法打开所选保存位置" })
                                }
                            } catch (error: Throwable) {
                                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                                throw error
                            }
                        } else {
                            contentResolver.openInputStream(uri).use { input ->
                                transfer.importResources(requireNotNull(input) { "无法打开所选迁移包" })
                            }
                        }
                    }
                    syncUiSnapshots()
                    runOnUiThread {
                        resourceCancellation = null
                        busy = false
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        message = result.fold(
                            onSuccess = { summary ->
                                if (exporting) "已迁出 ${summary.files} 个资源文件，可在换机后迁入" else
                                    "已迁入 ${summary.files} 个资源文件（保留 ${summary.keptExisting} 个已有文件）"
                            },
                            onFailure = { error -> error.userMessage() }
                        )
                    }
                }.start()
            }

            val createResourceDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip")
            ) { uri ->
                if (uri != null) runResourceTransfer(uri, exporting = true)
            }
            val openResourceDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) runResourceTransfer(uri, exporting = false)
            }

            LimbusZhCNTheme {
                LimbusPeScreenRedesign(
                    containerStatus = containerStatus,
                    lastInstall = lastInstall,
                    cachedMeta = cachedMeta,
                    fontStatus = fontStatus,
                    dropInPath = dropInDir.absolutePath,
                    translationEnabled = translationEnabled,
                    compatibilityReport = compatibilityReport,
                    githubReleaseUrl = githubReleaseUrl,
                    busy = busy,
                    busyMessage = busyMessage,
                    gameRunning = gameRunning,
                    message = message,
                    onCancelTask = resourceCancellation?.let { flag ->
                        { flag.set(true); busyMessage = "正在取消资源迁移..." }
                    },
                    onToggleTranslationEnabled = { enabled ->
                        translationEnabled = enabled
                        preferences.edit().putBoolean(KEY_TRANSLATION_ENABLED, enabled).apply()
                        runTask(if (enabled) "正在重新激活汉化索引..." else "正在切换为纯净官方原版模式...") {
                            val installer = createInstaller()
                            pendingGameRestartForNewIndex = true
                            if (!enabled) {
                                val ver = installer.deactivateIndexOnly()
                                if (ver != null) "已停用汉化 v$ver（缓存已保留，下次启动将以官方原版运行）"
                                else "已切换为官方原版模式"
                            } else {
                                if (installer.latestCachedPatchMetadata() != null) {
                                    val summary = installer.refreshActiveRedirects()
                                    installResultMessage(summary)
                                } else {
                                    "已开启汉化模式，请导入中文汉化包"
                                }
                            }
                        }
                    },
                    onManagePacksClick = {
                        showPackSelectionDialog = true
                        Thread {
                            val installer = runCatching { createInstaller() }.getOrNull()
                            val patches = runCatching { installer?.listInstalledPatches() }.getOrNull().orEmpty()
                            val candidates = runCatching { geminiImporter.listDropInCandidates(applicationContext) }.getOrNull().orEmpty()
                            runOnUiThread {
                                installedPatches = patches
                                dropInCandidates = candidates
                            }
                        }.start()
                    },
                    onImportArchiveClick = {
                        openArchiveLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/x-7z-compressed",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onImportFolderClick = {
                        openFolderLauncher.launch(null)
                    },
                    onScanDropInAndRefreshClick = {
                        runTask("正在扫描热投递目录并对齐游戏资源...") {
                            val autoImported = geminiImporter.scanAutoImportSources(applicationContext)
                            val installer = createInstaller()
                            if (autoImported != null) {
                                pendingGameRestartForNewIndex = true
                                if (translationEnabled) {
                                    val summary = installer.activateRedirect(
                                        autoImported.extraction.outputDirectory,
                                        autoImported.extraction.manifest
                                    )
                                    installResultMessage(summary, autoImported.sourceLabel)
                                } else {
                                    "已从热投递目录导入 v${autoImported.version}"
                                }
                            } else if (translationEnabled && installer.latestCachedPatchMetadata() != null) {
                                val summary = installer.refreshActiveRedirects()
                                pendingGameRestartForNewIndex = summary.indexActivated
                                installResultMessage(summary, "重新对齐容器日文表")
                            } else {
                                "热投递目录暂无新压缩包；当前状态已刷新"
                            }
                        }
                    },
                    onLaunchGameClick = {
                        if (gameRunning) {
                            Thread {
                                val resumed = runCatching {
                                    containerRuntime.resumeGameTask()
                                }.getOrDefault(false)
                                runOnUiThread {
                                    gameRunning = resumed
                                    message = if (resumed) {
                                        "已返回正在运行的《边狱公司》"
                                    } else {
                                        "未找到可恢复的游戏任务，请使用“重新启动并校验”"
                                    }
                                }
                            }.start()
                        } else {
                            runTask("正在校验沙箱环境、microG 与汉化索引...") {
                                launchGameWithChecks(forceRestart = false)
                            }
                        }
                    },
                    onRestartGameClick = {
                        runTask("正在重新启动并校验游戏环境...") {
                            launchGameWithChecks(forceRestart = true)
                        }
                    },
                    onRequestBatteryOptimization = {
                        runCatching {
                            startActivity(deviceCompatibility.batteryOptimizationRequestIntent())
                            PersistentDiagnosticLog.info(
                                TAG,
                                "Opened battery optimization exemption request"
                            )
                            message = "请在系统页面允许《Limbus 汉化容器》后台运行"
                        }.onFailure { error ->
                            PersistentDiagnosticLog.error(
                                TAG,
                                "Unable to open battery optimization request",
                                error
                            )
                            message = error.userMessage()
                        }
                    },
                    onOpenCompatibilitySettings = {
                        runCatching {
                            startActivity(deviceCompatibility.settingsIntent())
                            PersistentDiagnosticLog.info(TAG, "Opened device compatibility settings")
                            message = "请在系统设置中允许本应用的「自启动」「后台高耗电」与「关联启动」"
                        }.onFailure { error ->
                            PersistentDiagnosticLog.error(TAG, "Unable to open compatibility settings", error)
                            message = error.userMessage()
                        }
                    },
                    onSyncInstalledGame = {
                        runTask("正在强制重新同步正版游戏与内置 microG...") {
                            val prepared = prepareGame(forceSync = true)
                            runCatching { containerRuntime.stopGameForResourceTransfer() }
                            "$prepared；可点击下方按钮启动游戏"
                        }
                    },
                    onGithubReleaseUrlChange = { githubReleaseUrl = it },
                    onPullFromGithubClick = {
                        runTask("正在从在线 Releases 下载并更新汉化...") {
                            preferences.edit().putString(KEY_GITHUB_RELEASE_URL, githubReleaseUrl.trim()).apply()
                            require(readContainerStatus().gameImported) { "请先点击一次启动或同步正版游戏以初始化容器" }
                            val url = githubReleaseUrl.trim()
                            require(url.isNotBlank()) { "请填写有效的 GitHub Releases 地址" }
                            val summary = createInstaller().installFromGitHub(
                                releaseUrl = url,
                                downloadDirectory = dropInDir.toPath()
                            )
                            pendingGameRestartForNewIndex = true
                            installResultMessage(summary, "在线下载汉化包")
                        }
                    },
                    screenOrientationMode = screenOrientationMode,
                    onScreenOrientationModeChange = { mode ->
                        screenOrientationMode = mode
                        LimbusOrientationManager.setOrientationMode(this@MainActivity, mode)
                    },
                    onExportResources = {
                        val stamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        createResourceDocument.launch("LimbusResources-$stamp.zip")
                    },
                    onImportResources = {
                        openResourceDocument.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onExportDebugLogs = {
                        busy = true
                        busyMessage = "正在打包脱敏诊断日志..."
                        message = busyMessage
                        Thread {
                            val result = runCatching {
                                DebugLogExporter(applicationContext).export(
                                    containerStatus = readContainerStatus(),
                                    activeTranslation = lastInstall
                                )
                            }
                            runOnUiThread {
                                busy = false
                                result.fold(
                                    onSuccess = { archive ->
                                        pendingDiagnosticArchive = archive
                                        message = "诊断日志包已生成，请选择保存位置"
                                        createDiagnosticDocument.launch(archive.name)
                                    },
                                    onFailure = { error ->
                                        message = error.userMessage()
                                    }
                                )
                            }
                        }.start()
                    }
                )

                if (showPackSelectionDialog) {
                    val packCardShape = CutCornerShape(
                        topStart = 0.dp,
                        topEnd = 8.dp,
                        bottomEnd = 0.dp,
                        bottomStart = 8.dp
                    )
                    AlertDialog(
                        onDismissRequest = {
                            showPackSelectionDialog = false
                            isPackManageMode = false
                        },
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(20.dp)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "汉化包管理与切换",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                                Surface(
                                    shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
                                    color = if (isPackManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isPackManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier.clickable {
                                        isPackManageMode = !isPackManageMode
                                    }
                                ) {
                                    Text(
                                        text = if (isPackManageMode) "✓ 完成" else "⚙ 管理",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPackManageMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 480.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (isPackManageMode) {
                                        "⚠️ 管理模式已开启：点击任意条目右侧「删除」可清理不需要的历史包，释放存储空间："
                                    } else {
                                        "点击即可切换生效对应汉化包；启动游戏时将锁定所选版本，防止被覆盖："
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isPackManageMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )

                                if (dropInCandidates.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(13.dp)
                                                .background(Color(0xFFE07A5F))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "热投递专区待导入 (${dropInCandidates.size})",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    dropInCandidates.forEach { candidate ->
                                        Surface(
                                            shape = packCardShape,
                                            color = Color(0xFF1E1E23),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 64.dp)
                                                .clickable {
                                                    if (isPackManageMode) {
                                                        candidateToDelete = candidate
                                                    } else {
                                                        showPackSelectionDialog = false
                                                        runTask("正在解压并切换至汉化包 ${candidate.name}...") {
                                                            val imported = if (candidate.isArchive) {
                                                                geminiImporter.importFromArchiveFile(
                                                                    archiveFile = candidate.file,
                                                                    originalNameHint = candidate.name,
                                                                    sourceLabel = "热投递专区 (${candidate.name})"
                                                                )
                                                            } else {
                                                                geminiImporter.importFromLocalDirectory(
                                                                    sourceDir = candidate.file.toPath(),
                                                                    sourceLabel = "热投递文件夹 (${candidate.name})"
                                                                )
                                                            }
                                                            pendingGameRestartForNewIndex = true
                                                            val summary = createInstaller().activateRedirect(
                                                                imported.extraction.outputDirectory,
                                                                imported.extraction.manifest
                                                            )
                                                            installResultMessage(summary, imported.sourceLabel)
                                                        }
                                                    }
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Text(
                                                        text = candidate.name,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    val sizeStr = if (candidate.sizeBytes > 0) {
                                                        String.format(java.util.Locale.US, "%.1f MB", candidate.sizeBytes / (1024.0 * 1024.0))
                                                    } else {
                                                        "文件夹"
                                                    }
                                                    val verStr = if (candidate.versionHint.isNotBlank()) " · v${candidate.versionHint}" else ""
                                                    Text(
                                                        text = "$sizeStr$verStr · 待导入",
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontSize = 11.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                if (isPackManageMode) {
                                                    Surface(
                                                        shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
                                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                                    ) {
                                                        Text(
                                                            text = "✕ 删除",
                                                            color = MaterialTheme.colorScheme.error,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                        )
                                                    }
                                                } else {
                                                    Surface(
                                                        shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                                    ) {
                                                        Text(
                                                            text = "导入并生效",
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(13.dp)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "已导入本地缓存 (${installedPatches.size})",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (installedPatches.isEmpty()) {
                                    Text(
                                        text = "暂无已导入的汉化包，可通过“导入 .zip/.7z”或在线下载获取",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    installedPatches.forEach { patch ->
                                        Surface(
                                            shape = packCardShape,
                                            color = if (patch.isActive) Color(0xFF2B191C) else Color(0xFF1B1B1E),
                                            border = BorderStroke(
                                                if (patch.isActive) 1.5.dp else 1.dp,
                                                if (patch.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 64.dp)
                                                .clickable {
                                                    if (isPackManageMode) {
                                                        packToDelete = patch
                                                    } else if (!patch.isActive) {
                                                        showPackSelectionDialog = false
                                                        runTask("正在切换至汉化包 v${patch.version}...") {
                                                            val summary = createInstaller().activatePatchByVersion(patch.version)
                                                            pendingGameRestartForNewIndex = true
                                                            installResultMessage(summary, "切换汉化包")
                                                        }
                                                    }
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (patch.isActive) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(3.5.dp)
                                                            .height(36.dp)
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.width(3.5.dp))
                                                }
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            start = 11.dp,
                                                            end = 14.dp,
                                                            top = 12.dp,
                                                            bottom = 12.dp
                                                        ),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        Text(
                                                            text = "版本 v${patch.version}",
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (patch.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = "${patch.sourceLabel} · ${patch.jsonFileCount} 个 JSON 文件",
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            fontSize = 11.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    if (isPackManageMode) {
                                                        Surface(
                                                            shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
                                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                                        ) {
                                                            Text(
                                                                text = "✕ 删除",
                                                                color = MaterialTheme.colorScheme.error,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                            )
                                                        }
                                                    } else if (patch.isActive) {
                                                        Surface(
                                                            shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
                                                            color = MaterialTheme.colorScheme.primary
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(6.dp)
                                                                        .clip(RoundedCornerShape(3.dp))
                                                                        .background(MaterialTheme.colorScheme.onPrimary)
                                                                )
                                                                Spacer(modifier = Modifier.width(5.dp))
                                                                Text(
                                                                    text = "当前生效",
                                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        Surface(
                                                            shape = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
                                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                                        ) {
                                                            Text(
                                                                text = "点击切换",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                fontWeight = FontWeight.SemiBold,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPackSelectionDialog = false
                                isPackManageMode = false
                            }) {
                                Text("关闭")
                            }
                        }
                    )
                }

                val targetPack = packToDelete
                if (targetPack != null) {
                    AlertDialog(
                        onDismissRequest = { packToDelete = null },
                        title = {
                            Text(
                                text = if (targetPack.isActive) "⚠️ 警告：删除当前生效包" else "确认删除汉化包？",
                                fontWeight = FontWeight.Bold,
                                color = if (targetPack.isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            Text(
                                text = if (targetPack.isActive) {
                                    "汉化包 v${targetPack.version} 当前正在生效中！\n删除后将停用汉化模式并清除运行时索引，需重启游戏生效。\n\n是否确认永久删除？"
                                } else {
                                    "确定要删除汉化包 v${targetPack.version} 吗？\n将彻底清除该版本的本地解压缓存（${targetPack.jsonFileCount} 个文件），释放存储空间。"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val toDel = targetPack
                                    packToDelete = null
                                    runTask("正在删除汉化包 v${toDel.version}...") {
                                        val installer = createInstaller()
                                        installer.deleteInstalledPatch(toDel.version)
                                        val refreshed = runCatching { installer.listInstalledPatches() }.getOrNull().orEmpty()
                                        runOnUiThread {
                                            installedPatches = refreshed
                                        }
                                        if (toDel.isActive) {
                                            pendingGameRestartForNewIndex = true
                                            "已删除当前生效的汉化包并停用汉化，请重启游戏或选择其它汉化包"
                                        } else {
                                            "已成功删除汉化包 v${toDel.version}，释放了存储空间"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp)
                            ) {
                                Text("确认删除", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { packToDelete = null }) {
                                Text("取消")
                            }
                        }
                    )
                }

                val targetCandidate = candidateToDelete
                if (targetCandidate != null) {
                    AlertDialog(
                        onDismissRequest = { candidateToDelete = null },
                        title = {
                            Text(
                                text = "确认删除待导入文件？",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                text = "确定要从热投递专区永久删除「${targetCandidate.name}」吗？",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val toDel = targetCandidate
                                    candidateToDelete = null
                                    runTask("正在删除待导入文件 ${toDel.name}...") {
                                        geminiImporter.deleteDropInCandidate(toDel)
                                        val refreshed = runCatching { geminiImporter.listDropInCandidates(applicationContext) }.getOrNull().orEmpty()
                                        runOnUiThread {
                                            dropInCandidates = refreshed
                                        }
                                        "已从热投递专区删除 ${toDel.name}"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp)
                            ) {
                                Text("确认删除", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { candidateToDelete = null }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "MainActivity.onNewIntent pid=${Process.myPid()} taskId=$taskId intent=${intent.describeForLog()} stack=${shortStack()}")
        launchContainerGameFromDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        onResumeGameStateRefresh?.invoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "MainActivity.onDestroy pid=${Process.myPid()} taskId=$taskId finishing=$isFinishing")
        onResumeGameStateRefresh = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LAUNCH_CONTAINER_GAME = "launch_container_game"
        private const val EXTRA_RESTART_CONTAINER_GAME = "restart_container_game"
        private const val EXTRA_SYNC_INSTALLED_GAME = "sync_installed_game"
        private const val EXTRA_EXPORT_LOCALIZE_SNAPSHOT = "export_localize_snapshot"
        private const val EXTRA_REFRESH_TRANSLATION_REDIRECTS = "refresh_translation_redirects"
        private const val EXTRA_AUDIT_TRANSLATION_INDEX = "audit_translation_index"
        private const val EXTRA_ENABLE_TRANSLATION_REDIRECTS = "enable_translation_redirects"
        private const val EXTRA_MICROG_APK_PATHS = "microg_apk_paths"
        private const val EXTRA_MICROG_APK_URLS = "microg_apk_urls"
        private const val KEY_GITHUB_RELEASE_URL = "github_release_url"
        private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val LEGACY_KEY_RELAY_BASE_URL = "relay_base_url"
        private const val LEGACY_KEY_TOKEN = "token"
        private const val LEGACY_KEY_TRANSLATION_CHANNEL = "translation_channel"
        private const val DEFAULT_GITHUB_RELEASE_URL = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases"
        private const val TAG = "LimbusZhCN"
    }

    private fun launchContainerGameFromDebugIntent(intent: Intent?) {
        if (BuildConfig.BUILD_TYPE != "debug" || intent == null || !intent.hasDebugContainerAction()) {
            return
        }
        Thread {
            runCatching {
                configureMicrogFromDebugIntent(intent)
                if (intent.getBooleanExtra(EXTRA_SYNC_INSTALLED_GAME, false)) {
                    val installed = AndroidInstalledGameImporter(
                        packageManager = packageManager,
                        importDirectory = File(filesDir, "container-imports")
                    ).copyInstalledGame()
                    containerRuntime.importGame(
                        GameImportRequest(
                            splitApkPaths = installed.splitApkPaths,
                            versionCode = installed.versionCode,
                            source = "installed"
                        )
                    )
                }
                if (intent.getBooleanExtra(EXTRA_EXPORT_LOCALIZE_SNAPSHOT, false)) {
                    val storage = containerRuntime.storage()
                    require(storage is com.example.limbuszhcn.container.ContainerWorkspaceStorage) {
                        "Localize snapshot export requires container workspace storage"
                    }
                    LocalizeSnapshotExporter(applicationContext, storage).exportSnapshot()
                }
                if (intent.getBooleanExtra(EXTRA_REFRESH_TRANSLATION_REDIRECTS, false) ||
                    intent.getBooleanExtra(EXTRA_AUDIT_TRANSLATION_INDEX, false)) {
                    val auditDirectory = if (intent.getBooleanExtra(EXTRA_AUDIT_TRANSLATION_INDEX, false)) {
                        File(requireNotNull(getExternalFilesDir(null)), "diagnostics/translation-coverage").toPath()
                    } else null
                    createInstaller().refreshActiveRedirects(auditDirectory)
                }
                if (intent.getBooleanExtra(EXTRA_RESTART_CONTAINER_GAME, false)) {
                    containerRuntime.stopGameForResourceTransfer()
                    containerRuntime.launchGame()
                } else if (intent.getBooleanExtra(EXTRA_LAUNCH_CONTAINER_GAME, false)) {
                    containerRuntime.launchGame()
                }
                if (intent.getBooleanExtra(EXTRA_ENABLE_TRANSLATION_REDIRECTS, false)) {
                    require(!intent.getBooleanExtra(EXTRA_LAUNCH_CONTAINER_GAME, false)) {
                        "Enable translation redirects only after the running game finishes resource validation"
                    }
                    TranslationRedirectGate(applicationContext).openForRunningGame()
                }
            }.onSuccess {
                recordDebugIntentResult("SUCCESS")
            }.onFailure { error ->
                recordDebugIntentResult("FAILURE", error)
                Log.e(TAG, "Debug intent launch failed", error)
            }
        }.start()
    }

    private fun requestNotificationPermissionOnce(preferences: android.content.SharedPreferences) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED ||
            preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        ) {
            return
        }
        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun recordDebugIntentResult(status: String, error: Throwable? = null) {
        runCatching {
            File(filesDir, "debug-intent-result.txt").writeText(
                buildString {
                    appendLine("status=$status")
                    appendLine("time=${System.currentTimeMillis()}")
                    if (error != null) {
                        appendLine("type=${error.javaClass.name}")
                        appendLine("message=${error.message.orEmpty()}")
                        append(error.stackTraceToString())
                    }
                }
            )
        }
    }

    private fun configureMicrogFromDebugIntent(intent: Intent) {
        val rawUrls = intent.getStringExtra(EXTRA_MICROG_APK_URLS)
        if (rawUrls != null) {
            val urls = rawUrls.split('|').map(String::trim).filter(String::isNotEmpty)
            MicrogContainerConfig(applicationContext).configureFromUrls(urls)
            return
        }
        val rawPaths = intent.getStringExtra(EXTRA_MICROG_APK_PATHS) ?: return
        val sourceApks = rawPaths
            .split(File.pathSeparatorChar)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::File)
        MicrogContainerConfig(applicationContext).configure(sourceApks)
    }
}

private fun Intent.hasDebugContainerAction(): Boolean =
    getBooleanExtra("launch_container_game", false) ||
        getBooleanExtra("restart_container_game", false) ||
        getBooleanExtra("audit_translation_index", false) ||
        getBooleanExtra("sync_installed_game", false) ||
        getBooleanExtra("export_localize_snapshot", false) ||
        getBooleanExtra("refresh_translation_redirects", false) ||
        getBooleanExtra("enable_translation_redirects", false)

private fun Intent?.describeForLog(): String =
    if (this == null) {
        "null"
    } else {
        "action=$action component=$component package=$`package` flags=0x${flags.toString(16)} data=$data type=$type"
    }

private fun shortStack(): String =
    Throwable().stackTrace
        .drop(1)
        .take(6)
        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

private fun android.content.SharedPreferences.nonBlankString(key: String, defaultValue: String): String =
    getString(key, null)?.takeIf { it.isNotBlank() } ?: defaultValue

private fun Throwable.userMessage(): String {
    val chain = generateSequence(this) { it.cause }.toList()
    return chain.joinToString(separator = "\n") { error ->
        val message = error.message?.takeIf { it.isNotBlank() }
        if (message == null) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }.take(900)
}

private fun ContainerStatus.withInstalledVersion(installedVersionCode: Long?): ContainerStatus {
    if (!gameImported || installedVersionCode == null || importedVersionCode == null) {
        return this
    }
    if (installedVersionCode == importedVersionCode) {
        return copy(message = "$message，已匹配宿主机正版 v$installedVersionCode")
    }
    return copy(
        message = "宿主机 Google Play 已更新至 v$installedVersionCode（容器记录 v$importedVersionCode），点击启动将自动无损同步"
    )
}

@Composable
private fun LimbusPeScreen(
    containerStatus: ContainerStatus,
    lastInstall: PatchInstallSummary?,
    cachedMeta: CachedPatchMetadata?,
    fontStatus: String,
    dropInPath: String,
    translationEnabled: Boolean,
    compatibilityReport: DeviceCompatibilityReport,
    githubReleaseUrl: String,
    busy: Boolean,
    busyMessage: String,
    gameRunning: Boolean,
    message: String,
    onCancelTask: (() -> Unit)?,
    onToggleTranslationEnabled: (Boolean) -> Unit,
    onImportArchiveClick: () -> Unit,
    onImportFolderClick: () -> Unit,
    onScanDropInAndRefreshClick: () -> Unit,
    onLaunchGameClick: () -> Unit,
    onRestartGameClick: () -> Unit,
    onOpenCompatibilitySettings: () -> Unit,
    onSyncInstalledGame: () -> Unit,
    onGithubReleaseUrlChange: (String) -> Unit,
    onPullFromGithubClick: () -> Unit,
    onExportResources: () -> Unit,
    onImportResources: () -> Unit,
    onExportDebugLogs: () -> Unit
) {
    var advancedExpanded by remember { mutableStateOf(false) }

    val versionDisplay = when {
        lastInstall != null -> "v${lastInstall.version}（已激活）"
        cachedMeta != null -> "v${cachedMeta.version}（已导入待下载游戏资源）"
        else -> "尚未导入"
    }
    val jsonCountDisplay = when {
        lastInstall != null -> "${lastInstall.availableSourceFiles} / ${lastInstall.candidateSourceFiles} 个文件"
        cachedMeta != null -> "${cachedMeta.jsonFileCount} 个文件（已缓存）"
        else -> "0 个文件"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "边狱公司",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "VirtualApp 完整容器 · AppSealing 适配 · 内置 microG 直登",
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val launchLabel = if (gameRunning) {
                        "返回游戏"
                    } else {
                        when {
                            !translationEnabled -> "启动《边狱公司》（纯净原版模式）"
                            lastInstall?.indexActivated == true ->
                                "启动《边狱公司》（汉化 v${lastInstall.version} 挂载中）"
                            cachedMeta != null ->
                                "首次启动《边狱公司》（登录下载资源后自动汉化）"
                            else -> "同步并启动《边狱公司》"
                        }
                    }
                    Button(
                        onClick = onLaunchGameClick,
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text(launchLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (gameRunning) {
                        OutlinedButton(
                            onClick = onRestartGameClick,
                            enabled = !busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                "重新启动并校验",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (busy) {
            BusyDialog(message = busyMessage, onCancel = onCancelTask)
        }
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 卡片 1：沙箱与 Google 直登状态
            SectionCard(
                title = "1. 虚拟容器与 Google 直登环境",
                badge = if (containerStatus.gameImported) "已就绪" else "首次启动自动同步"
            ) {
                StatusLine("VirtualApp 引擎", if (containerStatus.available) "arm64-v8a 已就绪 (AppSealing 兼容)" else "不可用")
                StatusLine(
                    "正版游戏同步",
                    if (containerStatus.gameImported) {
                        "v${containerStatus.importedVersionCode ?: "?"} (${containerStatus.importedSplits} 个 split)"
                    } else {
                        "点击底部启动将自动关联宿主机正版"
                    }
                )
                StatusLine("Google 账号直登", "内置 microG + FakeStore 自动挂载")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Text(
                    text = message,
                    color = Color(0xFF81C784),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 卡片 2：vivo / OriginOS 后台兼容
            SectionCard(
                title = "2. 机型兼容与后台保活",
                badge = compatibilityReport.statusLabel
            ) {
                Text(
                    "${compatibilityReport.deviceLabel} · ${compatibilityReport.androidLabel}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "为避免 Google 网页认证跳回游戏时被系统杀后台导致黑屏，建议允许本应用的「自启动」「后台高耗电」与「关联启动」。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onOpenCompatibilitySettings,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开后台运行与自启动设置")
                }
            }

            // 卡片 3：中文汉化包与字体
            SectionCard(
                title = "3. 中文汉化包与字体",
                badge = if (translationEnabled) "汉化开启" else "原版直连"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "启用运行时汉化与中文字体注入",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = onToggleTranslationEnabled,
                        enabled = !busy
                    )
                }

                StatusLine("当前汉化版本", versionDisplay)
                StatusLine("本地化 JSON 统计", jsonCountDisplay)
                StatusLine("原生 TTF 字体检测", fontStatus)

                if (!cachedMeta?.notice.isNullOrBlank()) {
                    Text(
                        text = cachedMeta!!.notice,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = "自动热投递路径: $dropInPath",
                    color = Color(0xFF81C784),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "提示：运行时会自动锁定游戏日语槽位并按需生成 4096×4096 黑边 SDF 中文图集，无需改动原版校验文件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onImportArchiveClick,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导入 .zip/.7z")
                    }
                    OutlinedButton(
                        onClick = onImportFolderClick,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导入文件夹")
                    }
                }

                OutlinedButton(
                    onClick = onScanDropInAndRefreshClick,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("扫描热投递目录 / 重新对齐日文表")
                }
            }

            OutlinedButton(
                onClick = onExportDebugLogs,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("遇到闪退？一键导出运行诊断包（ZIP）")
            }

            // 折叠高级区：游戏同步、资源备份迁出迁入、在线拉取与诊断包
            TextButton(
                onClick = { advancedExpanded = !advancedExpanded },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advancedExpanded) "收起高级维护与资源备份选项" else "展开高级维护与资源备份选项")
            }

            if (advancedExpanded) {
                SectionCard(title = "4. 容器维护、资源迁移与诊断", badge = "高级选项") {
                    OutlinedButton(
                        onClick = onSyncInstalledGame,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("手动重新同步宿主机正版游戏 APK（保留数据）")
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Text("容器内游戏热更资源备份（换机免重下十几 GB 数据）", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onExportResources,
                            enabled = containerStatus.gameImported && !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("迁出资源 ZIP")
                        }
                        OutlinedButton(
                            onClick = onImportResources,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("迁入资源 ZIP")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Text("可选：从兼容 GitHub Releases 在线拉取汉化", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = githubReleaseUrl,
                        onValueChange = onGithubReleaseUrlChange,
                        label = { Text("GitHub Releases 地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = onPullFromGithubClick,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("在线下载并更新汉化")
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    OutlinedButton(
                        onClick = onExportDebugLogs,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出脱敏运行诊断包（ZIP）")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    badge: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = badge,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BusyDialog(message: String, onCancel: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("取消迁移")
                }
            }
        },
        title = { Text("正在处理") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun LimbusPeScreenPreview() {
    LimbusZhCNTheme {
        LimbusPeScreenRedesign(
            containerStatus = ContainerStatus(
                available = true,
                gameImported = true,
                message = "已无损同步正版游戏",
                importedSplits = 4,
                importedVersionCode = 126
            ),
            lastInstall = PatchInstallSummary(
                version = "2026101501",
                writtenFiles = 2366,
                indexActivated = true,
                candidateSourceFiles = 2366,
                availableSourceFiles = 2366,
                sourceRecordCount = 190423
            ),
            cachedMeta = CachedPatchMetadata(
                version = "2026101501",
                jsonFileCount = 2366,
                notice = "2026-10-15 增量汉化更新: 已完成西西弗之镜(MD8)与高定红派辛克莱全部文本本地化。"
            ),
            fontStatus = "Context 主字体已就绪 (23.9 MB)",
            dropInPath = "/storage/emulated/0/Android/data/io.limbuspe.box/files/DropIn_Lang_Pack",
            translationEnabled = true,
            compatibilityReport = DeviceCompatibilityReport(
                deviceLabel = "vivo / OriginOS",
                androidLabel = "Android 15（API 35）",
                sdkInt = 35,
                abiLabel = "arm64-v8a",
                hasRequiredAbi = true,
                pageSizeBytes = 16 * 1024L,
                is64BitProcess = true,
                isLowRamDevice = false,
                isIgnoringBatteryOptimizations = true,
                warnings = emptyList()
            ),
            githubReleaseUrl = "https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany/releases",
            busy = false,
            busyMessage = "",
            gameRunning = false,
            message = "沙箱环境与汉化索引已就绪",
            onCancelTask = null,
            onToggleTranslationEnabled = {},
            onManagePacksClick = {},
            onImportArchiveClick = {},
            onImportFolderClick = {},
            onScanDropInAndRefreshClick = {},
            onLaunchGameClick = {},
            onRestartGameClick = {},
            onRequestBatteryOptimization = {},
            onOpenCompatibilitySettings = {},
            onSyncInstalledGame = {},
            onGithubReleaseUrlChange = {},
            onPullFromGithubClick = {},
            onExportResources = {},
            onImportResources = {},
            onExportDebugLogs = {}
        )
    }
}
