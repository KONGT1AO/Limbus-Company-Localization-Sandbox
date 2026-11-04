package com.lody.virtual.client.hook.delegate;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 边狱公司游戏横屏方向锁定管理器。
 *
 * <p>解决系统开启“竖屏锁定”后游戏被系统死锁在单一常规横屏（音量键朝下、易误触）的问题。
 * 允许玩家在启动台中自由选择锁定方向（反向横屏/常规横屏），启动时由容器直接锁定在目标方向，
 * 跨进程直接穿透定位宿主配置路径，彻底消除路径重定向与传感器判定，坚如磐石。</p>
 */
public class LimbusOrientationManager {
    private static final String TAG = "LimbusOrientation";
    private static final String CONFIG_FILE_NAME = "limbus_screen_orientation.cfg";

    public static final int MODE_REVERSE_LANDSCAPE = 0; // 锁定反向横屏（音量键始终朝上，推荐默认）
    public static final int MODE_LANDSCAPE = 1;         // 锁定常规横屏（音量键始终朝下）

    // 向后兼容旧版本常量定义
    public static final int MODE_AUTO_SENSOR = 0;
    public static final int MODE_SYSTEM = 1;

    private static final LimbusOrientationManager sInstance = new LimbusOrientationManager();

    public static LimbusOrientationManager get() {
        return sInstance;
    }

    /**
     * 跨进程获取绝对可靠的配置文件对象（避免 VirtualApp 内部沙箱目录重定向导致读写不同步）。
     */
    private static File getConfigFile(Context context) {
        // 1. 尝试宿主 VirtualCore context
        try {
            VirtualCore core = VirtualCore.get();
            if (core != null && core.getContext() != null) {
                File dir = core.getContext().getFilesDir();
                if (dir != null) {
                    File f = new File(dir, CONFIG_FILE_NAME);
                    if (f.exists()) {
                        return f;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. 尝试传入的 context
        if (context != null) {
            try {
                Context appCtx = context.getApplicationContext();
                if (appCtx != null && appCtx.getFilesDir() != null) {
                    File f = new File(appCtx.getFilesDir(), CONFIG_FILE_NAME);
                    if (f.exists()) {
                        return f;
                    }
                }
                if (context.getFilesDir() != null) {
                    File f = new File(context.getFilesDir(), CONFIG_FILE_NAME);
                    if (f.exists()) {
                        return f;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 3. 宿主主私有目录固定路径兜底（同一 UID 拥有完全读写权限）
        File fallback = new File("/data/user/0/io.limbuspe.box/files", CONFIG_FILE_NAME);
        if (fallback.exists()) {
            return fallback;
        }
        File fallbackData = new File("/data/data/io.limbuspe.box/files", CONFIG_FILE_NAME);
        if (fallbackData.exists()) {
            return fallbackData;
        }
        return fallback;
    }

    /**
     * 读取用户设定的横屏锁定模式。
     */
    public static int getOrientationMode(Context context) {
        File configFile = getConfigFile(context);
        if (configFile == null || !configFile.exists()) {
            return MODE_REVERSE_LANDSCAPE;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line = reader.readLine();
            if (line != null) {
                int raw = Integer.parseInt(line.trim());
                if (raw == 2 || raw == MODE_LANDSCAPE) {
                    return MODE_LANDSCAPE;
                }
                return MODE_REVERSE_LANDSCAPE;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read orientation config, using default", e);
        }
        return MODE_REVERSE_LANDSCAPE;
    }

    /**
     * 持久化保存横屏锁定模式。
     */
    public static void setOrientationMode(Context context, int mode) {
        File configFile = getConfigFile(context);
        if (configFile == null) {
            configFile = new File("/data/user/0/io.limbuspe.box/files", CONFIG_FILE_NAME);
        }
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(configFile, false)) {
                writer.write(String.valueOf(mode));
                writer.flush();
            }
            Log.i(TAG, "Screen orientation mode saved: " + mode + " to " + configFile.getAbsolutePath());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to save orientation config", e);
        }
    }

    /**
     * 根据当前模式决议启动占位屏幕朝向。
     */
    public static int resolveInitialOrientation(Context context, int fallbackOrientation) {
        int mode = getOrientationMode(context);
        if (mode == MODE_LANDSCAPE) {
            // 模式 1：物理音量键在下方
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        } else {
            // 模式 0（推荐）：物理音量键在上方（防按键误触）
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
    }

    /**
     * 将锁定方向应用到游戏主 Activity。
     */
    public void applyLockedOrientation(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        int target = resolveInitialOrientation(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        try {
            int current = activity.getRequestedOrientation();
            if (current != target) {
                activity.setRequestedOrientation(target);
                Log.i(TAG, "Applied locked orientation: " + target + " (was " + current + ") to " + activity.getClass().getSimpleName());
            } else {
                Log.i(TAG, "Orientation already matches target: " + target + " on " + activity.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply requested orientation", t);
        }
    }

    public synchronized void attach(Activity activity) {
        applyLockedOrientation(activity);
    }

    public synchronized void onResume(Activity activity) {
        applyLockedOrientation(activity);
    }

    public synchronized void onPause(Activity activity) {
    }

    public synchronized void onDestroy(Activity activity) {
    }
}
