package com.example.limbuszhcn

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.DrawableRes

/**
 * 预设桌面、通知栏与启动器图标清单。
 *
 * 首位始终为当期版本的默认图标（本期为「噗咿」），随后依次排列玩家「但丁」及
 * 01～13 号全部 12 位罪人，共计 14 个可切换预设入口。
 */
enum class AppIconPreset(
    val id: String,
    val displayName: String,
    val sinnerNumber: String,
    val aliasClassName: String,
    @DrawableRes val iconRes: Int,
    val isDefault: Boolean = false
) {
    PWIE(
        id = "pwie",
        displayName = "噗咿",
        sinnerNumber = "00",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasDefault",
        iconRes = R.drawable.app_icon_default_pwie,
        isDefault = true
    ),
    DANTE(
        id = "dante",
        displayName = "但丁",
        sinnerNumber = "10",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasDante",
        iconRes = R.drawable.app_icon_dante
    ),
    YI_SANG(
        id = "yi_sang",
        displayName = "李箱",
        sinnerNumber = "01",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasYiSang",
        iconRes = R.drawable.app_icon_yisang
    ),
    FAUST(
        id = "faust",
        displayName = "浮士德",
        sinnerNumber = "02",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasFaust",
        iconRes = R.drawable.app_icon_faust
    ),
    DON_QUIXOTE(
        id = "don_quixote",
        displayName = "堂吉诃德",
        sinnerNumber = "03",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasDonQuixote",
        iconRes = R.drawable.app_icon_donquixote
    ),
    RYOSHU(
        id = "ryoshu",
        displayName = "良秀",
        sinnerNumber = "04",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasRyoshu",
        iconRes = R.drawable.app_icon_ryoshu
    ),
    MEURSAULT(
        id = "meursault",
        displayName = "默尔索",
        sinnerNumber = "05",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasMeursault",
        iconRes = R.drawable.app_icon_meursault
    ),
    HONG_LU(
        id = "hong_lu",
        displayName = "鸿璐",
        sinnerNumber = "06",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasHongLu",
        iconRes = R.drawable.app_icon_honglu
    ),
    HEATHCLIFF(
        id = "heathcliff",
        displayName = "希斯克利夫",
        sinnerNumber = "07",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasHeathcliff",
        iconRes = R.drawable.app_icon_heathcliff
    ),
    ISHMAEL(
        id = "ishmael",
        displayName = "以实玛利",
        sinnerNumber = "08",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasIshmael",
        iconRes = R.drawable.app_icon_ishmael
    ),
    RODYA(
        id = "rodya",
        displayName = "罗佳",
        sinnerNumber = "09",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasRodya",
        iconRes = R.drawable.app_icon_rodya
    ),
    SINCLAIR(
        id = "sinclair",
        displayName = "辛克莱",
        sinnerNumber = "11",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasSinclair",
        iconRes = R.drawable.app_icon_sinclair
    ),
    OUTIS(
        id = "outis",
        displayName = "奥提斯",
        sinnerNumber = "12",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasOutis",
        iconRes = R.drawable.app_icon_outis
    ),
    GREGOR(
        id = "gregor",
        displayName = "格里高尔",
        sinnerNumber = "13",
        aliasClassName = "com.example.limbuszhcn.MainActivityAliasGregor",
        iconRes = R.drawable.app_icon_gregor
    );

    companion object {
        val defaultPreset: AppIconPreset
            get() = entries.firstOrNull { it.isDefault } ?: PWIE
    }
}

/** 抽象 `<activity-alias>` 启用状态读写接口，供原生调用和 JVM 单元测试共用。 */
internal interface ComponentStateStore {
    fun getState(aliasClassName: String): Int
    fun setEnabled(aliasClassName: String, enabled: Boolean)
}

/**
 * 基于 Android `<activity-alias>` 管理桌面、通知栏及多任务卡片图标切换。
 *
 * `MainActivity` 本体保持常驻启用，桌面入口全部由对应别名承载；切换时始终先启用目标别名、
 * 再禁用其他别名，确保桌面与通知回跳入口任何时刻都不中断。
 */
object AppIconManager {
    private const val TAG = "AppIconManager"

    fun currentPreset(context: Context): AppIconPreset =
        currentPreset(AndroidComponentStateStore(context.applicationContext))

    /**
     * 跨版本升级自愈：若老版本已显式选中了某个非默认角色（例如浮士德），而新版清单引入了
     * 处于 `DEFAULT(enabled=true)` 状态的全新默认别名，则禁用其余激活项/默认别名，防止双图标。
     */
    fun reconcileAliasStates(context: Context): Boolean {
        val appContext = context.applicationContext
        return reconcileAliasStates(AndroidComponentStateStore(appContext)) { active ->
            PersistentDiagnosticLog.info(
                TAG,
                "Reconciled desktop alias states after upgrade, kept active=${active.id} alias=${active.aliasClassName}"
            )
        }
    }

    fun applyPreset(context: Context, target: AppIconPreset): Boolean {
        val appContext = context.applicationContext
        return applyPreset(AndroidComponentStateStore(appContext), target) {
            PersistentDiagnosticLog.info(
                TAG,
                "Switched app icon preset=${target.id} name=${target.displayName} alias=${target.aliasClassName}"
            )
        }
    }

    /** 将预设图标资源解码并缩放到适配通知大图标与旧版任务栏的尺寸。 */
    fun loadIconBitmap(
        context: Context,
        preset: AppIconPreset,
        targetSizePx: Int = 192
    ): Bitmap? = runCatching {
        val raw = BitmapFactory.decodeResource(context.resources, preset.iconRes) ?: return null
        if (raw.width == targetSizePx && raw.height == targetSizePx) {
            return raw
        }
        val scaled = Bitmap.createScaledBitmap(raw, targetSizePx, targetSizePx, true)
        if (scaled !== raw) {
            raw.recycle()
        }
        scaled
    }.getOrNull()

    /** 同步更新系统最近任务（多任务切换卡片）中的当前角色图标，桌面名称保持不变。 */
    fun applyTaskDescription(activity: Activity, preset: AppIconPreset) {
        runCatching {
            val label = activity.getString(R.string.app_name)
            val description = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setIcon(preset.iconRes)
                        .build()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    @Suppress("DEPRECATION")
                    ActivityManager.TaskDescription(label, preset.iconRes)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    ActivityManager.TaskDescription(label, loadIconBitmap(activity, preset))
                }
            }
            activity.setTaskDescription(description)
        }.onFailure { error ->
            PersistentDiagnosticLog.warn(TAG, "Unable to update task description icon", error)
        }
    }

    internal fun currentPreset(store: ComponentStateStore): AppIconPreset {
        val explicitlyEnabled = AppIconPreset.entries.firstOrNull { preset ->
            store.getState(preset.aliasClassName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (explicitlyEnabled != null) {
            return explicitlyEnabled
        }
        val defaultPreset = AppIconPreset.defaultPreset
        if (store.getState(defaultPreset.aliasClassName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            return defaultPreset
        }
        return AppIconPreset.entries.firstOrNull { preset ->
            store.getState(preset.aliasClassName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } ?: defaultPreset
    }

    internal fun reconcileAliasStates(
        store: ComponentStateStore,
        onReconciled: ((AppIconPreset) -> Unit)? = null
    ): Boolean {
        val active = AppIconPreset.entries.firstOrNull { preset ->
            store.getState(preset.aliasClassName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: return false
        var changed = false
        AppIconPreset.entries.forEach { preset ->
            if (preset != active) {
                val state = store.getState(preset.aliasClassName)
                val shouldDisable = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (preset.isDefault && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                if (shouldDisable) {
                    store.setEnabled(preset.aliasClassName, enabled = false)
                    changed = true
                }
            }
        }
        if (changed) {
            onReconciled?.invoke(active)
        }
        return changed
    }

    internal fun applyPreset(
        store: ComponentStateStore,
        target: AppIconPreset,
        onApplied: (() -> Unit)? = null
    ): Boolean {
        val current = currentPreset(store)
        if (current == target &&
            store.getState(target.aliasClassName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ) {
            return false
        }
        val allAlreadyDefaultOnDefaultTarget = target.isDefault &&
            current == target &&
            store.getState(target.aliasClassName) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
            AppIconPreset.entries.all { preset ->
                preset == target || store.getState(preset.aliasClassName) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        if (allAlreadyDefaultOnDefaultTarget) {
            return false
        }

        // 先激活目标入口再关闭其余别名，防止切换瞬间桌面找不到已启用的 LAUNCHER Activity。
        store.setEnabled(target.aliasClassName, enabled = true)
        AppIconPreset.entries.forEach { preset ->
            if (preset != target) {
                store.setEnabled(preset.aliasClassName, enabled = false)
            }
        }
        onApplied?.invoke()
        return true
    }

    private class AndroidComponentStateStore(
        private val context: Context
    ) : ComponentStateStore {
        private val pm: PackageManager = context.packageManager

        override fun getState(aliasClassName: String): Int =
            pm.getComponentEnabledSetting(ComponentName(context.packageName, aliasClassName))

        override fun setEnabled(aliasClassName: String, enabled: Boolean) {
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, aliasClassName),
                newState,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
