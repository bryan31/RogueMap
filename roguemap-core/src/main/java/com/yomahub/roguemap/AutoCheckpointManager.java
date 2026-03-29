package com.yomahub.roguemap;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动 checkpoint 管理器
 *
 * <p>支持两种触发模式：
 * <ul>
 *   <li>时间间隔模式：每隔固定时间触发 checkpoint</li>
 *   <li>操作次数模式：每 N 次写操作触发 checkpoint</li>
 * </ul>
 *
 * <p>两种模式可以同时配置，任一条件满足即触发 checkpoint。
 */
public class AutoCheckpointManager {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "roguemap-autocheckpoint");
                t.setDaemon(true);
                return t;
            });

    private final Runnable checkpointAction;
    private final long intervalMillis;      // -1 表示未配置
    private final int operationThreshold;   // -1 表示未配置

    private final AtomicInteger operationCount = new AtomicInteger(0);
    private volatile ScheduledFuture<?> scheduledFuture;
    private volatile boolean running = false;

    /**
     * 创建自动 checkpoint 管理器
     *
     * @param checkpointAction   checkpoint 操作（调用数据结构的 checkpoint 方法）
     * @param intervalMillis     时间间隔（毫秒），-1 表示不启用时间模式
     * @param operationThreshold 操作次数阈值，-1 表示不启用操作计数模式
     */
    public AutoCheckpointManager(Runnable checkpointAction, long intervalMillis, int operationThreshold) {
        this.checkpointAction = checkpointAction;
        this.intervalMillis = intervalMillis;
        this.operationThreshold = operationThreshold;
    }

    /**
     * 启动自动 checkpoint
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;

        // 启动定时任务（如果配置了时间间隔）
        if (intervalMillis > 0) {
            scheduledFuture = SCHEDULER.scheduleAtFixedRate(
                    this::doCheckpoint,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 停止自动 checkpoint
     */
    public void stop() {
        running = false;

        // 停止定时任务
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    /**
     * 写操作后调用，用于操作计数模式
     */
    public void onWriteOperation() {
        if (!running || operationThreshold <= 0) {
            return;
        }

        int count = operationCount.incrementAndGet();
        if (count >= operationThreshold) {
            // 重置计数器并触发 checkpoint
            // 使用 compareAndSet 避免多次触发
            if (operationCount.compareAndSet(count, 0)) {
                doCheckpoint();
            }
        }
    }

    /**
     * 执行 checkpoint
     */
    private void doCheckpoint() {
        if (!running) {
            return;
        }
        try {
            checkpointAction.run();
        } catch (Exception e) {
            // checkpoint 失败不中断程序，仅打印警告
            System.err.println("[RogueMap] Auto checkpoint failed: " + e.getMessage());
        }
    }

    /**
     * 检查是否已启用自动 checkpoint
     */
    boolean isEnabled() {
        return intervalMillis > 0 || operationThreshold > 0;
    }
}
