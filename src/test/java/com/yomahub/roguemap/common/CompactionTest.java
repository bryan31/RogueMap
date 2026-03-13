package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.StorageMetrics;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compaction 空间回收测试
 *
 * 验证 compact() 能正确回收碎片空间并保持数据完整性。
 */
public class CompactionTest {

    private static final String MAP_FILE = "target/test-compact-map.db";
    private static final String SET_FILE = "target/test-compact-set.db";
    private static final String LIST_FILE = "target/test-compact-list.db";
    private static final String QUEUE_FILE = "target/test-compact-queue.db";

    @BeforeEach
    public void setUp() {
        deleteFiles();
    }

    @AfterEach
    public void tearDown() {
        deleteFiles();
    }

    private void deleteFiles() {
        for (String f : new String[]{MAP_FILE, SET_FILE, LIST_FILE, QUEUE_FILE,
                MAP_FILE + ".compact", SET_FILE + ".compact",
                LIST_FILE + ".compact", QUEUE_FILE + ".compact"}) {
            File file = new File(f);
            if (file.exists()) file.delete();
        }
    }

    // ========== RogueMap compact 测试 ==========

    @Test
    public void testMapCompactReducesFragmentation() {
        long allocSize = 16L * 1024 * 1024;

        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(allocSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        // 写入 1000 条
        for (long i = 0; i < 1000; i++) {
            map.put(i, i * 10);
        }

        // 删除 900 条，产生大量碎片
        for (long i = 0; i < 900; i++) {
            map.remove(i);
        }

        StorageMetrics beforeMetrics = map.getMetrics();
        assertTrue(beforeMetrics.getFragmentationRatio() > 0.8,
                "compact 前碎片率应>0.8，实际: " + beforeMetrics.getFragmentationRatio());

        // compact
        map = map.compact(allocSize);

        try {
            // 验证数据完整性
            assertEquals(100, map.size());
            for (long i = 900; i < 1000; i++) {
                assertEquals(i * 10, map.get(i).longValue(), "key=" + i);
            }

            // 验证碎片率下降
            StorageMetrics afterMetrics = map.getMetrics();
            assertTrue(afterMetrics.getFragmentationRatio() < 0.1,
                    "compact 后碎片率应<0.1，实际: " + afterMetrics.getFragmentationRatio());
        } finally {
            map.close();
        }
    }

    @Test
    public void testMapCompactPreservesAllData() {
        long allocSize = 16L * 1024 * 1024;

        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(allocSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        // 写入 500 条（不删除）
        for (long i = 0; i < 500; i++) {
            map.put(i, i * 100);
        }

        map = map.compact(allocSize);

        try {
            assertEquals(500, map.size());
            for (long i = 0; i < 500; i++) {
                assertEquals(i * 100, map.get(i).longValue());
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testMapCompactAfterUpdates() {
        long allocSize = 16L * 1024 * 1024;

        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(allocSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        // 写入 200 条
        for (long i = 0; i < 200; i++) {
            map.put(i, i);
        }

        // 更新所有（旧值变碎片）
        for (long i = 0; i < 200; i++) {
            map.put(i, i * 1000);
        }

        map = map.compact(allocSize);

        try {
            assertEquals(200, map.size());
            for (long i = 0; i < 200; i++) {
                assertEquals(i * 1000, map.get(i).longValue(), "key=" + i);
            }
        } finally {
            map.close();
        }
    }

    // ========== RogueSet compact 测试 ==========

    @Test
    public void testSetCompactReducesFragmentation() {
        long allocSize = 16L * 1024 * 1024;

        RogueSet<Long> set = RogueSet.<Long>mmap()
                .persistent(SET_FILE)
                .allocateSize(allocSize)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 1000; i++) {
            set.add(i);
        }

        for (long i = 0; i < 900; i++) {
            set.remove(i);
        }

        StorageMetrics beforeMetrics = set.getMetrics();
        assertTrue(beforeMetrics.getFragmentationRatio() > 0.8);

        set = set.compact(allocSize);

        try {
            assertEquals(100, set.size());
            for (long i = 900; i < 1000; i++) {
                assertTrue(set.contains(i), "应包含: " + i);
            }
            for (long i = 0; i < 900; i++) {
                assertFalse(set.contains(i), "不应包含: " + i);
            }

            StorageMetrics afterMetrics = set.getMetrics();
            assertTrue(afterMetrics.getFragmentationRatio() < 0.1,
                    "compact 后碎片率应<0.1，实际: " + afterMetrics.getFragmentationRatio());
        } finally {
            set.close();
        }
    }

    // ========== RogueList compact 测试 ==========

    @Test
    public void testListCompactReducesFragmentation() {
        long allocSize = 16L * 1024 * 1024;

        RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(allocSize)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 1000; i++) {
            list.addLast(i);
        }

        // 删除尾部 900 条
        for (int i = 0; i < 900; i++) {
            list.removeLast();
        }

        StorageMetrics beforeMetrics = list.getMetrics();
        assertTrue(beforeMetrics.getFragmentationRatio() > 0.8);

        list = list.compact(allocSize);

        try {
            assertEquals(100, list.size());
            for (int i = 0; i < 100; i++) {
                assertEquals(i, list.get(i).longValue(), "index=" + i);
            }

            StorageMetrics afterMetrics = list.getMetrics();
            assertTrue(afterMetrics.getFragmentationRatio() < 0.1,
                    "compact 后碎片率应<0.1，实际: " + afterMetrics.getFragmentationRatio());
        } finally {
            list.close();
        }
    }

    @Test
    public void testListCompactPreservesOrder() {
        long allocSize = 16L * 1024 * 1024;

        RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(allocSize)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 200; i++) {
            list.addLast(i);
        }

        list = list.compact(allocSize);

        try {
            assertEquals(200, list.size());
            assertEquals(0, list.getFirst().longValue());
            assertEquals(199, list.getLast().longValue());
            for (int i = 0; i < 200; i++) {
                assertEquals(i, list.get(i).longValue());
            }
        } finally {
            list.close();
        }
    }

    // ========== RogueQueue compact 测试 ==========

    @Test
    public void testQueueCompactReducesFragmentation() {
        long allocSize = 16L * 1024 * 1024;

        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(QUEUE_FILE)
                .allocateSize(allocSize)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        // offer 1000 条
        for (long i = 0; i < 1000; i++) {
            queue.offer(i);
        }

        // poll 900 条
        for (int i = 0; i < 900; i++) {
            queue.poll();
        }

        // 此时队列有 100 条，但空闲链表中有 ~900 个节点
        assertEquals(100, queue.size());

        queue = queue.compact(allocSize);

        try {
            // 验证数据完整性（队列应保持 FIFO 顺序）
            assertEquals(100, queue.size());
            for (long i = 900; i < 1000; i++) {
                Long value = queue.poll();
                assertNotNull(value, "第 " + i + " 个元素不应为 null");
                assertEquals(i, value.longValue());
            }
            assertTrue(queue.isEmpty());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testQueueCircularCompactUnsupported() {
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .circular(100, 8)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertThrows(UnsupportedOperationException.class,
                    () -> queue.compact(16L * 1024 * 1024));
        } finally {
            queue.close();
        }
    }

    // ========== 错误场景测试 ==========

    @Test
    public void testCompactTemporaryUnsupported() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertThrows(UnsupportedOperationException.class,
                    () -> map.compact(16L * 1024 * 1024));
        }
    }

    @Test
    public void testCompactThenContinueUsing() {
        long allocSize = 16L * 1024 * 1024;

        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(allocSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 100; i++) {
            map.put(i, i);
        }

        map = map.compact(allocSize);

        // compact 后继续使用
        for (long i = 100; i < 200; i++) {
            map.put(i, i * 10);
        }

        try {
            assertEquals(200, map.size());
            assertEquals(50L, map.get(50L).longValue());
            assertEquals(1500L, map.get(150L).longValue());
        } finally {
            map.close();
        }
    }
}
