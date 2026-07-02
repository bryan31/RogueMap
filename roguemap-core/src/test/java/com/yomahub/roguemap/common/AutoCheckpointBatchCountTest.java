package com.yomahub.roguemap.common;

import com.yomahub.roguemap.AutoCheckpointManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AutoCheckpointManager 批量写操作计数测试
 */
public class AutoCheckpointBatchCountTest {

    @Test
    public void testBatchCountTriggersCheckpoint() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 10);
        mgr.start();

        mgr.onWriteOperations(4);
        mgr.onWriteOperations(4);
        assertEquals(0, checkpoints.get());

        // 累计 12 >= 10，触发一次并重置计数
        mgr.onWriteOperations(4);
        assertEquals(1, checkpoints.get());

        // 计数已重置，9 < 10 不触发
        mgr.onWriteOperations(9);
        assertEquals(1, checkpoints.get());

        mgr.stop();
    }

    @Test
    public void testSingleOperationDelegatesToBatch() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 3);
        mgr.start();

        mgr.onWriteOperation();
        mgr.onWriteOperation();
        assertEquals(0, checkpoints.get());

        mgr.onWriteOperation();
        assertEquals(1, checkpoints.get());

        mgr.stop();
    }

    @Test
    public void testNotStartedDoesNotTrigger() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 2);
        // 未调用 start()
        mgr.onWriteOperations(5);
        assertEquals(0, checkpoints.get());
    }

    @Test
    public void testNonPositiveCountIgnored() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 1);
        mgr.start();
        mgr.onWriteOperations(0);
        mgr.onWriteOperations(-3);
        assertEquals(0, checkpoints.get());
        mgr.stop();
    }
}
