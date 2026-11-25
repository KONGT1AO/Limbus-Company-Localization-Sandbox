package com.example.limbuszhcn

import com.lody.virtual.client.hook.delegate.LimbusOrientationManager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.limbuszhcn.container.CachedPatchMetadata
import com.example.limbuszhcn.container.ContainerStatus
import com.example.limbuszhcn.gamefs.PatchInstallSummary
import com.example.limbuszhcn.ui.theme.LimbusSuccess
import com.example.limbuszhcn.update.LauncherUpdateRelease
import com.example.limbuszhcn.update.LauncherUpdateState
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class HostTab(val index: String, val label: String) {
    Launch("01", "启程"),
    Translation("02", "汉化"),
    Maintenance("03", "维护")
}

private val PanelShape = CutCornerShape(
    topStart = 4.dp,
    topEnd = 22.dp,
    bottomEnd = 4.dp,
    bottomStart = 14.dp
)

private val ControlShape = CutCornerShape(
    topStart = 4.dp,
    topEnd = 12.dp,
    bottomEnd = 4.dp,
    bottomStart = 12.dp
)

@Composable
internal fun LimbusPeScreenRedesign(
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
    onManagePacksClick: () -> Unit = {},
    onImportArchiveClick: () -> Unit,
    onImportFolderClick: () -> Unit,
    onScanDropInAndRefreshClick: () -> Unit,
    onLaunchGameClick: () -> Unit,
    onRestartGameClick: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenCompatibilitySettings: () -> Unit,
    onSyncInstalledGame: () -> Unit,
    onGithubReleaseUrlChange: (String) -> Unit,
    onPullFromGithubClick: () -> Unit,
    onExportResources: () -> Unit,
    onImportResources: () -> Unit,
    onExportDebugLogs: () -> Unit,
    hasDropInAccess: Boolean = true,
    onRequestDropInAccessClick: () -> Unit = {},
    selectedAppIcon: AppIconPreset = AppIconPreset.defaultPreset,
    onSelectAppIcon: (AppIconPreset) -> Unit = {},
    screenOrientationMode: Int = LimbusOrientationManager.MODE_AUTO_SENSOR,
    onScreenOrientationModeChange: (Int) -> Unit = {},
    launcherUpdateState: LauncherUpdateState = LauncherUpdateState.Idle,
    showUpdateBadge: Boolean = false,
    onOpenSettingsClick: () -> Unit = {},
    onCheckLauncherUpdateClick: () -> Unit = {},
    onDownloadLauncherUpdateClick: (LauncherUpdateRelease) -> Unit = {},
    onCancelLauncherUpdateDownloadClick: () -> Unit = {},
    onInstallLauncherUpdateClick: (File) -> Unit = {},
    onDismissLauncherUpdateClick: (LauncherUpdateRelease) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(HostTab.Launch) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    val launchHint = when {
        gameRunning -> "游戏进程仍在运行，可直接返回，无需重新检查环境"
        !translationEnabled -> "当前以纯净官方原版模式启动"
        lastInstall?.indexActivated == true -> "汉化 v${lastInstall.version} 已挂载"
        cachedMeta != null -> "登录并下载资源后将自动启用汉化"
        else -> "同步宿主机正版游戏后进入"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TerminalTopBar(
                iconRes = selectedAppIcon.iconRes,
                inSettings = showSettings,
                showUpdateBadge = showUpdateBadge,
                onSettingsToggle = {
                    if (showSettings) {
                        showSettings = false
                    } else {
                        showSettings = true
                        onOpenSettingsClick()
                    }
                }
            )
        },
        bottomBar = {
            TerminalBottomBar(
                selectedTab = selectedTab,
                launchHint = launchHint,
                busy = busy,
                gameRunning = gameRunning,
                onTabSelected = { tab ->
                    showSettings = false
                    selectedTab = tab
                },
                onLaunchGameClick = onLaunchGameClick,
                onRestartGameClick = onRestartGameClick
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showSettings) {
                SettingsPage(
                    busy = busy,
                    gameRunning = gameRunning,
                    message = message,
                    selectedAppIcon = selectedAppIcon,
                    onSelectAppIcon = onSelectAppIcon,
                    screenOrientationMode = screenOrientationMode,
                    onScreenOrientationModeChange = onScreenOrientationModeChange,
                    launcherUpdateState = launcherUpdateState,
                    onCheckLauncherUpdateClick = onCheckLauncherUpdateClick,
                    onDownloadLauncherUpdateClick = onDownloadLauncherUpdateClick,
                    onCancelLauncherUpdateDownloadClick = onCancelLauncherUpdateDownloadClick,
                    onInstallLauncherUpdateClick = onInstallLauncherUpdateClick,
                    onDismissLauncherUpdateClick = onDismissLauncherUpdateClick
                )
            } else {
                when (selectedTab) {
                    HostTab.Launch -> LaunchPage(
                        containerStatus = containerStatus,
                        lastInstall = lastInstall,
                        fontStatus = fontStatus,
                        translationEnabled = translationEnabled,
                        compatibilityReport = compatibilityReport,
                        message = message,
                        iconRes = selectedAppIcon.iconRes,
                        onRequestBatteryOptimization = onRequestBatteryOptimization,
                        onOpenCompatibilitySettings = onOpenCompatibilitySettings
                    )

                    HostTab.Translation -> TranslationPage(
                        lastInstall = lastInstall,
                        cachedMeta = cachedMeta,
                        fontStatus = fontStatus,
                        dropInPath = dropInPath,
                        translationEnabled = translationEnabled,
                        busy = busy,
                        hasDropInAccess = hasDropInAccess,
                        onToggleTranslationEnabled = onToggleTranslationEnabled,
                        onManagePacksClick = onManagePacksClick,
                        onImportArchiveClick = onImportArchiveClick,
                        onImportFolderClick = onImportFolderClick,
                        onScanDropInAndRefreshClick = onScanDropInAndRefreshClick,
                        onRequestDropInAccessClick = onRequestDropInAccessClick
                    )

                    HostTab.Maintenance -> MaintenancePage(
                        containerStatus = containerStatus,
                        githubReleaseUrl = githubReleaseUrl,
                        busy = busy,
                        message = message,
                        onSyncInstalledGame = onSyncInstalledGame,
                        onGithubReleaseUrlChange = onGithubReleaseUrlChange,
                        onPullFromGithubClick = onPullFromGithubClick,
                        onExportResources = onExportResources,
                        onImportResources = onImportResources,
                        onExportDebugLogs = onExportDebugLogs
                    )
                }
            }
        }

        if (busy) {
            TerminalBusyDialog(message = busyMessage, onCancel = onCancelTask)
        }
    }
}

@Composable
private fun TerminalTopBar(
    iconRes: Int = AppIconPreset.defaultPreset.iconRes,
    inSettings: Boolean = false,
    showUpdateBadge: Boolean = false,
    onSettingsToggle: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(42.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "LIMBUS COMPANY",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "汉化终端 // v${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (inSettings) {
                Surface(
                    onClick = onSettingsToggle,
                    shape = ControlShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .height(42.dp)
                        .semantics { contentDescription = "返回主界面" }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        TerminalBackArrowIcon(
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "返回",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.size(42.dp)) {
                    Surface(
                        onClick = onSettingsToggle,
                        shape = ControlShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(
                            1.dp,
                            if (showUpdateBadge) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "终端设置" }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            TerminalGearIcon(
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                    if (showUpdateBadge) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalGearIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rOuter = size.minDimension * 0.46f
        val rInner = size.minDimension * 0.33f
        val rHole = size.minDimension * 0.16f
        val teeth = 8
        val path = Path()
        val step = (2.0 * PI) / (teeth * 4)
        for (i in 0 until teeth * 4) {
            val angle = i * step - PI / 2.0
            val r = if (i % 4 == 0 || i % 4 == 1) rOuter else rInner
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path = path, color = color, style = Stroke(width = size.minDimension * 0.095f, join = StrokeJoin.Round))
        drawCircle(
            color = color,
            radius = rHole,
            center = Offset(cx, cy),
            style = Stroke(width = size.minDimension * 0.095f)
        )
    }
}

@Composable
private fun TerminalBackArrowIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.58f, h * 0.20f)
            lineTo(w * 0.24f, h * 0.50f)
            lineTo(w * 0.58f, h * 0.80f)
        }
        drawPath(path = path, color = color, style = stroke)
        drawLine(
            color = color,
            start = Offset(w * 0.28f, h * 0.50f),
            end = Offset(w * 0.84f, h * 0.50f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun TerminalBottomBar(
    selectedTab: HostTab,
    launchHint: String,
    busy: Boolean,
    gameRunning: Boolean,
    onTabSelected: (HostTab) -> Unit,
    onLaunchGameClick: () -> Unit,
    onRestartGameClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = launchHint,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onLaunchGameClick,
                enabled = !busy,
                shape = ControlShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = if (gameRunning) "返回游戏" else "进入《边狱公司》",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
            }
            if (gameRunning) {
                OutlinedButton(
                    onClick = onRestartGameClick,
                    enabled = !busy,
                    shape = ControlShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "重新启动并校验",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ControlShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(4.dp)
            ) {
                HostTab.values().forEach { tab ->
                    val selected = tab == selectedTab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.surfaceVariant
                                else Color.Transparent
                            )
                            .clickable { onTabSelected(tab) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = tab.index,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = tab.label,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchPage(
    containerStatus: ContainerStatus,
    lastInstall: PatchInstallSummary?,
    fontStatus: String,
    translationEnabled: Boolean,
    compatibilityReport: DeviceCompatibilityReport,
    message: String,
    iconRes: Int = AppIconPreset.defaultPreset.iconRes,
    onRequestBatteryOptimization: () -> Unit,
    onOpenCompatibilitySettings: () -> Unit
) {
    PageColumn {
        PageHeading(
            index = "01",
            title = "启动台",
            subtitle = "先确认运行环境，再继续进入公司。"
        )
        MissionPanel(
            containerStatus = containerStatus,
            lastInstall = lastInstall,
            fontStatus = fontStatus,
            translationEnabled = translationEnabled,
            iconRes = iconRes
        )
        SystemMessage(message)
        SectionCard(
            title = "运行保障",
            badge = compatibilityReport.statusLabel
        ) {
            StatusLine(
                label = "设备",
                value = "${compatibilityReport.deviceLabel} · ${compatibilityReport.androidLabel}"
            )
            StatusLine(
                label = "ABI",
                value = compatibilityReport.abiLabel
            )
            StatusLine(
                label = "电池优化",
                value = compatibilityReport.batteryOptimizationStatus
            )
            Text(
                text = "Google 网页认证返回游戏时，系统可能终止后台进程。建议允许本应用的“自启动”“后台高耗电”与“关联启动”。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onRequestBatteryOptimization,
                enabled = !compatibilityReport.isIgnoringBatteryOptimizations,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (compatibilityReport.isIgnoringBatteryOptimizations) {
                        "后台省电限制已放行"
                    } else {
                        "一键允许后台运行"
                    }
                )
            }
            OutlinedButton(
                onClick = onOpenCompatibilitySettings,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("检查后台运行与自启动设置")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun MissionPanel(
    containerStatus: ContainerStatus,
    lastInstall: PatchInstallSummary?,
    fontStatus: String,
    translationEnabled: Boolean,
    iconRes: Int = AppIconPreset.defaultPreset.iconRes
) {
    val translationReady = translationEnabled && lastInstall?.indexActivated == true
    val translationValue = when {
        !translationEnabled -> "原版模式"
        translationReady -> "v${lastInstall?.version}"
        else -> "待导入"
    }
    val gameValue = if (containerStatus.gameImported) {
        "已同步 · ${containerStatus.importedSplits} split"
    } else {
        "待同步"
    }

    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(ControlShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "边狱公司",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "PROJECT MOON // VM",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Surface(
                    shape = ControlShape,
                    color = if (translationReady || !translationEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Text(
                        text = when {
                            !translationEnabled -> "原版"
                            translationReady -> "汉化就绪"
                            else -> "待配置"
                        },
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusTile(
                        label = "VIRTUALAPP",
                        value = if (containerStatus.available) "引擎已就绪" else "不可用",
                        ready = containerStatus.available,
                        modifier = Modifier.fillMaxWidth()
                    )
                    StatusTile(
                        label = "TRANSLATION",
                        value = translationValue,
                        ready = translationReady || !translationEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusTile(
                        label = "OFFICIAL GAME",
                        value = gameValue,
                        ready = containerStatus.gameImported,
                        modifier = Modifier.fillMaxWidth()
                    )
                    StatusTile(
                        label = "GOOGLE LOGIN",
                        value = "microG / FakeStore",
                        ready = containerStatus.available,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            StatusLine(
                label = "中文字体",
                value = fontStatus
            )
        }
    }
}

@Composable
private fun TranslationPage(
    lastInstall: PatchInstallSummary?,
    cachedMeta: CachedPatchMetadata?,
    fontStatus: String,
    dropInPath: String,
    translationEnabled: Boolean,
    busy: Boolean,
    hasDropInAccess: Boolean = true,
    onToggleTranslationEnabled: (Boolean) -> Unit,
    onManagePacksClick: () -> Unit = {},
    onImportArchiveClick: () -> Unit,
    onImportFolderClick: () -> Unit,
    onScanDropInAndRefreshClick: () -> Unit,
    onRequestDropInAccessClick: () -> Unit = {}
) {
    val versionDisplay = when {
        lastInstall != null -> "v${lastInstall.version}（已激活）"
        cachedMeta != null -> "v${cachedMeta.version}（待游戏资源）"
        else -> "尚未导入"
    }
    val jsonCountDisplay = when {
        lastInstall != null -> "${lastInstall.availableSourceFiles} / ${lastInstall.candidateSourceFiles} 个文件"
        cachedMeta != null -> "${cachedMeta.jsonFileCount} 个文件（已缓存）"
        else -> "0 个文件"
    }

    PageColumn {
        PageHeading(
            index = "02",
            title = "语言覆写",
            subtitle = "管理汉化包、中文字体与运行时索引。"
        )
        SectionCard(
            title = "语言覆盖",
            badge = if (translationEnabled) "汉化开启" else "原版直连"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "运行时文本替换",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (translationEnabled) "中文文本与字体注入已启用"
                        else "游戏将以官方原始语言运行",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = translationEnabled,
                    onCheckedChange = onToggleTranslationEnabled,
                    enabled = !busy
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
            StatusLine(label = "当前汉化版本", value = versionDisplay)
            StatusLine(label = "本地化文件", value = jsonCountDisplay)
            StatusLine(label = "中文字体", value = fontStatus)
            if (!cachedMeta?.notice.isNullOrBlank()) {
                InfoStrip(cachedMeta?.notice.orEmpty())
            }
            StatusLine(label = "公共热投递专区", value = dropInPath)
            Text(
                text = "支持直接放入任意符合官方文件结构的中文汉化包，运行时自动对齐，不改写游戏校验文件。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (!hasDropInAccess) {
                Surface(
                    shape = ControlShape,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "未获得「所有文件访问权限」：Android 11 及以上系统在重装启动器后，外部文件管理器放入该目录的汉化包将无法被检测。",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = onRequestDropInAccessClick,
                            enabled = !busy,
                            shape = ControlShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("授权访问公共热投递目录")
                        }
                    }
                }
            }
        }
        SectionCard(
            title = "汉化包管理与导入",
            badge = "自由切换"
        ) {
            Button(
                onClick = onManagePacksClick,
                enabled = !busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📑 切换 / 管理汉化包")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onImportArchiveClick,
                    enabled = !busy,
                    shape = ControlShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入 .zip / .7z")
                }
                OutlinedButton(
                    onClick = onImportFolderClick,
                    enabled = !busy,
                    shape = ControlShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入文件夹")
                }
            }
            OutlinedButton(
                onClick = onScanDropInAndRefreshClick,
                enabled = !busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("扫描热投递专区并对齐")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun MaintenancePage(
    containerStatus: ContainerStatus,
    githubReleaseUrl: String,
    busy: Boolean,
    message: String,
    onSyncInstalledGame: () -> Unit,
    onGithubReleaseUrlChange: (String) -> Unit,
    onPullFromGithubClick: () -> Unit,
    onExportResources: () -> Unit,
    onImportResources: () -> Unit,
    onExportDebugLogs: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (message.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 4.dp)
            ) {
                SystemMessage(message)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            PageColumn {
                PageHeading(
                    index = "03",
                    title = "维护终端",
                    subtitle = "同步游戏、迁移资源并保存诊断现场。"
                )
        SectionCard(
            title = "游戏与容器",
            badge = if (containerStatus.gameImported) "数据已关联" else "等待同步"
        ) {
            Text(
                text = "重新同步只会关联宿主机正版 APK，不会清除容器内的账号、剧情进度或游戏资源。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onSyncInstalledGame,
                enabled = !busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("手动重新同步宿主机游戏 APK")
            }
        }
        SectionCard(
            title = "资源迁移",
            badge = "换机备份"
        ) {
            Text(
                text = "迁移容器内热更资源，换机时可免去重新下载十几 GB 数据。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onExportResources,
                    enabled = containerStatus.gameImported && !busy,
                    shape = ControlShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("迁出资源 ZIP")
                }
                OutlinedButton(
                    onClick = onImportResources,
                    enabled = !busy,
                    shape = ControlShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("迁入资源 ZIP")
                }
            }
        }
        SectionCard(
            title = "汉化源连接",
            badge = "可选"
        ) {
            OutlinedTextField(
                value = githubReleaseUrl,
                onValueChange = onGithubReleaseUrlChange,
                label = { Text("GitHub Releases 地址") },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onPullFromGithubClick,
                enabled = !busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("在线下载并更新汉化")
            }
        }
        SectionCard(
            title = "诊断与支持",
            badge = "脱敏"
        ) {
            Text(
                text = "遇到闪退或黑屏时，先导出诊断包再排查。包内包含运行日志和脱敏后的崩溃现场。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onExportDebugLogs,
                enabled = !busy,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出运行诊断包（ZIP）")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SettingsPage(
    busy: Boolean,
    gameRunning: Boolean,
    message: String,
    selectedAppIcon: AppIconPreset,
    onSelectAppIcon: (AppIconPreset) -> Unit,
    screenOrientationMode: Int,
    onScreenOrientationModeChange: (Int) -> Unit,
    launcherUpdateState: LauncherUpdateState,
    onCheckLauncherUpdateClick: () -> Unit,
    onDownloadLauncherUpdateClick: (LauncherUpdateRelease) -> Unit,
    onCancelLauncherUpdateDownloadClick: () -> Unit,
    onInstallLauncherUpdateClick: (File) -> Unit,
    onDismissLauncherUpdateClick: (LauncherUpdateRelease) -> Unit
) {
    val updateBadge = when (launcherUpdateState) {
        is LauncherUpdateState.Idle -> "v${BuildConfig.VERSION_NAME}"
        is LauncherUpdateState.Checking -> "检查中"
        is LauncherUpdateState.UpToDate -> "已是最新"
        is LauncherUpdateState.Available -> "有新版本"
        is LauncherUpdateState.Downloading -> "下载中"
        is LauncherUpdateState.ReadyToInstall -> "待安装"
        is LauncherUpdateState.Error -> "检查异常"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (message.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 4.dp)
            ) {
                SystemMessage(message)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            PageColumn {
                PageHeading(
                    index = "SET",
                    title = "终端设置",
                    subtitle = "管理启动器更新、游戏横屏朝向与应用图标。"
                )

                SectionCard(
                    title = "启动器更新",
                    badge = updateBadge
                ) {
                    Text(
                        text = "当前版本：v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "直连 GitHub Releases 检查与下载正式版，支持应用内直接覆盖安装，保留容器内全部游戏数据。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    when (launcherUpdateState) {
                        LauncherUpdateState.Idle -> {
                            OutlinedButton(
                                onClick = onCheckLauncherUpdateClick,
                                enabled = !busy,
                                shape = ControlShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("检查更新")
                            }
                        }

                        LauncherUpdateState.Checking -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "正在连接 GitHub 检查正式版本...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        is LauncherUpdateState.UpToDate -> {
                            InfoStrip(launcherUpdateState.checkedAtSummary)
                            OutlinedButton(
                                onClick = onCheckLauncherUpdateClick,
                                enabled = !busy,
                                shape = ControlShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("重新检查更新")
                            }
                        }

                        is LauncherUpdateState.Available -> {
                            val release = launcherUpdateState.release
                            Surface(
                                shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = "发现新版本：${release.displayLabel}",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = release.releaseNotesSummary,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    release.sizeBytes?.let { bytes ->
                                        Text(
                                            text = "安装包大小：${formatMegabytes(bytes)}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { onDownloadLauncherUpdateClick(release) },
                                    enabled = !busy,
                                    shape = ControlShape,
                                    modifier = Modifier.weight(1.4f)
                                ) {
                                    Text("应用内下载更新", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { onDismissLauncherUpdateClick(release) },
                                    enabled = !busy,
                                    shape = ControlShape,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("暂不更新")
                                }
                            }
                        }

                        is LauncherUpdateState.Downloading -> {
                            val total = launcherUpdateState.totalBytes
                            val downloaded = launcherUpdateState.downloadedBytes
                            val progress = if (total > 0L) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                            Text(
                                text = buildString {
                                    append("正在下载 ${launcherUpdateState.release.displayLabel}")
                                    append(" · ${formatMegabytes(downloaded)}")
                                    if (total > 0L) {
                                        val pct = ((downloaded * 100L) / total).coerceIn(0L, 100L)
                                        append(" / ${formatMegabytes(total)} ($pct%)")
                                    }
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                            OutlinedButton(
                                onClick = onCancelLauncherUpdateDownloadClick,
                                shape = ControlShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("取消下载")
                            }
                        }

                        is LauncherUpdateState.ReadyToInstall -> {
                            Surface(
                                shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
                                color = LimbusSuccess.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, LimbusSuccess.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${launcherUpdateState.release.displayLabel} 已下载并通过包名、版本与同签名校验，可直接覆盖安装。",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Button(
                                onClick = { onInstallLauncherUpdateClick(launcherUpdateState.apkFile) },
                                enabled = !busy,
                                shape = ControlShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("立即覆盖安装", fontWeight = FontWeight.Bold)
                            }
                        }

                        is LauncherUpdateState.Error -> {
                            InfoStrip(launcherUpdateState.message)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = onCheckLauncherUpdateClick,
                                    enabled = !busy,
                                    shape = ControlShape,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("重新检查")
                                }
                                launcherUpdateState.release?.let { release ->
                                    Button(
                                        onClick = { onDownloadLauncherUpdateClick(release) },
                                        enabled = !busy,
                                        shape = ControlShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("重新下载")
                                    }
                                }
                            }
                        }
                    }
                }

                SectionCard(
                    title = "横屏方向锁定",
                    badge = if (screenOrientationMode == LimbusOrientationManager.MODE_LANDSCAPE) "常规横屏" else "反向横屏"
                ) {
                    Text(
                        text = "针对手机开启竖屏锁定（系统关闭自动旋转）进行专项适配。可自由指定游戏横屏朝向，告别误触音量键与锁屏按键，进入游戏直接锁定。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val modes = listOf(
                            Triple(
                                LimbusOrientationManager.MODE_REVERSE_LANDSCAPE,
                                "锁定反向横屏 (推荐)",
                                "音量键始终朝上 · 避免双手握持误触按键（惯用推荐）"
                            ),
                            Triple(
                                LimbusOrientationManager.MODE_LANDSCAPE,
                                "锁定常规横屏",
                                "音量键始终朝下 · 传统原生默认方向"
                            )
                        )
                        modes.forEach { (mode, title, desc) ->
                            val selected = screenOrientationMode == mode
                            Surface(
                                onClick = { onScreenOrientationModeChange(mode) },
                                shape = CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = desc,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    if (selected) {
                                        Text(
                                            text = "● 已生效",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                SectionCard(
                    title = "应用图标",
                    badge = selectedAppIcon.displayName
                ) {
                    Text(
                        text = "选择后返回桌面自动生效（桌面应用名称保持不变），部分机型需等待数秒刷新。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (gameRunning) {
                        InfoStrip("游戏正在后台运行中，请先退出游戏后再更换图标，避免系统刷新桌面时结束游戏进程。")
                    }
                    val switchable = !busy && !gameRunning
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AppIconPreset.entries.chunked(4).forEach { rowPresets ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                rowPresets.forEach { preset ->
                                    val selected = preset == selectedAppIcon
                                    Surface(
                                        onClick = { onSelectAppIcon(preset) },
                                        enabled = switchable,
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        border = BorderStroke(
                                            width = if (selected) 1.5.dp else 1.dp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                            }
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                        ) {
                                            Image(
                                                painter = painterResource(preset.iconRes),
                                                contentDescription = preset.displayName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(50.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                            )
                                            Text(
                                                text = preset.displayName,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                                repeat(4 - rowPresets.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun PageHeading(
    index: String,
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = index,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black
            )
            Column {
                Text(
                    text = "SECTION $index",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
            }
        }
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    badge: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun StatusTile(
    label: String,
    value: String,
    ready: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (ready) LimbusSuccess else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(50)
                    )
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.8f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.4f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SystemMessage(message: String) {
    Surface(
        shape = ControlShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = ">",
                color = LimbusSuccess,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun InfoStrip(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TerminalBusyDialog(message: String, onCancel: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = {},
        shape = PanelShape,
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("取消当前任务")
                }
            }
        },
        title = {
            Text(
                text = "PROCESSING",
                color = MaterialTheme.colorScheme.secondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    )
}
