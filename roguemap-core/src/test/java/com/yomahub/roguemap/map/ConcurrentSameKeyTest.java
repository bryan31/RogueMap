package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.benchmark.TestValueObject;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试多线程同时更新同一个 key 的并发安全性
 * <p>
 * 此测试用于复现和验证修复 issue IDCXRL 中描述的竞态条件问题：
 * - 问题：多个线程同时更新同一个 key 时，会导致 KryoBufferUnderflowException
 * - 原因：put() 方法中 "读取旧地址 → 释放内存 → 更新索引" 不是原子操作
 * - 修复：使用 putAndGetOld() 原子操作接口
 * </p>
 */
public class ConcurrentSameKeyTest {

    /**
     * 测试1: Mmap临时文件模式 - 多线程更新同一个 key（核心测试）
     * <p>
     * 在修复前，此测试应该会出现 KryoBufferUnderflowException 错误
     * 在修复后，此测试应该通过，没有任何错误
     * </p>
     */
    @Test
    public void testMmapTempConcurrentUpdateSameKey() throws Exception {
        System.out.println("\n=== 测试 Mmap临时文件模式：多线程更新同一个 key ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .primitiveIndex()
                .build()) {

            testConcurrentUpdateSameKey(map, "MmapTemp");
        }
    }

    /**
     * 测试2: 分段索引模式 - 多线程更新同一个 key
     */
    @Test
    public void testSegmentedIndexConcurrentUpdateSameKey() throws Exception {
        System.out.println("\n=== 测试分段索引模式：多线程更新同一个 key ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .segmentedIndex(64)
                .build()) {

            testConcurrentUpdateSameKey(map, "SegmentedIndex");
        }
    }

    /**
     * 测试3: 极端压力测试 - 更多线程，更多迭代
     */
    @Test
    public void testExtremeConcurrentUpdateSameKey() throws Exception {
        System.out.println("\n=== 极端压力测试：200 线程 × 50000 次迭代 ===");

        try (RogueMap<Long, TestValueObject> map = RogueMap.<Long, TestValueObject>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                .primitiveIndex()
                .autoExpand(true)
                .build()) {

            final int threads = 200;
            final int iterations = 50000;
            final Long SAME_KEY = 1L;

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threads);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Random random = new Random(threadId);

                        for (int j = 0; j < iterations; j++) {
                            try {
                                TestValueObject value = createTestValue(j, random);
                                map.put(SAME_KEY, value);
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                if (errorCount.get() <= 5) {  // 只打印前 5 个错误
                                    System.err.println("线程 " + threadId + " 错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                }
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

            assertTrue(finishLatch.await(120, TimeUnit.SECONDS), "极端压力测试应在120秒内完成");
            long duration = System.currentTimeMillis() - startTime;

            executor.shutdown();

            System.out.printf("✓ 极端压力测试完成%n");
            System.out.printf("  线程数: %d%n", threads);
            System.out.printf("  总操作数: %d%n", threads * iterations);
            System.out.printf("  成功次数: %d%n", successCount.get());
            System.out.printf("  错误次数: %d%n", errorCount.get());
            System.out.printf("  耗时: %d ms%n", duration);
            System.out.printf("  吞吐量: %.2f ops/s%n", successCount.get() * 1000.0 / duration);

            // 验证：修复后不应该有任何错误
            assertEquals(0, errorCount.get(), "修复后不应该有任何并发错误");
            assertEquals(threads * iterations, successCount.get(), "所有操作都应该成功");
        }
    }

    /**
     * 核心测试逻辑：多个线程同时更新同一个 key
     */
    private void testConcurrentUpdateSameKey(RogueMap<Long, TestValueObject> map, String modeName)
            throws Exception {

        final int threads = 100;
        final int iterations = 10000;
        final Long SAME_KEY = 1L;  // 所有线程竞争同一个 key

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);

                    for (int j = 0; j < iterations; j++) {
                        try {
                            TestValueObject value = createTestValue(j, random);
                            map.put(SAME_KEY, value);  // ← 所有线程竞争同一个 key
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            if (errorCount.get() <= 3) {  // 只打印前 3 个错误
                                System.err.println("线程 " + threadId + " 错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                            }
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

        assertTrue(finishLatch.await(60, TimeUnit.SECONDS), "并发测试应在60秒内完成");
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        // 最终验证 map 中应该只有一个 key
        assertEquals(1, map.size(), "Map 中应该只有一个 key");
        assertNotNull(map.get(SAME_KEY), "应该能获取到最终的值");

        System.out.printf("✓ %s 模式测试完成%n", modeName);
        System.out.printf("  线程数: %d%n", threads);
        System.out.printf("  每线程迭代: %d%n", iterations);
        System.out.printf("  总操作数: %d%n", threads * iterations);
        System.out.printf("  成功次数: %d%n", successCount.get());
        System.out.printf("  错误次数: %d%n", errorCount.get());
        System.out.printf("  最终 Map 大小: %d%n", map.size());
        System.out.printf("  耗时: %d ms%n", duration);
        System.out.printf("  吞吐量: %.2f ops/s%n", successCount.get() * 1000.0 / duration);

        // 验证：修复后不应该有任何错误
        assertEquals(0, errorCount.get(),
            "修复后不应该有任何并发错误（修复前会出现 KryoBufferUnderflowException）");
        assertEquals(threads * iterations, successCount.get(), "所有操作都应该成功");
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
}
