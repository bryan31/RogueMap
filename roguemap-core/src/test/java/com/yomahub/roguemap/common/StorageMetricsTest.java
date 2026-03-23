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
 * 运维指标 StorageMetrics 测试
 *
 * 验证 getMetrics() 返回的各项指标准确性。
 */
public class StorageMetricsTest {

    private static final String MAP_FILE = "target/test-metrics-map.db";
    private static final String SET_FILE = "target/test-metrics-set.db";
    private static final String LIST_FILE = "target/test-metrics-list.db";
    private static final String QUEUE_FILE = "target/test-metrics-queue.db";

    @BeforeEach
    public void setUp() {
        deleteFiles();
    }

    @AfterEach
    public void tearDown() {
        deleteFiles();
    }

    private void deleteFiles() {
        for (String f : new String[]{MAP_FILE, SET_FILE, LIST_FILE, QUEUE_FILE}) {
            File file = new File(f);
            if (file.exists()) file.delete();
        }
    }

    // ========== RogueMap 指标测试 ==========

    @Test
    public void testMapMetricsAfterPutOnly() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 1000; i++) {
                map.put(i, i * 10);
            }

            StorageMetrics metrics = map.getMetrics();
            assertEquals(1000, metrics.getEntryCount());
            assertTrue(metrics.getLiveDataBytes() > 0);
            // 没有删除操作，碎片率应该接近 0
            assertTrue(metrics.getFragmentationRatio() < 0.01,
                    "碎片率应接近0，实际: " + metrics.getFragmentationRatio());
            assertFalse(metrics.shouldCompact(0.5));
        }
    }

    @Test
    public void testMapMetricsAfterRemove() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 1000; i++) {
                map.put(i, i * 10);
            }

            // 删除 900 条
            for (long i = 0; i < 900; i++) {
                map.remove(i);
            }

            StorageMetrics metrics = map.getMetrics();
            assertEquals(100, metrics.getEntryCount());
            // 删除 90% 数据后，碎片率应该很高
            assertTrue(metrics.getFragmentationRatio() > 0.8,
                    "碎片率应>0.8，实际: " + metrics.getFragmentationRatio());
            assertTrue(metrics.shouldCompact(0.5));
            assertTrue(metrics.getDeadBytes() > 0);
        }
    }

    @Test
    public void testMapMetricsAfterUpdate() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 100; i++) {
                map.put(i, i);
            }

            // 更新所有条目（旧值变为死数据）
            for (long i = 0; i < 100; i++) {
                map.put(i, i * 100);
            }

            StorageMetrics metrics = map.getMetrics();
            assertEquals(100, metrics.getEntryCount());
            // 更新产生碎片
            assertTrue(metrics.getFragmentationRatio() > 0.4,
                    "更新后碎片率应>0.4，实际: " + metrics.getFragmentationRatio());
        }
    }

    // ========== RogueSet 指标测试 ==========

    @Test
    public void testSetMetrics() {
        try (RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 500; i++) {
                set.add(i);
            }

            StorageMetrics metrics = set.getMetrics();
            assertEquals(500, metrics.getEntryCount());
            assertTrue(metrics.getLiveDataBytes() > 0);
            assertTrue(metrics.getFragmentationRatio() < 0.01);

            // 删除一半
            for (long i = 0; i < 250; i++) {
                set.remove(i);
            }

            metrics = set.getMetrics();
            assertEquals(250, metrics.getEntryCount());
            assertTrue(metrics.getFragmentationRatio() > 0.4,
                    "删除一半后碎片率应>0.4，实际: " + metrics.getFragmentationRatio());
        }
    }

    // ========== RogueList 指标测试 ==========

    @Test
    public void testListMetrics() {
        try (RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 500; i++) {
                list.addLast(i);
            }

            StorageMetrics metrics = list.getMetrics();
            assertEquals(500, metrics.getEntryCount());
            assertTrue(metrics.getLiveDataBytes() > 0);
            assertTrue(metrics.getFragmentationRatio() < 0.01);

            // 删除一半
            for (int i = 0; i < 250; i++) {
                list.removeLast();
            }

            metrics = list.getMetrics();
            assertEquals(250, metrics.getEntryCount());
            assertTrue(metrics.getFragmentationRatio() > 0.4,
                    "删除一半后碎片率应>0.4，实际: " + metrics.getFragmentationRatio());
        }
    }

    // ========== RogueQueue 指标测试 ==========

    @Test
    public void testQueueMetrics() {
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 100; i++) {
                queue.offer(i);
            }

            StorageMetrics metrics = queue.getMetrics();
            assertEquals(100, metrics.getEntryCount());
            assertTrue(metrics.getLiveDataBytes() > 0);
        }
    }

    // ========== 临时文件标识测试 ==========

    @Test
    public void testTemporaryFlag() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            StorageMetrics metrics = map.getMetrics();
            assertTrue(metrics.isTemporary());
        }
    }

    @Test
    public void testPersistentFlag() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            StorageMetrics metrics = map.getMetrics();
            assertFalse(metrics.isTemporary());
            assertNotNull(metrics.getFilePath());
        }
    }

    @Test
    public void testMetricsToString() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            map.put(1L, 1L);
            StorageMetrics metrics = map.getMetrics();
            String str = metrics.toString();
            assertTrue(str.contains("StorageMetrics"));
            assertTrue(str.contains("entryCount=1"));
        }
    }
}
