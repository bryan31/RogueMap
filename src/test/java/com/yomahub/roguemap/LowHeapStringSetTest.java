package com.yomahub.roguemap;

import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.serialization.StringCodec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LowHeapStringSetTest {

    private static final String SET_FILE = "target/test-lowheap-set.db";

    @BeforeEach
    public void setUp() {
        File file = new File(SET_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    @AfterEach
    public void tearDown() {
        File file = new File(SET_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    @Test
    public void testTemporaryLowHeapSetBasicOps() {
        try (RogueSet<String> set = RogueSet.<String>mmap()
                .temporary()
                .elementCodec(StringCodec.INSTANCE)
                .lowHeapIndex()
                .build()) {

            assertEquals(0, set.size());
            assertTrue(set.add("a"));
            assertTrue(set.add("b"));
            assertFalse(set.add("a"));

            assertTrue(set.contains("a"));
            assertTrue(set.contains("b"));
            assertFalse(set.contains("c"));

            assertTrue(set.remove("a"));
            assertFalse(set.contains("a"));
            assertEquals(1, set.size());
        }
    }

    @Test
    public void testPersistentLowHeapSetRecovery() {
        try (RogueSet<String> set = RogueSet.<String>mmap()
                .persistent(SET_FILE)
                .allocateSize(32L * 1024 * 1024)
                .elementCodec(StringCodec.INSTANCE)
                .lowHeapIndex()
                .build()) {
            set.add("alpha");
            set.add("beta");
            set.checkpoint();
        }

        MmapAllocator allocator = new MmapAllocator(SET_FILE, 32L * 1024 * 1024, false);
        MmapFileHeader header = allocator.readHeader();
        assertEquals(4, header.getIndexType());
        allocator.close();

        try (RogueSet<String> reopened = RogueSet.<String>mmap()
                .persistent(SET_FILE)
                .allocateSize(32L * 1024 * 1024)
                .elementCodec(StringCodec.INSTANCE)
                .build()) {
            assertEquals(2, reopened.size());
            assertTrue(reopened.contains("alpha"));
            assertTrue(reopened.contains("beta"));
        }
    }

    @Test
    public void testLowHeapSetOpenLegacyFileShouldFail() {
        try (RogueSet<String> oldSet = RogueSet.<String>mmap()
                .persistent(SET_FILE)
                .allocateSize(64L * 1024 * 1024)
                .elementCodec(StringCodec.INSTANCE)
                .build()) {
            for (int i = 0; i < 1000; i++) {
                oldSet.add("k-" + i);
            }
            oldSet.checkpoint();
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                RogueSet.<String>mmap()
                        .persistent(SET_FILE)
                        .allocateSize(64L * 1024 * 1024)
                        .elementCodec(StringCodec.INSTANCE)
                        .lowHeapIndex()
                        .build());
        assertTrue(ex.getMessage().contains("不兼容旧索引类型"));
    }

    @Test
    public void testLowHeapSetMetricsFields() {
        try (RogueSet<String> set = RogueSet.<String>mmap()
                .temporary()
                .elementCodec(StringCodec.INSTANCE)
                .lowHeapIndex()
                .build()) {
            set.add("a");
            set.add("bb");
            set.add("ccc");

            StorageMetrics metrics = set.getMetrics();
            assertTrue(metrics.getIndexHeapBytesEstimate() > 0);
            assertTrue(metrics.getIndexMmapBytes() > 0);
            assertTrue(metrics.getAvgKeyBytes() > 0);
        }
    }
}
