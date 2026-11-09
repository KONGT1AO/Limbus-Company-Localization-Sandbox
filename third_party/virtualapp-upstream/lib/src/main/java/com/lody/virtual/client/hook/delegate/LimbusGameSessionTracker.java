package com.lody.virtual.client.hook.delegate;

import android.app.Activity;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 边狱公司游戏运行时会话追踪器。
 *
 * <p>穿透 VirtualApp 沙箱重定向，直接在宿主真实私有目录记录游戏主进程的真实 Linux PID、
 * 虚拟槽位 (vpid) 和系统任务栈 ID (taskId)。宿主启动台可通过此会话记录结合系统原生
 * 进程表进行毫秒级极速探活，彻底杜绝跨进程同步 Binder 调用在后台被 Freezer 挂起而导致的
 * “后台游戏存活却检测不到”的误判。</p>
 */
public class LimbusGameSessionTracker {
    private static final String TAG = "LimbusSessionTracker";
    private static final String SESSION_FILE_NAME = "limbus_game_session.cfg";

    private static final LimbusGameSessionTracker sInstance = new LimbusGameSessionTracker();

    public static LimbusGameSessionTracker get() {
        return sInstance;
    }

    public static class SessionInfo {
        public final int pid;
        public final int vpid;
        public final int taskId;
        public final long updatedAt;

        public SessionInfo(int pid, int vpid, int taskId, long updatedAt) {
            this.pid = pid;
            this.vpid = vpid;
            this.taskId = taskId;
            this.updatedAt = updatedAt;
        }

        public boolean isValid() {
            return pid > 0 && vpid >= 0 && taskId > 0;
        }

        @Override
        public String toString() {
            return "SessionInfo{pid=" + pid + ", vpid=" + vpid + ", taskId=" + taskId + ", updatedAt=" + updatedAt + '}';
        }
    }

    private static File getSessionFile(Context context) {
        // 1. 尝试 VirtualCore context
        try {
            VirtualCore core = VirtualCore.get();
            if (core != null && core.getContext() != null) {
                File dir = core.getContext().getFilesDir();
                if (dir != null) {
                    return new File(dir, SESSION_FILE_NAME);
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. 尝试传入的 Context
        if (context != null) {
            try {
                Context appCtx = context.getApplicationContext();
                if (appCtx != null && appCtx.getFilesDir() != null) {
                    return new File(appCtx.getFilesDir(), SESSION_FILE_NAME);
                }
                if (context.getFilesDir() != null) {
                    return new File(context.getFilesDir(), SESSION_FILE_NAME);
                }
            } catch (Throwable ignored) {
            }
        }
        // 3. 宿主主私有目录固定路径兜底
        File fallback = new File("/data/user/0/io.limbuspe.box/files", SESSION_FILE_NAME);
        if (fallback.getParentFile() != null && fallback.getParentFile().exists()) {
            return fallback;
        }
        return new File("/data/data/io.limbuspe.box/files", SESSION_FILE_NAME);
    }

    public synchronized void recordSession(Context context, int pid, int vpid, int taskId) {
        if (pid <= 0 || vpid < 0 || taskId <= 0) {
            return;
        }
        File file = getSessionFile(context);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file, false)) {
                writer.write(pid + "\n" + vpid + "\n" + taskId + "\n" + System.currentTimeMillis() + "\n");
                writer.flush();
            }
            Log.i(TAG, "Recorded game session: pid=" + pid + " vpid=" + vpid + " taskId=" + taskId + " path=" + file.getAbsolutePath());
        } catch (Throwable error) {
            Log.w(TAG, "Failed to record game session", error);
        }
    }

    public synchronized SessionInfo readSession(Context context) {
        File file = getSessionFile(context);
        if (file == null || !file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String linePid = reader.readLine();
            String lineVpid = reader.readLine();
            String lineTaskId = reader.readLine();
            String lineUpdatedAt = reader.readLine();
            if (linePid != null && lineVpid != null && lineTaskId != null) {
                int pid = Integer.parseInt(linePid.trim());
                int vpid = Integer.parseInt(lineVpid.trim());
                int taskId = Integer.parseInt(lineTaskId.trim());
                long updatedAt = lineUpdatedAt != null ? Long.parseLong(lineUpdatedAt.trim()) : 0L;
                SessionInfo info = new SessionInfo(pid, vpid, taskId, updatedAt);
                if (info.isValid()) {
                    return info;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Failed to read game session", error);
        }
        return null;
    }

    public synchronized void clearSession(Context context) {
        File file = getSessionFile(context);
        if (file != null && file.exists()) {
            try {
                boolean deleted = file.delete();
                Log.i(TAG, "Cleared game session file: deleted=" + deleted);
            } catch (Throwable error) {
                Log.w(TAG, "Failed to clear game session file", error);
            }
        }
    }

    public synchronized void clearSessionIfMatchingPid(Context context, int currentPid) {
        SessionInfo info = readSession(context);
        if (info != null && (info.pid == currentPid || currentPid == 0)) {
            clearSession(context);
        }
    }

    public void onActivityCreated(Activity activity, int vpid) {
        if (activity == null) return;
        int pid = Process.myPid();
        int taskId = activity.getTaskId();
        recordSession(activity, pid, vpid, taskId);
    }

    public void onActivityResumed(Activity activity, int vpid) {
        if (activity == null) return;
        int pid = Process.myPid();
        int taskId = activity.getTaskId();
        recordSession(activity, pid, vpid, taskId);
    }

    public void onActivityDestroyed(Activity activity) {
        if (activity == null) return;
        int pid = Process.myPid();
        clearSessionIfMatchingPid(activity, pid);
    }
}
