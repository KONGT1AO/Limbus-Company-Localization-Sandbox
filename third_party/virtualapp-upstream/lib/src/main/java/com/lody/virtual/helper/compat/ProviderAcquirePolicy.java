package com.lody.virtual.helper.compat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 虚拟容器 ContentProvider 获取、防自递归重入与后台扫描退避策略。
 *
 * <p>集中定义与 Android Framework 解耦的纯逻辑规则，便于在单元测试中验证。</p>
 */
public final class ProviderAcquirePolicy {
    /** 跨进程等待虚拟应用绑定完成的超时上限（毫秒）。 */
    public static final long BIND_APPLICATION_TIMEOUT_MS = 15_000L;
    /** 服务端等待虚拟子进程完成初始化的超时上限（毫秒）。 */
    public static final long PROCESS_INIT_TIMEOUT_MS = 15_000L;
    /** 主线程本地补装单个 Provider 的等待超时上限（毫秒）。 */
    public static final long LOCAL_PROVIDER_INSTALL_TIMEOUT_MS = 5_000L;

    private static final int FAST_SCAN_ITERATIONS = 10;
    private static final long FAST_SCAN_INTERVAL_MS = 25L;
    private static final long SLOW_SCAN_INTERVAL_MS = 100L;

    private ProviderAcquirePolicy() {
    }

    /**
     * 判断请求目标是否属于当前正在运行的同一虚拟进程。
     *
     * <p>命中时应在本地查找或本地补装 Provider，严禁再次经由 {@code ContentResolver}
     * 或 {@code VActivityManager} 跨进程回调自身，避免在 Android 16 上触发自递归死锁。</p>
     */
    public static boolean isSameVirtualProcess(
            String currentPackage,
            String currentProcess,
            String targetPackage,
            String targetProcess
    ) {
        if (isEmpty(currentPackage) || isEmpty(targetPackage)) {
            return false;
        }
        if (!currentPackage.equals(targetPackage)) {
            return false;
        }
        String resolvedCurrent = isEmpty(currentProcess) ? currentPackage : currentProcess;
        String resolvedTarget = isEmpty(targetProcess) ? targetPackage : targetProcess;
        return resolvedCurrent.equals(resolvedTarget);
    }

    /**
     * 将以分号分隔的 authority 声明拆分为规范化集合。
     */
    public static Set<String> splitAuthorities(String rawAuthority) {
        if (isEmpty(rawAuthority)) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String part : rawAuthority.split(";")) {
            if (part != null) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * 计算 AppSealing 启动阶段后台扫描线程的休眠间隔。
     *
     * <p>前若干轮保持较高频率以便第一时间拦截刚加载的原生库，随后退避至较低频率，
     * 避免持续争抢 {@code g_dl_mutex} 与刷爆系统日志。</p>
     */
    public static long computeAppSealingScanSleepMs(int completedScans) {
        return completedScans < FAST_SCAN_ITERATIONS
                ? FAST_SCAN_INTERVAL_MS
                : SLOW_SCAN_INTERVAL_MS;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
