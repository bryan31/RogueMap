package com.yomahub.roguemap;

import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动扩容功能测试
 */
public class AutoExpansionTest {

    private static final String TEST_DIR = System.getProperty("java.io.tmpdir") + "/roguemap_expand_test/";

    @BeforeEach
    public void setUp() {
        File dir = new File(TEST_DIR);
        // 清理可能由上次 JVM 崩溃遗留的文件（避免读到损坏索引）
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.mkdirs();
    }

    @AfterEach
    public void tearDown() {
        File dir = new File(TEST_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    private String testFile(String name) {
        return TEST_DIR + name + ".db";
    }

    // ===== 1. 基本功能：写入超过初始容量的数据 =====

    @Test
    public void testAutoExpandBasic() throws Exception {
        String path = testFile("expand_basic");
        // 初始分配 16KB，每条 Long 值 8 bytes，2000 条 * 8 = 16KB 超过初始净空间（12KB）会触发扩容
        long initialSize = 16 * 1024; // 16KB

        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .persistent(path)
                .allocateSize(initialSize)
                .autoExpand(true)
                .expandFactor(2.0)
                .keyCodec(new StringCodec())
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            // 写入 500 条（每条约 20 bytes key + 8 bytes value = ~28 bytes，共 ~14KB）
            // 加上 header 4KB、以及 StringCodec 的变长编码，总量超过初始 64KB
            int count = 2000;
            for (int i = 0; i < count; i++) {
                map.put("auto-expand-key-" + String.format("%06d", i), (long) i);
            }

            // 验证所有写入均可正确读取
            for (int i = 0; i < count; i++) {
                Long val = map.get("auto-expand-key-" + String.format("%06d", i));
                assertNotNull(val, "key " + i + " should not be null after expand");
                assertEquals((long) i, val.longValue());
            }

            // 文件大小应已超过初始大小
            StorageMetrics metrics = map.getMetrics();
            assertTrue(metrics.getTotalFileSize() > initialSize, "文件大小应已超过初始大小");
        }

        new File(path).delete();
    }

    // ===== 2. 扩容后已有数据地址正确性 =====

    @Test
    public void testExpandPreservesExistingData() throws Exception {
        String path = testFile("expand_preserve");
        long initialSize = 8 * 1024; // 8KB（净空间 4KB，512 条即触发扩容）

        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(path)
                .allocateSize(initialSize)
                .autoExpand(true)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            // 写入前 100 条
            for (long i = 0; i < 100; i++) {
                map.put(i, i * 10);
            }

            // 继续写直到触发扩容
            for (long i = 100; i < 3000; i++) {
                map.put(i, i * 10);
            }

            // 验证前 100 条（扩容前写入的）仍可正确读取
            for (long i = 0; i < 100; i++) {
                Long val = map.get(i);
                assertNotNull(val, "扩容前写入的 key=" + i + " 不应为 null");
                assertEquals(i * 10, val.longValue());
            }

            // 验证扩容后写入的也正确
            for (long i = 100; i < 3000; i++) {
                Long val = map.get(i);
                assertNotNull(val, "扩容后写入的 key=" + i + " 不应为 null");
                assertEquals(i * 10, val.longValue());
            }
        }

        new File(path).delete();
    }

    // ===== 3. 不开启 autoExpand 时空间耗尽应抛出异常 =====

    @Test
    public void testNoExpandThrowsOOM() throws Exception {
        String path = testFile("no_expand");
        try {
            assertThrows(OutOfMemoryError.class, () -> {
                try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                        .persistent(path)
                        .allocateSize(32 * 1024)  // 32KB，不开启扩容
                        .keyCodec(PrimitiveCodecs.LONG)
                        .valueCodec(PrimitiveCodecs.LONG)
                        .build()) {
                    for (long i = 0; i < 10000; i++) {
                        map.put(i, i);
                    }
                }
            });
        } finally {
            new File(path).delete();
        }
    }

    // ===== 4. 多次扩容（小 expandFactor）=====

    @Test
    public void testMultipleExpansions() throws Exception {
        String path = testFile("multi_expand");
        long initialSize = 16 * 1024; // 16KB，很小

        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(path)
                .allocateSize(initialSize)
                .autoExpand(true)
                .expandFactor(1.5)  // 较小倍数，触发多次扩容
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            int count = 2000;
            for (long i = 0; i < count; i++) {
                map.put(i, i);
            }

            assertEquals(count, map.size());
            for (long i = 0; i < count; i++) {
                assertEquals(i, map.get(i).longValue());
            }

            StorageMetrics metrics = map.getMetrics();
            // 经过多次扩容，总大小应远超初始值
            assertTrue(metrics.getTotalFileSize() > 4 * initialSize, "多次扩容后总大小应远超 16KB");
        }

        new File(path).delete();
    }

    // ===== 5. 并发写入触发扩容（数据无丢失）=====

    @Test
    public void testConcurrentWriteTriggerExpand() throws Exception {
        String path = testFile("concurrent_expand");
        int threadCount = 10;
        int perThread = 100;

        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(path)
                .allocateSize(8 * 1024) // 8KB，净空间 4KB，512 条后触发扩容
                .autoExpand(true)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

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
                            long key = (long) tid * perThread + i;
                            map.put(key, key * 2);
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await();
            executor.shutdown();

            assertEquals(0, errorCount.get(), "并发写入应无错误");
            assertEquals(threadCount * perThread, map.size(), "所有写入均应可读");

            for (long key = 0; key < threadCount * perThread; key++) {
                Long val = map.get(key);
                assertNotNull(val, "key=" + key + " 不应为 null");
                assertEquals(key * 2, val.longValue());
            }
        }

        new File(path).delete();
    }

    // ===== 6. getMetrics() 反映扩容后的新大小 =====

    @Test
    public void testMetricsReflectsExpandedSize() throws Exception {
        String path = testFile("metrics_expand");

        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(path)
                .allocateSize(8 * 1024) // 8KB，净空间 4KB，1000 条肯定触发扩容
                .autoExpand(true)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            long sizeBefore = map.getMetrics().getTotalFileSize();

            for (long i = 0; i < 1000; i++) {
                map.put(i, i);
            }

            long sizeAfter = map.getMetrics().getTotalFileSize();
            assertTrue(sizeAfter > sizeBefore, "扩容后 totalFileSize 应增大");
        }

        new File(path).delete();
    }

    // ===== 7. 临时文件模式也支持 autoExpand =====

    @Test
    public void testTemporaryModeAutoExpand() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .allocateSize(16 * 1024)
                .autoExpand(true)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 1000; i++) {
                map.put(i, i);
            }

            assertEquals(1000, map.size());
            for (long i = 0; i < 1000; i++) {
                assertEquals(i, map.get(i).longValue());
            }
        }
    }
}
