package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LinkedQueue Free List 测试
 *
 * 验证：长期 offer/poll 循环不会耗尽 mmap 文件空间，
 * 已 poll 的节点内存可被后续 offer 复用。
 */
public class LinkedQueueFreeListTest {

    private static final int ROUNDS = 200_000;

    @Test
    public void testLongRunningOfferPollDoesNotExhaustSpace() {
        // 仅分配 4MB 的文件空间，验证 200k 轮 offer/poll 不会 OOM
        // 若没有 free list，每轮 offer 都会消耗新空间：4B(size) + 8B(next) + 8B(long) = 20B
        // 200k * 20B = 4MB，恰好等于分配量 → 必然 OOM
        // 有 free list 后，节点被回收复用，空间消耗趋近于常量
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .linked()
                .allocateSize(4L * 1024 * 1024) // 4MB
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (int i = 0; i < ROUNDS; i++) {
                boolean offered = queue.offer((long) i);
                assertTrue(offered, "第 " + i + " 轮 offer 失败（文件空间耗尽）");

                Long value = queue.poll();
                assertNotNull(value, "第 " + i + " 轮 poll 返回 null");
                assertEquals((long) i, value.longValue());
            }

            assertEquals(0, queue.size());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testFreeListWithMixedSizes() {
        // 使用可变大小元素验证 free list 按"足够大"策略匹配节点
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .temporary()
                .linked()
                .allocateSize(8L * 1024 * 1024) // 8MB
                .elementCodec(new StringCodec())
                .build();

        try {
            int rounds = 50_000;
            for (int i = 0; i < rounds; i++) {
                String payload = "value-" + i;
                assertTrue(queue.offer(payload));
                assertEquals(payload, queue.poll());
            }

            assertEquals(0, queue.size());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testFreeListWithBatchedOperations() {
        // 先批量入队，再批量出队，反复多轮，验证 free list 正常工作
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .linked()
                .allocateSize(2L * 1024 * 1024) // 2MB
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            int batchSize = 1000;
            int rounds = 100;

            for (int round = 0; round < rounds; round++) {
                // 批量入队
                for (int i = 0; i < batchSize; i++) {
                    assertTrue(queue.offer((long) i));
                }
                assertEquals(batchSize, queue.size());

                // 批量出队
                for (int i = 0; i < batchSize; i++) {
                    Long val = queue.poll();
                    assertNotNull(val);
                    assertEquals((long) i, val.longValue());
                }
                assertEquals(0, queue.size());
            }
        } finally {
            queue.close();
        }
    }
}
