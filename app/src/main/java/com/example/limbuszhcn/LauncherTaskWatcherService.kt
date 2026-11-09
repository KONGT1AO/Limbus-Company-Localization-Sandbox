package com.example.limbuszhcn

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.lody.virtual.client.stub.HiddenForeNotification
import com.lody.virtual.client.stub.KeepAliveService

/**
 * 监听启动台主任务在多任务栏（Recents）被用户手动划掉（Task Removed）的事件。
 *
 * 当用户关闭启动台且当前没有正在运行的游戏实例时：
 * 1. 停止容器的 KeepAliveService；
 * 2. 清除前台守护通知（Notification ID 2781）；
 * 3. 停止自身，防止常驻残留。
 */
class LauncherTaskWatcherService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        PersistentDiagnosticLog.info(TAG, "LauncherTaskWatcherService.onTaskRemoved received")
        try {
            val runtime = AndroidContainerRuntimeFactory.create(
                applicationContext,
                filesDir.toPath().resolve("container-workspace")
            )
            val isGameRunning = runCatching {
                runtime.isGameRunning()
            }.getOrDefault(false)

            PersistentDiagnosticLog.info(TAG, "LauncherTaskWatcherService onTaskRemoved, isGameRunning=$isGameRunning")

            if (!isGameRunning) {
                // 1. 清理可能残留的失效游戏会话
                com.lody.virtual.client.hook.delegate.LimbusGameSessionTracker.get().clearSession(applicationContext)

                // 2. 通知容器进程停止 KeepAliveService
                KeepAliveService.stop(applicationContext)

                // 3. 清除 2781 守护通知
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(HiddenForeNotification.FOREGROUND_NOTIFICATION_ID)
                notificationManager?.cancelAll()
                PersistentDiagnosticLog.info(TAG, "LauncherTaskWatcherService dismissed keepalive notification")
            }
        } catch (error: Throwable) {
            PersistentDiagnosticLog.error(TAG, "Error cleaning up tasks onTaskRemoved", error)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "LauncherTaskWatcher"

        fun start(context: Context) {
            try {
                val intent = Intent(context, LauncherTaskWatcherService::class.java)
                context.startService(intent)
            } catch (error: Throwable) {
                PersistentDiagnosticLog.warn(TAG, "Unable to start LauncherTaskWatcherService", error)
            }
        }
    }
}
