package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LinkedQueue 崩溃恢复测试
 *
 * 验证：每次 offer/poll 写入快照后，重新打开文件能恢复正确状态（模拟 dirtyFlag=0 的正常关闭路径）。
 */
public class QueueCrashRecoveryTest {

    private static final String TEST_FILE = "target/test-crash-recovery-queue.db";

    @BeforeEach
    public void setUp() {
        new File(TEST_FILE).delete();
    }

    @AfterEach
    public void tearDown() {
        new File(TEST_FILE).delete();
    }

    @Test
    public void testPersistAndReopen() {
        // 第一轮：写入数据后正常关闭
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 1; i <= 100; i++) {
                assertTrue(queue.offer(i));
            }
            assertEquals(100, queue.size());
        }

        // 第二轮：重新打开，验证数据恢复
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertEquals(100, queue.size());

            for (long i = 1; i <= 100; i++) {
                Long val = queue.poll();
                assertNotNull(val, "第 " + i + " 次 poll 返回 null");
                assertEquals(i, val.longValue(), "第 " + i + " 次 poll 值不正确");
            }

            assertNull(queue.poll());
            assertEquals(0, queue.size());
        }
    }

    @Test
    public void testPartialConsumeAndReopen() {
        // 第一轮：写入 50 个，消费 20 个
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 50; i++) {
                queue.offer(i);
            }
            for (int i = 0; i < 20; i++) {
                queue.poll();
            }
            assertEquals(30, queue.size());
        }

        // 第二轮：重新打开，验证剩余 30 个（值为 20..49）
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertEquals(30, queue.size());

            for (long i = 20; i < 50; i++) {
                Long val = queue.poll();
                assertNotNull(val);
                assertEquals(i, val.longValue());
            }

            assertNull(queue.poll());
        }
    }

    @Test
    public void testMultipleReopenCycles() {
        // 多轮 open → write → close → reopen 循环
        int totalRounds = 5;
        int itemsPerRound = 10;

        for (int round = 0; round < totalRounds; round++) {
            // 打开并追加数据
            try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                    .persistent(TEST_FILE)
                    .linked()
                    .elementCodec(PrimitiveCodecs.LONG)
                    .build()) {

                int expectedSizeBefore = round * itemsPerRound;
                assertEquals(expectedSizeBefore, queue.size(),
                        "第 " + round + " 轮打开时队列大小不正确");

                for (long i = 0; i < itemsPerRound; i++) {
                    queue.offer((long) round * itemsPerRound + i);
                }
                assertEquals(expectedSizeBefore + itemsPerRound, queue.size());
            }
        }

        // 最终验证：消费所有元素，顺序正确
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertEquals(totalRounds * itemsPerRound, queue.size());

            for (long i = 0; i < totalRounds * itemsPerRound; i++) {
                Long val = queue.poll();
                assertNotNull(val);
                assertEquals(i, val.longValue());
            }

            assertNull(queue.poll());
        }
    }

    @Test
    public void testCircularQueuePersistAndReopen() {
        // 环形队列的持久化和恢复
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .circular(100, 8) // 容量100，每个元素8字节（Long）
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 50; i++) {
                assertTrue(queue.offer(i));
            }
            // 消费 20 个
            for (int i = 0; i < 20; i++) {
                queue.poll();
            }
            // 再入队 30 个
            for (long i = 50; i < 80; i++) {
                assertTrue(queue.offer(i));
            }
            // 剩余 60 个（值 20..79）
            assertEquals(60, queue.size());
        }

        // 重新打开，验证环形队列状态恢复
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(TEST_FILE)
                .circular(100, 8)
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertEquals(60, queue.size());

            for (long i = 20; i < 80; i++) {
                Long val = queue.poll();
                assertNotNull(val, "第 " + (i - 20) + " 次 poll 返回 null");
                assertEquals(i, val.longValue());
            }

            assertNull(queue.poll());
        }
    }
}
