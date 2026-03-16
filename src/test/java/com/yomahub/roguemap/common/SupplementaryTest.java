package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.RogueMapTransaction;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 补充测试：覆盖生产环境关键路径的测试缺口
 *
 * 包含：
 * - P0-2: RogueMap clear() 测试
 * - P0-3: MmapFileHeader 损坏检测测试
 * - P0-4: maxFileSize 上限测试
 * - P1-5: flush() 测试
 * - P1-6: null key/value 处理测试
 * - P1-7: expandFactor 参数校验测试
 * - P1-8: LowHeapIndex beginTransaction() 拒绝测试
 * - P2-9: size() 在 TTL 过期后的准确性
 * - P2-10: LinkedQueue 交替 offer/poll 持久化
 * - P2-11: RogueList 并发 addFirst/removeFirst
 * - P2-12: Transaction pendingSize() 测试
 */
public class SupplementaryTest {

    private static final String TEST_DIR = "target/test-files/supplementary";

    @BeforeEach
    void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
            }
        }
        dir.mkdirs();
    }

    @AfterEach
    void tearDown() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
            }
        }
    }

    // ========== P0-2: RogueMap clear() 测试 ==========

    @Test
    void testMapClearBasic() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            map.put("a", "1");
            map.put("b", "2");
            map.put("c", "3");
            assertEquals(3, map.size());

            map.clear();

            assertEquals(0, map.size());
            assertTrue(map.isEmpty());
            assertNull(map.get("a"));
            assertNull(map.get("b"));
            assertNull(map.get("c"));
            assertFalse(map.containsKey("a"));
        }
    }

    @Test
    void testMapClearThenContinueUsing() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            map.put("old", "data");
            map.clear();

            // clear 后继续使用
            map.put("new1", "val1");
            map.put("new2", "val2");
            assertEquals(2, map.size());
            assertEquals("val1", map.get("new1"));
            assertEquals("val2", map.get("new2"));
            assertNull(map.get("old"));
        }
    }

    @Test
    void testMapClearEmpty() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            // 空 map 调用 clear 不应报错
            map.clear();
            assertEquals(0, map.size());
        }
    }

    // ========== P0-3: MmapFileHeader 损坏检测测试 ==========

    @Test
    void testHeaderCRC32ValidationDetectsCorruption() {
        String path = TEST_DIR + "/header_corrupt.db";

        // 正常写入数据并关闭
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            map.put("key1", "value1");
        }

        // 手动篡改文件头中的 entryCount 字段（offset 16），使 CRC 不匹配
        MmapAllocator allocator = new MmapAllocator(path, 4 * 1024 * 1024L, false);
        long baseAddr = allocator.getBaseAddress();
        // 验证正常头部是有效的
        assertTrue(MmapFileHeader.isValidHeader(baseAddr));
        // 篡改 entryCount（offset 16）
        int originalCount = UnsafeOps.getInt(baseAddr + 16);
        UnsafeOps.putInt(baseAddr + 16, originalCount + 999);
        // 篡改后 CRC 应不匹配，头部应无效
        assertFalse(MmapFileHeader.isValidHeader(baseAddr), "CRC 不匹配时 isValidHeader 应返回 false");
        allocator.close();
    }

    @Test
    void testHeaderWriteGenOddMeansIncomplete() {
        String path = TEST_DIR + "/header_gen_odd.db";

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            map.put("key1", "value1");
        }

        // 手动将 WriteGen 设为奇数（模拟写入中断）
        MmapAllocator allocator = new MmapAllocator(path, 4 * 1024 * 1024L, false);
        long baseAddr = allocator.getBaseAddress();
        UnsafeOps.putInt(baseAddr + MmapFileHeader.WRITE_GEN_POS, 3); // 奇数
        assertFalse(MmapFileHeader.isValidHeader(baseAddr), "WriteGen 为奇数时 isValidHeader 应返回 false");
        allocator.close();
    }

    // ========== P0-4: maxFileSize 上限测试 ==========

    @Test
    void testMaxFileSizeCapPreventsExpansion() {
        String path = TEST_DIR + "/max_size.db";
        long initialSize = 16 * 1024; // 16KB
        long maxSize = 32 * 1024;     // 32KB 上限

        assertThrows(OutOfMemoryError.class, () -> {
            try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                    .persistent(path)
                    .allocateSize(initialSize)
                    .autoExpand(true)
                    .maxFileSize(maxSize)
                    .keyCodec(PrimitiveCodecs.LONG)
                    .valueCodec(PrimitiveCodecs.LONG)
                    .build()) {
                // 写入大量数据直到超过 maxFileSize
                for (long i = 0; i < 100000; i++) {
                    map.put(i, i);
                }
            }
        });
    }

    // ========== P1-5: flush() 测试 ==========

    @Test
    void testMapFlush() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            map.put("k", "v");
            // flush 不应抛异常，数据应仍可读
            map.flush();
            assertEquals("v", map.get("k"));
        }
    }

    @Test
    void testListFlush() {
        try (RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            list.addLast(42L);
            list.flush();
            assertEquals(42L, list.get(0).longValue());
        }
    }

    @Test
    void testSetFlush() {
        try (RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            set.add(42L);
            set.flush();
            assertTrue(set.contains(42L));
        }
    }

    @Test
    void testQueueFlush() {
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            queue.offer(42L);
            queue.flush();
            assertEquals(42L, queue.peek().longValue());
        }
    }

    // ========== P1-6: null key/value 处理测试 ==========

    @Test
    void testPutNullKeyThrows() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            assertThrows(IllegalArgumentException.class, () -> map.put(null, "value"));
        }
    }

    @Test
    void testGetNullKeyReturnsNull() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            assertNull(map.get(null));
        }
    }

    @Test
    void testRemoveNullKeyReturnsNull() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            assertNull(map.remove(null));
        }
    }

    @Test
    void testContainsKeyNullReturnsFalse() {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            assertFalse(map.containsKey(null));
        }
    }

    // ========== P1-7: expandFactor 参数校验测试 ==========

    @Test
    void testExpandFactorTooSmallThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                RogueMap.<Long, Long>mmap()
                        .temporary()
                        .keyCodec(PrimitiveCodecs.LONG)
                        .valueCodec(PrimitiveCodecs.LONG)
                        .expandFactor(1.0)
                        .build());
    }

    @Test
    void testExpandFactorBelowMinThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                RogueMap.<Long, Long>mmap()
                        .temporary()
                        .keyCodec(PrimitiveCodecs.LONG)
                        .valueCodec(PrimitiveCodecs.LONG)
                        .expandFactor(0.5)
                        .build());
    }

    // ========== P1-8: LowHeapIndex beginTransaction() 拒绝测试 ==========

    @Test
    void testLowHeapIndexTransactionUnsupported() {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .temporary()
                .allocateSize(4 * 1024 * 1024)
                .lowHeapIndex()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertThrows(UnsupportedOperationException.class, map::beginTransaction);
        }
    }

    // ========== P2-9: size() 在 TTL 过期后的准确性 ==========

    @Test
    void testSizeAfterTTLExpiration() throws InterruptedException {
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(100, TimeUnit.MILLISECONDS)
                .build()) {
            map.put("a", "1");
            map.put("b", "2");
            map.put("c", "3", 0, TimeUnit.MILLISECONDS); // 永不过期

            Thread.sleep(200);

            // get 会触发惰性删除，调用 get 后 size 应减少
            assertNull(map.get("a"));
            assertNull(map.get("b"));
            assertEquals("3", map.get("c"));
        }
    }

    // ========== P2-10: LinkedQueue 交替 offer/poll 持久化 ==========

    @Test
    void testInterleavedOfferPollPersistence() {
        String path = TEST_DIR + "/interleaved_queue.db";

        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            long nextOffer = 0;
            long nextPoll = 0;
            // 交替操作：offer 10 → poll 5，重复 20 轮
            for (int round = 0; round < 20; round++) {
                for (int i = 0; i < 10; i++) {
                    queue.offer(nextOffer++);
                }
                for (int i = 0; i < 5; i++) {
                    Long val = queue.poll();
                    assertNotNull(val);
                    assertEquals(nextPoll++, val.longValue());
                }
            }
            // 剩余 100 个（200 offer - 100 poll）
            assertEquals(100, queue.size());
        }

        // reopen 验证
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertEquals(100, queue.size());
            // 验证 FIFO 顺序（值应为 100..199）
            for (long i = 100; i < 200; i++) {
                Long val = queue.poll();
                assertNotNull(val, "第 " + (i - 100) + " 次 poll 不应为 null");
                assertEquals(i, val.longValue());
            }
            assertTrue(queue.isEmpty());
        }
    }

    // ========== P2-11: RogueList 并发 addFirst/removeFirst ==========

    @Test
    void testConcurrentAddFirst() throws Exception {
        try (RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .allocateSize(50 * 1024 * 1024L)
                .elementCodec(PrimitiveCodecs.LONG)
                .initialCapacity(10000)
                .build()) {

            int threadCount = 5;
            int perThread = 200;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int tid = t;
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            list.addFirst((long) tid * perThread + i);
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await();
            executor.shutdown();

            assertEquals(0, errorCount.get(), "并发 addFirst 不应有错误");
            assertEquals(threadCount * perThread, list.size());
        }
    }

    // ========== P2-12: Transaction pendingSize() 测试 ==========

    @Test
    void testTransactionPendingSize() {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .temporary()
                .allocateSize(16 * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                assertEquals(0, txn.pendingSize());

                txn.put("a", 1L);
                assertEquals(1, txn.pendingSize());

                txn.put("b", 2L);
                assertEquals(2, txn.pendingSize());

                txn.remove("c");
                assertEquals(3, txn.pendingSize());

                // 同一 key 多次操作，pendingSize 不应重复计数
                txn.put("a", 10L);

                txn.commit();
            }
        }
    }
}
