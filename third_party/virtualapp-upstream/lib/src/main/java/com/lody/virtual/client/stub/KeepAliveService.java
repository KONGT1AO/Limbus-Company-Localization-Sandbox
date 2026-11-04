package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.utils.VLog;


/**
 * @author Lody
 */
public class KeepAliveService extends Service {

    public static final String ACTION_STOP_KEEP_ALIVE = "com.lody.virtual.action.STOP_KEEP_ALIVE";

    public static void stop(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            intent.setAction(ACTION_STOP_KEEP_ALIVE);
            context.startService(intent);
        } catch (Throwable error) {
            VLog.w("LimbusVA", "KeepAliveService.stop error: " + error);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
            // The legacy two-service notification-hiding trick does not promote this service on
            // modern Android. It leaves the VirtualApp server at a killable cached-service adj and
            // a background restart cannot legally start the second foreground service.
            HiddenForeNotification.bindForeground(this);
            VLog.i("LimbusVA", "KeepAliveService promoted directly on Android "
                    + Build.VERSION.SDK_INT);
        } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
            Intent intent = new Intent(this, HiddenForeNotification.class);
            startService(intent);
        } else {
            HiddenForeNotification.bindForeground(this);
        }
    }

    public static boolean useDirectForegroundPromotion(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.O;
    }

    private void stopForegroundAndSelf() {
        try {
            if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
                HiddenForeNotification.hideForeground(this);
            } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
                stopService(new Intent(this, HiddenForeNotification.class));
            } else {
                HiddenForeNotification.hideForeground(this);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable e) {
            VLog.w("LimbusVA", "KeepAliveService stopForeground error: " + e);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
            HiddenForeNotification.hideForeground(this);
        } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
            stopService(new Intent(this, HiddenForeNotification.class));
        } else {
            HiddenForeNotification.hideForeground(this);
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        try {
            boolean gameRunning = VActivityManager.get().isAppRunning(
                    "com.ProjectMoon.LimbusCompany", 0, false
            );
            if (!gameRunning) {
                VLog.i("LimbusVA", "KeepAliveService onTaskRemoved and no game running, stopping self");
                stopForegroundAndSelf();
            }
        } catch (Throwable error) {
            VLog.w("LimbusVA", "KeepAliveService onTaskRemoved check failed: " + error);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_KEEP_ALIVE.equals(intent.getAction())) {
            VLog.i("LimbusVA", "KeepAliveService received ACTION_STOP_KEEP_ALIVE");
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }
}
