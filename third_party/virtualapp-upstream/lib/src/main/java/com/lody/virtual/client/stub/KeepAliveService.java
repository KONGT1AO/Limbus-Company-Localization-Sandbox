package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.lody.virtual.helper.utils.VLog;

/**
 * VirtualApp 引擎进程 (:x) 基础守护服务。
 *
 * <p>重构后：该服务不再作为前台通知服务运行，不再创建或管理任何通知栏图标。
 * 它作为一个纯净的 Binder 服务，供运行中的游戏进程 (:p0 ~ :p99) 的 {@link ShadowService}
 * 通过 bindService(..., BIND_IMPORTANT) 挂靠绑定。系统会自动将游戏的前台服务高优先级
 * 传递给本服务所在进程，既达成容器服务后台存活，又彻底消除了多进程争抢 2781 通知 ID
 * 和启动台未进游戏就常驻通知的问题。</p>
 *
 * @author Lody
 */
public class KeepAliveService extends Service {

    public static final String ACTION_STOP_KEEP_ALIVE = "com.lody.virtual.action.STOP_KEEP_ALIVE";
    private final IBinder mBinder = new LocalBinder();

    public static class LocalBinder extends Binder {
        public KeepAliveService getService() {
            return null;
        }
    }

    public static boolean useDirectForegroundPromotion(int sdkInt) {
        return sdkInt >= android.os.Build.VERSION_CODES.O;
    }

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
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        VLog.i("LimbusVA", "KeepAliveService created in engine process");
    }

    @Override
    public void onDestroy() {
        VLog.i("LimbusVA", "KeepAliveService destroyed");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_KEEP_ALIVE.equals(intent.getAction())) {
            VLog.i("LimbusVA", "KeepAliveService received ACTION_STOP_KEEP_ALIVE");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }
}
