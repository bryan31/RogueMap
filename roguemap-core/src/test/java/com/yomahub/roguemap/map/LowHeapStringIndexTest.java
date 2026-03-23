package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.StorageMetrics;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LowHeapStringIndexTest {

    private static final String MAP_FILE = "target/test-lowheap-map.db";

    @BeforeEach
    public void setUp() {
        File file = new File(MAP_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    @AfterEach
    public void tearDown() {
        File file = new File(MAP_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    @Test
    public void testTemporaryLowHeapBasicOps() {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .lowHeapIndex()
                .build()) {

            assertEquals(0, map.size());

            map.put("k1", 1L);
            map.put("k2", 2L);
            assertEquals(2, map.size());
            assertEquals(1L, map.get("k1"));
            assertEquals(2L, map.get("k2"));

            map.put("k1", 11L);
            assertEquals(11L, map.get("k1"));

            Long removed = map.remove("k2");
            assertEquals(2L, removed);
            assertFalse(map.containsKey("k2"));
            assertEquals(1, map.size());
        }
    }

    @Test
    public void testPersistentLowHeapRecovery() {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(32L * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .lowHeapIndex()
                .build()) {
            map.put("alpha", 100L);
            map.put("beta", 200L);
            map.checkpoint();
        }

        MmapAllocator allocator = new MmapAllocator(MAP_FILE, 32L * 1024 * 1024, false);
        MmapFileHeader header = allocator.readHeader();
        long indexAddress = allocator.getAddressForOffset(header.getIndexOffset());
        assertEquals(4, header.getIndexType());
        assertEquals(1, UnsafeOps.getInt(indexAddress));
        allocator.close();

        try (RogueMap<String, Long> reopened = RogueMap.<String, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(32L * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertEquals(100L, reopened.get("alpha"));
            assertEquals(200L, reopened.get("beta"));
            assertEquals(2, reopened.size());
        }
    }

    @Test
    public void testLowHeapOpenLegacyFileShouldFail() {
        try (RogueMap<String, Long> oldMap = RogueMap.<String, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(64L * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .segmentedIndex(64)
                .build()) {
            for (int i = 0; i < 1000; i++) {
                oldMap.put("k-" + i, (long) i);
            }
            oldMap.checkpoint();
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                RogueMap.<String, Long>mmap()
                        .persistent(MAP_FILE)
                        .allocateSize(64L * 1024 * 1024)
                        .keyCodec(StringCodec.INSTANCE)
                        .valueCodec(PrimitiveCodecs.LONG)
                        .lowHeapIndex()
                        .build());
        assertTrue(ex.getMessage().contains("不兼容旧索引类型"));
    }

    @Test
    public void testLowHeapMetricsFields() {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .lowHeapIndex()
                .build()) {
            map.put("a", 1L);
            map.put("bb", 2L);
            map.put("ccc", 3L);

            StorageMetrics metrics = map.getMetrics();
            assertTrue(metrics.getIndexHeapBytesEstimate() > 0);
            assertTrue(metrics.getIndexMmapBytes() > 0);
            assertTrue(metrics.getAvgKeyBytes() > 0);
        }
    }

}
