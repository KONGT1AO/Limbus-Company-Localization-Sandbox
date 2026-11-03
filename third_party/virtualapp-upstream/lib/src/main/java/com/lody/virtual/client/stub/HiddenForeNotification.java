package com.lody.virtual.client.stub;

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.IBinder;

import com.lody.virtual.R;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.compat.NotificationChannelCompat;
import com.lody.virtual.helper.utils.VLog;

/**
 * 为 VirtualApp 容器进程维护前台通知，并兼容不同 Android 版本的前台服务要求。
 */
public class HiddenForeNotification extends Service {
    public static final int FOREGROUND_NOTIFICATION_ID = 2781;

    /**
     * 取消指定服务的前台状态。
     *
     * @param service 当前持有前台通知的服务
     */
    public static void hideForeground(Service service) {
        try {
            service.stopForeground(true);
            if (VERSION.SDK_INT <= 24) {
                service.stopService(new Intent(service, HiddenForeNotification.class));
            }
        } catch (Throwable e) {
            VLog.w("LimbusVA", "Unable to stop foreground on KeepAliveService: %s", e.toString());
        }
    }

    /**
     * 将指定服务提升为前台服务。
     *
     * @param service 需要保持运行的 VirtualApp 服务
     */
    public static void bindForeground(Service service) {
        promoteToForeground(service);
        if (VERSION.SDK_INT <= 24) {
            service.startService(new Intent(service, HiddenForeNotification.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if(VirtualCore.getConfig().isHideForegroundNotification()) {
            startForeground();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(!VirtualCore.getConfig().isHideForegroundNotification()) {
            try {
                startForeground();
                stopForeground(true);
                stopSelf();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return START_NOT_STICKY;
    }

    private void startForeground(){
        promoteToForeground(this);
    }

    /**
     * 使用清单声明的 specialUse 类型启动前台服务。
     *
     * <p>Android 14 及以上如果未同时传入服务类型，会直接抛出
     * {@link android.app.MissingForegroundServiceTypeException}。旧版本继续使用
     * 两参数接口，避免访问不存在的运行时 API。</p>
     *
     * @param service 需要提升到前台的服务实例
     */
    private static void promoteToForeground(Service service) {
        try {
            if (VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(
                        FOREGROUND_NOTIFICATION_ID,
                        VirtualCore.getConfig().getForegroundNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return;
            }

            service.startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    VirtualCore.getConfig().getForegroundNotification()
            );
        } catch (Throwable e) {
            // OriginOS 等系统在后台关联拉起 :x 服务进程时可能拒绝前台通知提升，保留普通进程存活即可。
            VLog.w("LimbusVA", "Skip KeepAliveService foreground promotion: %s", e.toString());
        }
    }

    @Override
    public void onDestroy() {
        if(VirtualCore.getConfig().isHideForegroundNotification()) {
            stopForeground(true);
        }
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }
}
