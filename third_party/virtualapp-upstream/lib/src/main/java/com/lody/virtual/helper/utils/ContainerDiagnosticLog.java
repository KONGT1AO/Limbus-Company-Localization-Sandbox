package com.lody.virtual.helper.utils;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 容器内轻量持久化诊断日志写入器。
 *
 * <p>将关键生命周期里程碑与异常超时写入与宿主相同的 {@code no_backup/diagnostics/runtime-*.log}
 * 目录，确保进程被 OEM 强杀或 logcat 被刷爆时，导出诊断包仍保留关键现场。</p>
 */
public final class ContainerDiagnosticLog {
    private static final String TAG = "LimbusVADiag";
    private static final String DIRECTORY_NAME = "diagnostics";
    private static final long MAX_LOG_BYTES = 256L * 1024L;
    private static final Object WRITE_LOCK = new Object();

    private ContainerDiagnosticLog() {
    }

    public static void info(String tag, String message) {
        Log.i(tag, message);
        append("INFO", tag, message, null);
    }

    public static void warn(String tag, String message) {
        Log.w(tag, message);
        append("WARN", tag, message, null);
    }

    public static void warn(String tag, String message, Throwable error) {
        Log.w(tag, message, error);
        append("WARN", tag, message, error);
    }

    public static void error(String tag, String message, Throwable error) {
        Log.e(tag, message, error);
        append("ERROR", tag, message, error);
    }

    private static void append(String level, String tag, String message, Throwable error) {
        try {
            Context context = VirtualCore.get() != null ? VirtualCore.get().getContext() : null;
            if (context == null) {
                return;
            }
            File baseDir = context.getNoBackupFilesDir();
            if (baseDir == null) {
                return;
            }
            File directory = new File(baseDir, DIRECTORY_NAME);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return;
            }
            String processSuffix = sanitizeProcessName(resolveProcessName(context));
            File target = new File(directory, "runtime-va-" + processSuffix + ".log");
            String timestamp = formatUtcTimestamp();
            StringBuilder line = new StringBuilder(256);
            line.append(timestamp)
                    .append(" pid=").append(Process.myPid())
                    .append(" tid=").append(Process.myTid())
                    .append(' ').append(level)
                    .append('/').append(tag == null ? TAG : tag)
                    .append(": ").append(message == null ? "" : message)
                    .append('\n');
            if (error != null) {
                String stack = Log.getStackTraceString(error);
                if (stack != null && !stack.isEmpty()) {
                    line.append(stack);
                    if (!stack.endsWith("\n")) {
                        line.append('\n');
                    }
                }
            }
            synchronized (WRITE_LOCK) {
                if (target.exists() && target.length() >= MAX_LOG_BYTES) {
                    File previous = new File(directory, "runtime-va-" + processSuffix + ".previous.log");
                    if (previous.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        previous.delete();
                    }
                    if (!target.renameTo(previous)) {
                        new FileOutputStream(target, false).close();
                    }
                }
                try (FileOutputStream out = new FileOutputStream(target, true);
                     OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                    writer.write(line.toString());
                    writer.flush();
                }
            }
        } catch (Throwable ignored) {
            // 诊断日志写入属于辅助观测，绝不能影响虚拟容器主流程。
        }
    }

    private static String resolveProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        String pkg = context.getPackageName();
        return pkg != null && !pkg.isEmpty() ? pkg : "unknown";
    }

    private static String sanitizeProcessName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        String sanitized = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
    }

    private static String formatUtcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
