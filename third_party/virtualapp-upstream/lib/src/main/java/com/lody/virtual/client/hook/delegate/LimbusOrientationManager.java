package com.lody.virtual.client.hook.delegate;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.OrientationEventListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.ref.WeakReference;

/**
 * 边狱公司游戏横屏方向与自适应双向旋转管理器。
 *
 * <p>解决系统开启“竖屏锁定”后游戏被系统死锁在单一横屏方向且无法旋转的问题。
 * 通过直接监听硬件底层加速度计（不受系统竖屏锁定/自动旋转开关制约），
 * 在用户翻转手机时主动显式请求横屏与反向横屏切换，实现双向自由旋转与惯用方向适配。</p>
 */
public class LimbusOrientationManager {
    private static final String TAG = "LimbusOrientation";
    private static final String CONFIG_FILE_NAME = "limbus_screen_orientation.cfg";

    public static final int MODE_AUTO_SENSOR = 0;       // 智能自适应双向横屏（默认反向横屏，音量键朝上，突破竖屏锁定）
    public static final int MODE_REVERSE_LANDSCAPE = 1; // 锁定反向横屏（音量键始终朝上）
    public static final int MODE_LANDSCAPE = 2;         // 锁定常规横屏（音量键始终朝下）
    public static final int MODE_SYSTEM = 3;            // 系统原生托管

    private static final LimbusOrientationManager sInstance = new LimbusOrientationManager();

    private OrientationEventListener mOrientationListener;
    private WeakReference<Activity> mCurrentActivityRef;
    private int mLastRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private long mLastOrientationChangeTime = 0L;

    public static LimbusOrientationManager get() {
        return sInstance;
    }

    /**
     * 读取用户设定的横屏模式。
     */
    public static int getOrientationMode(Context context) {
        if (context == null) {
            return MODE_AUTO_SENSOR;
        }
        File configFile = new File(context.getFilesDir(), CONFIG_FILE_NAME);
        if (!configFile.exists()) {
            return MODE_AUTO_SENSOR;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line = reader.readLine();
            if (line != null) {
                return Integer.parseInt(line.trim());
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read orientation config, using default", e);
        }
        return MODE_AUTO_SENSOR;
    }

    /**
     * 持久化保存横屏模式。
     */
    public static void setOrientationMode(Context context, int mode) {
        if (context == null) {
            return;
        }
        try {
            File configFile = new File(context.getFilesDir(), CONFIG_FILE_NAME);
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(configFile, false)) {
                writer.write(String.valueOf(mode));
                writer.flush();
            }
            Log.i(TAG, "Screen orientation mode saved: " + mode);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to save orientation config", e);
        }
    }

    /**
     * 根据当前模式计算初始屏幕朝向。
     */
    public static int resolveInitialOrientation(Context context, int fallbackOrientation) {
        int mode = getOrientationMode(context);
        switch (mode) {
            case MODE_AUTO_SENSOR:
            case MODE_REVERSE_LANDSCAPE:
                // 默认使用反向横屏（音量键朝上）
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            case MODE_LANDSCAPE:
                // 常规横屏（音量键朝下）
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case MODE_SYSTEM:
            default:
                return fallbackOrientation;
        }
    }

    /**
     * 将方向监听器挂载到目标游戏 Activity。
     */
    public synchronized void attach(Activity activity) {
        if (activity == null) {
            return;
        }
        int mode = getOrientationMode(activity);
        if (mode != MODE_AUTO_SENSOR) {
            disableListener();
            return;
        }
        mCurrentActivityRef = new WeakReference<>(activity);
        mLastRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        mLastOrientationChangeTime = SystemClock.uptimeMillis();

        if (mOrientationListener == null) {
            mOrientationListener = new OrientationEventListener(activity.getApplicationContext(), SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    handleOrientationChanged(orientation);
                }
            };
        }
        if (mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
            Log.i(TAG, "OrientationEventListener enabled for " + activity.getClass().getSimpleName());
        } else {
            Log.w(TAG, "OrientationEventListener cannot detect orientation on this hardware");
        }
    }

    /**
     * Activity 回到前台，重新恢复传感器监听。
     */
    public synchronized void onResume(Activity activity) {
        if (activity == null) {
            return;
        }
        int mode = getOrientationMode(activity);
        if (mode == MODE_AUTO_SENSOR) {
            mCurrentActivityRef = new WeakReference<>(activity);
            if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) {
                mOrientationListener.enable();
            }
        } else {
            disableListener();
        }
    }

    /**
     * Activity 切出后台，立即停止监听以保护电量。
     */
    public synchronized void onPause(Activity activity) {
        disableListener();
    }

    /**
     * Activity 销毁。
     */
    public synchronized void onDestroy(Activity activity) {
        disableListener();
        if (mCurrentActivityRef != null && mCurrentActivityRef.get() == activity) {
            mCurrentActivityRef.clear();
        }
    }

    private void disableListener() {
        if (mOrientationListener != null) {
            try {
                mOrientationListener.disable();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 处理传感器物理角度变化。
     *
     * @param orientation 0 ~ 359 度物理旋转角，-1 为无法探测（如平放）
     */
    private void handleOrientationChanged(int orientation) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return;
        }
        Activity activity = mCurrentActivityRef != null ? mCurrentActivityRef.get() : null;
        if (activity == null || activity.isFinishing()) {
            return;
        }

        int targetOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        // 45° ~ 135°: 手机向左横置（逆时针横屏，音量键在上方）
        if (orientation >= 45 && orientation <= 135) {
            targetOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
        // 225° ~ 315°: 手机向右横置（顺时针横屏，音量键在下方）
        else if (orientation >= 225 && orientation <= 315) {
            targetOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
        // 其余角度（竖屏、倒立竖屏）：保持现状不干扰

        if (targetOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            long now = SystemClock.uptimeMillis();
            if (targetOrientation != mLastRequestedOrientation && (now - mLastOrientationChangeTime > 350L)) {
                mLastRequestedOrientation = targetOrientation;
                mLastOrientationChangeTime = now;
                Log.i(TAG, "Auto-rotating orientation to " + targetOrientation + " (sensor angle=" + orientation + ")");
                try {
                    activity.setRequestedOrientation(targetOrientation);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to call setRequestedOrientation", t);
                }
            }
        }
    }
}
