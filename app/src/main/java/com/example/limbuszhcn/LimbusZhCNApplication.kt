package com.example.limbuszhcn

import android.app.Notification
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Process
import com.lody.virtual.client.core.SettingConfig
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.compat.NotificationChannelCompat
import java.io.File

/**
 * 初始化汉化器宿主进程及 VirtualApp 容器运行时。
 *
 * 主进程、容器服务进程和虚拟应用进程都会创建该 Application，因此初始化逻辑必须可重复执行。
 */
class LimbusZhCNApplication : Application() {
    /**
     * 在各进程创建早期启动 VirtualApp，使后续组件能够安全访问虚拟包管理服务。
     *
     * @param base Android 为当前进程提供的基础上下文
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 在 VirtualApp 初始化前安装持久日志，才能保留 vivo 等系统在启动早期杀进程时的现场。
        PersistentDiagnosticLog.install(base)
        PersistentDiagnosticLog.info(
            TAG,
            "attachBaseContext pid=${Process.myPid()} process=${base.getProcessNameForLog()} package=$packageName"
        )
        invalidateStaleGmsChimeraConfig(base)
        runCatching {
            VirtualCore.get().startup(this, LimbusVirtualConfig(packageName))
            PersistentDiagnosticLog.info(TAG, "VirtualCore.startup success pid=${Process.myPid()}")
        }.onFailure { error ->
            PersistentDiagnosticLog.error(TAG, "VirtualApp startup failed", error)
        }
    }

    private fun invalidateStaleGmsChimeraConfig(context: Context) {
        runCatching {
            val hostApk = File(context.applicationInfo.sourceDir)
            val config = File(
                context.applicationInfo.dataDir,
                "virtual/data/user_de/0/com.google.android.gms/app_chimera/current_config.fb"
            )
            if (config.isFile && config.lastModified() < hostApk.lastModified()) {
                val deleted = config.delete()
                PersistentDiagnosticLog.info(
                    TAG,
                    "Invalidated stale virtual GMS Chimera config deleted=$deleted " +
                        "configMtime=${config.lastModified()} hostApkMtime=${hostApk.lastModified()}"
                )
            }
        }.onFailure { error ->
            PersistentDiagnosticLog.warn(TAG, "Unable to invalidate stale virtual GMS Chimera config", error)
        }
    }

    /**
     * 完成 VirtualApp 的进程级初始化，并记录当前进程便于真机诊断。
     */
    override fun onCreate() {
        super.onCreate()
        PersistentDiagnosticLog.info(
            TAG,
            "Application.onCreate pid=${Process.myPid()} process=${getProcessNameForLog()} package=$packageName"
        )
        runCatching {
            VirtualCore.get().initialize(object : VirtualCore.VirtualInitializer() {})
            PersistentDiagnosticLog.info(TAG, "VirtualCore.initialize success pid=${Process.myPid()}")
        }.onFailure { error ->
            PersistentDiagnosticLog.error(TAG, "VirtualApp initialize failed", error)
        }
    }

    /**
     * 系统进入低内存或关键内存状态时释放宿主 Java 堆中的可回收对象。
     *
     * 游戏资源与运行态数据不会被清理；该处理只针对当前进程中已失去引用的临时对象。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            releaseTransientMemory("onTrimMemory:$level")
        }
    }

    /** 传统低内存回调到来时同样回收宿主临时对象。 */
    override fun onLowMemory() {
        releaseTransientMemory("onLowMemory")
        super.onLowMemory()
    }

    private fun releaseTransientMemory(source: String) {
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        PersistentDiagnosticLog.warn(
            TAG,
            "Host memory trim source=$source heapUsedBefore=$before"
        )
        runtime.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        PersistentDiagnosticLog.info(
            TAG,
            "Host memory trim completed source=$source heapUsedAfter=$after"
        )
    }

    private class LimbusVirtualConfig(
        private val hostPackageName: String
    ) : SettingConfig() {
        override fun getHostPackageName(): String = hostPackageName

        override fun getPluginEnginePackageName(): String? = null

        override fun isAllowCreateShortcut(): Boolean = false

        override fun isHideForegroundNotification(): Boolean = true

        /**
         * 构建可被 Android 识别的容器前台通知。
         *
         * Android 要求前台通知必须带有效的小图标；若沿用 VirtualApp 的空通知，
         * ColorOS 会把通知判定为损坏，容器服务也更容易在内存整理时被回收。
         *
         * @return 带小图标、说明文字和常驻标记的前台服务通知
         */
        override fun getForegroundNotification(): Notification {
            val context = VirtualCore.get().context
            val openLauncherIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                2781,
                openLauncherIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationChannelCompat.createBuilder(
                context,
                NotificationChannelCompat.DAEMON_ID
            ).apply {
                setSmallIcon(R.drawable.app_icon)
                setContentTitle("边狱公司后台运行")
                setContentText("正在维护容器和游戏运行环境")
                setContentIntent(contentIntent)
                setOngoing(true)
                setCategory(Notification.CATEGORY_SERVICE)
                setSound(null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setVisibility(Notification.VISIBILITY_SECRET)
                }
            }.build()
        }
    }

    companion object {
        private const val TAG = "LimbusZhCNApp"
    }
}

private fun Context.getProcessNameForLog(): String =
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val pid = Process.myPid()
            manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "unknown"
        }
    }.getOrDefault("unknown")
