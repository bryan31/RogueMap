package com.yomahub.roguemap.compare;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueMap 并发安全性测试
 *
 * 测试内容：
 * 1. 多线程并发读写测试
 * 2. 读写分离并发测试
 * 3. 高并发压力测试
 * 4. 数据一致性验证
 */
public class ConcurrentSafetyTest {

    private static final String TEST_DIR = "test_data/concurrent_test";
    private static final int THREAD_COUNT = 100;          // 并发线程数
    private static final int OPERATIONS_PER_THREAD = 10000; // 每个线程的操作数

    @BeforeEach
    public void setUp() {
        new File(TEST_DIR).mkdirs();
    }

    @AfterEach
    public void tearDown() {
        cleanupTestFiles();
    }

    /**
     * 测试1: Mmap临时文件模式 - 并发读写测试
     */
    @Test
    public void testMmapTemporaryConcurrentReadWrite() throws Exception {
        System.out.println("\n=== 测试 Mmap 临时文件模式并发读写 ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .primitiveIndex()
                .build()) {

            testConcurrentReadWrite(map, "Mmap临时文件");
        }
    }

    /**
     * 测试2: Mmap持久化模式 - 并发读写测试
     */
    @Test
    public void testMmapPersistentConcurrentReadWrite() throws Exception {
        System.out.println("\n=== 测试 Mmap 持久化模式并发读写 ===");

        String filePath = TEST_DIR + "/concurrent_test.map";
        new File(filePath).delete();

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .persistent(filePath)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .primitiveIndex()
                .build()) {

            testConcurrentReadWrite(map, "Mmap持久化");
        }
    }

    /**
     * 测试3: 读写分离并发测试（多读少写场景）
     */
    @Test
    public void testConcurrentReadHeavy() throws Exception {
        System.out.println("\n=== 测试读多写少并发场景 ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .primitiveIndex()
                .build()) {

            // 先预填充一些数据（key从1开始，因为LongPrimitiveIndex不支持0）
            System.out.println("预填充 10000 条数据...");
            for (int i = 1; i <= 10000; i++) {
                map.put((long) i, createTestValue(i, new Random(i)));
            }

            // 80% 读操作，20% 写操作
            testReadHeavyConcurrency(map);
        }
    }

    /**
     * 测试4: 不同key的并发更新测试（避免同一key竞态条件）
     *
     * 注意：当前版本的 RogueMap 在多个线程同时更新同一个 key 时存在竞态条件，
     * 会导致 Buffer Underflow 错误。这是因为 put 方法中读取旧值和释放内存不是原子操作。
     * 在实际使用中，应该在应用层面避免多个线程同时更新同一个 key，
     * 或者使用外部同步机制（如分段锁）。
     */
    @Test
    public void testConcurrentUpdateDifferentKeys() throws Exception {
        System.out.println("\n=== 测试不同Key的并发更新 ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .segmentedIndex(64)
                .build()) {

            final int updateCount = 10000;
            final int threads = 100;

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threads);
            AtomicInteger successCount = new AtomicInteger(0);

            // 每个线程更新自己专属的key，避免竞争
            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // 等待统一开始信号

                        Random random = new Random(threadId);
                        // 每个线程使用不同的key范围（从1开始，避免key=0）
                        long baseKey = threadId * updateCount + 1;

                        for (int j = 0; j < updateCount; j++) {
                            long key = baseKey + j;
                            TestValueObject value = createTestValue(j, random);
                            map.put(key, value);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            long startTime = System.currentTimeMillis();
            startLatch.countDown(); // 发出开始信号

            assertTrue(finishLatch.await(30, TimeUnit.SECONDS), "并发更新操作应在30秒内完成");
            long duration = System.currentTimeMillis() - startTime;

            executor.shutdown();

            // 验证
            assertEquals(threads * updateCount, successCount.get(), "所有更新操作都应该成功");
            assertEquals(threads * updateCount, map.size(), "Map大小应该正确");

            System.out.printf("✓ 完成 %d 个线程并发更新 %d 个不同Key，耗时 %d ms%n",
                threads, successCount.get(), duration);
            System.out.printf("  吞吐量: %.2f ops/s%n", successCount.get() * 1000.0 / duration);
        }
    }

    /**
     * 测试5: 并发删除测试
     */
    @Test
    public void testConcurrentDelete() throws Exception {
        System.out.println("\n=== 测试并发删除操作 ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .primitiveIndex()
                .build()) {

            final int totalKeys = 10000;
            final int threads = 10;

            // 预填充数据（key从1开始，因为LongPrimitiveIndex不支持0）
            System.out.printf("预填充 %d 条数据...%n", totalKeys);
            for (int i = 1; i <= totalKeys; i++) {
                map.put((long) i, createTestValue(i, new Random(i)));
            }
            assertEquals(totalKeys, map.size());

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threads);
            AtomicInteger deleteCount = new AtomicInteger(0);

            // 每个线程负责删除一部分数据（key从1开始）
            int keysPerThread = totalKeys / threads;
            for (int i = 0; i < threads; i++) {
                final int start = i * keysPerThread + 1;
                final int end = (i == threads - 1) ? (totalKeys + 1) : ((i + 1) * keysPerThread + 1);

                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (int j = start; j < end; j++) {
                            if (map.remove((long) j) != null) {
                                deleteCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            long startTime = System.currentTimeMillis();
            startLatch.countDown();

            assertTrue(finishLatch.await(30, TimeUnit.SECONDS), "并发删除操作应在30秒内完成");
            long duration = System.currentTimeMillis() - startTime;

            executor.shutdown();

            // 验证
            assertEquals(0, map.size(), "所有数据应该被删除");
            assertEquals(totalKeys, deleteCount.get(), "删除计数应该匹配");

            System.out.printf("✓ 完成 %d 个线程并发删除 %d 条数据，耗时 %d ms%n",
                threads, totalKeys, duration);
            System.out.printf("  删除吞吐量: %.2f ops/s%n", totalKeys * 1000.0 / duration);
        }
    }

    /**
     * 核心并发读写测试逻辑
     */
    private void testConcurrentReadWrite(RogueMap<Long, TestValueObject> map, String modeName)
            throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(THREAD_COUNT);

        AtomicLong writeCount = new AtomicLong(0);
        AtomicLong readCount = new AtomicLong(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // 启动多个线程并发读写
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待统一开始

                    Random random = new Random(threadId);

                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        // key从1开始，避免key=0
                        long key = threadId * OPERATIONS_PER_THREAD + j + 1;

                        try {
                            // 50% 写操作
                            if (j % 2 == 0) {
                                TestValueObject value = createTestValue(j, random);
                                map.put(key, value);
                                writeCount.incrementAndGet();

                                // 立即读取验证
                                TestValueObject retrieved = map.get(key);
                                assertNotNull(retrieved, "刚写入的数据应该能读取到");
                                assertEquals(j, retrieved.getId(), "数据内容应该一致");
                            }
                            // 50% 读操作
                            else {
                                // 读取之前写入的数据
                                long readKey = threadId * OPERATIONS_PER_THREAD + (j - 1) + 1;
                                TestValueObject value = map.get(readKey);
                                if (value != null) {
                                    readCount.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            System.err.printf("线程 %d 操作出错: %s%n", threadId, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown(); // 发出开始信号

        // 等待所有线程完成
        assertTrue(finishLatch.await(60, TimeUnit.SECONDS),
            "并发测试应在60秒内完成");

        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // 验证结果
        assertEquals(0, errorCount.get(), "不应该有错误发生");

        long expectedWrites = (THREAD_COUNT * OPERATIONS_PER_THREAD) / 2;
        assertEquals(expectedWrites, writeCount.get(), "写入次数应该正确");

        int finalSize = map.size();
        assertEquals(expectedWrites, finalSize, "Map中的数据量应该与写入次数一致");

        // 输出测试结果
        System.out.printf("✓ %s 模式并发测试通过%n", modeName);
        System.out.printf("  线程数: %d%n", THREAD_COUNT);
        System.out.printf("  每线程操作数: %d%n", OPERATIONS_PER_THREAD);
        System.out.printf("  总操作数: %d%n", THREAD_COUNT * OPERATIONS_PER_THREAD);
        System.out.printf("  写入次数: %d%n", writeCount.get());
        System.out.printf("  读取次数: %d%n", readCount.get());
        System.out.printf("  最终数据量: %d%n", finalSize);
        System.out.printf("  耗时: %d ms%n", duration);
        System.out.printf("  总吞吐量: %.2f ops/s%n",
            (THREAD_COUNT * OPERATIONS_PER_THREAD) * 1000.0 / duration);
        System.out.printf("  写吞吐量: %.2f ops/s%n", writeCount.get() * 1000.0 / duration);
        System.out.printf("  读吞吐量: %.2f ops/s%n", readCount.get() * 1000.0 / duration);
    }

    /**
     * 读多写少并发测试
     */
    private void testReadHeavyConcurrency(RogueMap<Long, TestValueObject> map) throws Exception {
        final int readerThreads = 8;
        final int writerThreads = 2;
        final int totalThreads = readerThreads + writerThreads;
        final int readsPerThread = 50000;
        final int writesPerThread = 5000;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalThreads);

        AtomicLong totalReads = new AtomicLong(0);
        AtomicLong totalWrites = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        // 启动读线程
        for (int i = 0; i < readerThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);

                    for (int j = 0; j < readsPerThread; j++) {
                        // key范围从1到10000
                        long key = random.nextInt(10000) + 1;
                        TestValueObject value = map.get(key);
                        if (value != null) {
                            totalReads.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 启动写线程
        for (int i = 0; i < writerThreads; i++) {
            final int threadId = i + readerThreads;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);

                    for (int j = 0; j < writesPerThread; j++) {
                        // key范围从1到10000
                        long key = random.nextInt(10000) + 1;
                        TestValueObject value = createTestValue(j, random);
                        map.put(key, value);
                        totalWrites.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        assertTrue(finishLatch.await(60, TimeUnit.SECONDS),
            "读多写少并发测试应在60秒内完成");

        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // 验证
        assertEquals(0, errors.get(), "不应该有错误");

        System.out.println("✓ 读多写少并发测试通过");
        System.out.printf("  读线程数: %d, 写线程数: %d%n", readerThreads, writerThreads);
        System.out.printf("  总读取: %d, 总写入: %d%n", totalReads.get(), totalWrites.get());
        System.out.printf("  耗时: %d ms%n", duration);
        System.out.printf("  读吞吐量: %.2f ops/s%n", totalReads.get() * 1000.0 / duration);
        System.out.printf("  写吞吐量: %.2f ops/s%n", totalWrites.get() * 1000.0 / duration);
    }

    /**
     * 创建测试值对象
     */
    private TestValueObject createTestValue(int id, Random random) {
        return new TestValueObject(
                id,
                System.currentTimeMillis() + id,
                99.99 + (random.nextDouble() * 100),
                random.nextBoolean(),
                "Product_" + id,
                "Description for product " + id,
                (byte) (random.nextInt(5)),
                (short) (random.nextInt(1000)),
                random.nextFloat() * 5,
                (char) ('A' + random.nextInt(26))
        );
    }

    /**
     * 清理测试文件
     */
    private void cleanupTestFiles() {
        try {
            File testDir = new File(TEST_DIR);
            if (testDir.exists()) {
                File[] files = testDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
                testDir.delete();
            }
        } catch (Exception e) {
            System.err.println("清理测试文件时出错: " + e.getMessage());
        }
    }
}
