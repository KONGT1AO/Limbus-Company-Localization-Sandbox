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
 * 边狱公司游戏横屏方向与单向互锁自适应双向旋转管理器。
 *
 * <p>解决系统开启“竖屏锁定”后游戏被系统死锁在单一横屏方向且无法旋转的问题。
 * 针对手持翻转时容易发生的“误触发反向拉回”现象，采用相隔 140° 安全盲区的
 * 单向互锁状态机（Hysteresis Interlock State Machine）：
 * 处于反向横屏时坚决锁定，只有当物理旋转明确进入相反核心区（250°~290°）并稳定保持 300ms 时才允许切换；
 * 反之亦然。彻底消除过渡倾斜角引发的反复横跳与强行拉回。</p>
 */
public class LimbusOrientationManager {
    private static final String TAG = "LimbusOrientation";
    private static final String CONFIG_FILE_NAME = "limbus_screen_orientation.cfg";

    public static final int MODE_AUTO_SENSOR = 0;       // 智能双向自适应旋转（默认反向横屏，音量键朝上，突破竖屏锁定）
    public static final int MODE_REVERSE_LANDSCAPE = 1; // 锁定反向横屏（音量键始终朝上）
    public static final int MODE_LANDSCAPE = 2;         // 锁定常规横屏（音量键始终朝下）
    public static final int MODE_SYSTEM = 3;            // 系统原生托管

    // 核心判定区定义（各取中心 ±20° 严格横握区）
    private static final int REVERSE_LANDSCAPE_MIN = 70;
    private static final int REVERSE_LANDSCAPE_MAX = 110;
    private static final int LANDSCAPE_MIN = 250;
    private static final int LANDSCAPE_MAX = 290;

    // 滤波与冷却时间
    private static final long DEBOUNCE_HOLD_TIME_MS = 300L;    // 目标角度需持续稳定时间
    private static final long ROTATION_COOLDOWN_MS = 800L;      // 两次旋转之间转场动画冷却

    private static final LimbusOrientationManager sInstance = new LimbusOrientationManager();

    private OrientationEventListener mOrientationListener;
    private WeakReference<Activity> mCurrentActivityRef;

    // 状态机核心字段
    private int mCurrentActiveOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
    private int mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private long mCandidateStartTime = 0L;
    private long mLastRotationTime = 0L;

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
        mCurrentActiveOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        mCandidateStartTime = 0L;
        mLastRotationTime = SystemClock.uptimeMillis();

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
            Log.i(TAG, "OrientationEventListener enabled with interlock state machine for " + activity.getClass().getSimpleName());
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
            mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            mCandidateStartTime = 0L;
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
        mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        mCandidateStartTime = 0L;
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
     * 单向互锁状态机核心判定。
     *
     * @param orientation 0 ~ 359 度物理旋转角，-1 为平放无法探测
     */
    private void handleOrientationChanged(int orientation) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            // 平放或倾斜无法判定，立即清空候选，坚决维持当前状态
            mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            mCandidateStartTime = 0L;
            return;
        }
        Activity activity = mCurrentActivityRef != null ? mCurrentActivityRef.get() : null;
        if (activity == null || activity.isFinishing()) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        // 处于旋转冷却时间内，禁止做任何状态机切换，防止动画期间抖动
        if (now - mLastRotationTime < ROTATION_COOLDOWN_MS) {
            mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            mCandidateStartTime = 0L;
            return;
        }

        // 核心单向互锁逻辑：
        // 1. 如果当前是反向横屏（音量键在上），唯一合法的转移目标是常规横屏（250°~290°）
        if (mCurrentActiveOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            boolean inTargetLandscapeZone = (orientation >= LANDSCAPE_MIN && orientation <= LANDSCAPE_MAX);
            if (inTargetLandscapeZone) {
                if (mPendingCandidateOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    mCandidateStartTime = now;
                } else if (now - mCandidateStartTime >= DEBOUNCE_HOLD_TIME_MS) {
                    // 确认已持续稳定在常规横屏核心区，执行单向翻转
                    executeRotation(activity, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, orientation, now);
                }
            } else {
                // 只要未进入严格常规横屏区（包括 303°、58° 等过渡盲区），坚决死守反向横屏，清空候选，绝不拉回！
                mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                mCandidateStartTime = 0L;
            }
        }
        // 2. 如果当前是常规横屏（音量键在下），唯一合法的转移目标是反向横屏（70°~110°）
        else {
            boolean inTargetReverseZone = (orientation >= REVERSE_LANDSCAPE_MIN && orientation <= REVERSE_LANDSCAPE_MAX);
            if (inTargetReverseZone) {
                if (mPendingCandidateOrientation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                    mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    mCandidateStartTime = now;
                } else if (now - mCandidateStartTime >= DEBOUNCE_HOLD_TIME_MS) {
                    // 确认已持续稳定在反向横屏核心区，执行单向翻转
                    executeRotation(activity, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, orientation, now);
                }
            } else {
                // 只要未进入严格反向横屏区，坚决死守常规横屏，清空候选，绝不拉回！
                mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                mCandidateStartTime = 0L;
            }
        }
    }

    private void executeRotation(Activity activity, int targetOrientation, int triggerAngle, long now) {
        mCurrentActiveOrientation = targetOrientation;
        mPendingCandidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        mCandidateStartTime = 0L;
        mLastRotationTime = now;
        Log.i(TAG, "Interlock state machine rotated to " + targetOrientation + " (trigger angle=" + triggerAngle + ")");
        try {
            activity.setRequestedOrientation(targetOrientation);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to call setRequestedOrientation", t);
        }
    }
}
