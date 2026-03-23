package com.yomahub.roguemap.benchmark;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;

/**
 * MMAP 持久化性能测试
 *
 * 测试 MMAP 模式下的各种性能指标,包括:
 * - 持久化写入性能
 * - 持久化读取性能
 * - 恢复性能
 * - 多次会话性能
 * - 大数据量性能
 * - 文件 I/O 性能
 */
public class MapPerformanceTest {

    private static final String TEST_FILE = "target/test-mmap-performance.db";

    @AfterEach
    public void tearDown() {
        deleteTestFile();
    }

    private void deleteTestFile() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * 测试首次写入性能
     */
    @Test
    public void testInitialWritePerformance() {
        deleteTestFile();
        int count = 500_000;
        System.out.println("\n========== MMAP 首次写入性能测试 ==========");
        System.out.println("数据量: " + count);

        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        try {
            long startTime = System.currentTimeMillis();

            for (long i = 1; i <= count; i++) {
                map.put(i, i * 100);
            }

            long insertDuration = System.currentTimeMillis() - startTime;
            System.out.println("写入耗时: " + insertDuration + " ms");
            System.out.println("写入吞吐量: " + (count * 1000L / insertDuration) + " ops/sec");

            // 关闭并持久化
            startTime = System.currentTimeMillis();
            map.close();
            long closeDuration = System.currentTimeMillis() - startTime;

            System.out.println("持久化耗时(close): " + closeDuration + " ms");
            System.out.println("总耗时: " + (insertDuration + closeDuration) + " ms");
        } catch (Exception e) {
            if (map != null) {
                map.close();
            }
            throw e;
        }
    }

    /**
     * 测试恢复性能
     */
    @Test
    public void testRecoveryPerformance() {
        deleteTestFile();
        int count = 500_000;
        System.out.println("\n========== MMAP 恢复性能测试 ==========");
        System.out.println("数据量: " + count);

        // 第一阶段：写入数据
        RogueMap<Long, Long> map1 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        for (long i = 1; i <= count; i++) {
            map1.put(i, i * 100);
        }
        map1.close();

        System.out.println("数据已写入,开始测试恢复...");

        // 第二阶段：恢复并读取
        long startTime = System.currentTimeMillis();

        RogueMap<Long, Long> map2 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        long recoveryDuration = System.currentTimeMillis() - startTime;
        System.out.println("恢复耗时: " + recoveryDuration + " ms");
        System.out.println("恢复数据量: " + map2.size() + " 条");

        // 验证数据
        startTime = System.currentTimeMillis();
        int verifyCount = 0;
        for (long i = 1; i <= count; i++) {
            Long value = map2.get(i);
            if (value != null && value == i * 100) {
                verifyCount++;
            }
        }
        long verifyDuration = System.currentTimeMillis() - startTime;

        System.out.println("验证耗时: " + verifyDuration + " ms");
        System.out.println("验证成功: " + verifyCount + "/" + count);

        map2.close();
    }

    /**
     * 测试持久化读取性能
     */
    @Test
    public void testPersistentReadPerformance() {
        deleteTestFile();
        int count = 500_000;
        System.out.println("\n========== MMAP 持久化读取性能测试 ==========");
        System.out.println("数据量: " + count);

        // 准备数据
        RogueMap<Long, Long> map1 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        for (long i = 1; i <= count; i++) {
            map1.put(i, i * 100);
        }
        map1.close();

        // 重新打开并测试读取
        RogueMap<Long, Long> map2 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        try {
            // 顺序读取
            long startTime = System.currentTimeMillis();
            int hitCount = 0;

            for (long i = 1; i <= count; i++) {
                Long value = map2.get(i);
                if (value != null) {
                    hitCount++;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            System.out.println("顺序读取耗时: " + duration + " ms");
            System.out.println("读取吞吐量: " + (count * 1000L / duration) + " ops/sec");
            System.out.println("命中率: " + (hitCount * 100.0 / count) + "%");

            // 随机读取
            startTime = System.currentTimeMillis();
            hitCount = 0;
            int randomCount = 100_000;

            for (int i = 0; i < randomCount; i++) {
                long randomKey = (long) (Math.random() * count) + 1;
                Long value = map2.get(randomKey);
                if (value != null) {
                    hitCount++;
                }
            }

            duration = System.currentTimeMillis() - startTime;
            System.out.println("\n随机读取耗时: " + duration + " ms (读取 " + randomCount + " 次)");
            System.out.println("读取吞吐量: " + (randomCount * 1000L / duration) + " ops/sec");
            System.out.println("命中率: " + (hitCount * 100.0 / randomCount) + "%");
        } finally {
            map2.close();
        }
    }

    /**
     * 测试增量更新性能
     */
    @Test
    public void testIncrementalUpdatePerformance() {
        deleteTestFile();
        int initialCount = 100_000;
        int updateCount = 50_000;
        System.out.println("\n========== MMAP 增量更新性能测试 ==========");
        System.out.println("初始数据量: " + initialCount);
        System.out.println("更新数据量: " + updateCount);

        // 第一阶段：写入初始数据
        RogueMap<Long, Long> map1 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(initialCount)
                .build();

        for (long i = 1; i <= initialCount; i++) {
            map1.put(i, i * 100);
        }
        map1.close();

        // 第二阶段：重新打开并更新
        RogueMap<Long, Long> map2 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(initialCount)
                .build();

        try {
            long startTime = System.currentTimeMillis();

            // 更新一半旧数据,新增一半新数据
            for (long i = 1; i <= updateCount / 2; i++) {
                map2.put(i, i * 200);  // 更新
            }
            for (long i = initialCount + 1; i <= initialCount + updateCount / 2; i++) {
                map2.put(i, i * 100);  // 新增
            }

            long updateDuration = System.currentTimeMillis() - startTime;
            System.out.println("更新耗时: " + updateDuration + " ms");
            System.out.println("更新吞吐量: " + (updateCount * 1000L / updateDuration) + " ops/sec");

            startTime = System.currentTimeMillis();
            map2.close();
            long closeDuration = System.currentTimeMillis() - startTime;

            System.out.println("持久化耗时: " + closeDuration + " ms");
            System.out.println("总耗时: " + (updateDuration + closeDuration) + " ms");
        } catch (Exception e) {
            map2.close();
            throw e;
        }
    }

    /**
     * 测试多次会话性能
     */
    @Test
    public void testMultipleSessionsPerformance() {
        deleteTestFile();
        int sessionsCount = 5;
        int itemsPerSession = 10_000;
        System.out.println("\n========== MMAP 多次会话性能测试 ==========");
        System.out.println("会话数: " + sessionsCount);
        System.out.println("每会话数据量: " + itemsPerSession);

        long totalDuration = 0;

        for (int session = 0; session < sessionsCount; session++) {
            long startTime = System.currentTimeMillis();

            RogueMap<String, String> map = RogueMap.<String, String>mmap()
                    .persistent(TEST_FILE)
                    .allocateSize(1024L * 1024 * 1024)
                    .keyCodec(new StringCodec())
                    .valueCodec(new StringCodec())
                    .segmentedIndex(32)
                    .build();

            // 添加本次会话数据
            for (int i = 0; i < itemsPerSession; i++) {
                map.put("session" + session + "_key" + i, "value" + i);
            }

            map.close();

            long sessionDuration = System.currentTimeMillis() - startTime;
            totalDuration += sessionDuration;

            System.out.println("会话 " + session + " 耗时: " + sessionDuration + " ms");
        }

        System.out.println("\n总耗时: " + totalDuration + " ms");
        System.out.println("平均每会话: " + (totalDuration / sessionsCount) + " ms");
        System.out.println("总吞吐量: " + (sessionsCount * itemsPerSession * 1000L / totalDuration) + " ops/sec");

        // 验证最终数据
        RogueMap<String, String> finalMap = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .segmentedIndex(32)
                .build();

        System.out.println("最终数据量: " + finalMap.size());
        finalMap.close();
    }

    /**
     * 测试大对象持久化性能
     */
    @Test
    public void testLargeObjectPersistencePerformance() {
        deleteTestFile();
        int count = 1_000_000;
        System.out.println("\n========== MMAP 大对象持久化性能测试 ==========");
        System.out.println("数据量: " + count);

        // 写入阶段
        RogueMap<Long, TestUserData> map1 = RogueMap.<Long, TestUserData>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestUserData.class))
                .initialCapacity(count)
                .build();

        long startTime = System.currentTimeMillis();

        for (long i = 1; i <= count; i++) {
            TestUserData userData = createUserData(i);
            map1.put(i, userData);
        }

        long insertDuration = System.currentTimeMillis() - startTime;

        startTime = System.currentTimeMillis();
        map1.close();
        long closeDuration = System.currentTimeMillis() - startTime;

        System.out.println("写入耗时: " + insertDuration + " ms");
        System.out.println("持久化耗时: " + closeDuration + " ms");
        System.out.println("总耗时: " + (insertDuration + closeDuration) + " ms");
        System.out.println("写入吞吐量: " + (count * 1000L / insertDuration) + " ops/sec");

        // 读取阶段
        startTime = System.currentTimeMillis();

        RogueMap<Long, TestUserData> map2 = RogueMap.<Long, TestUserData>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestUserData.class))
                .initialCapacity(count)
                .build();

        long recoveryDuration = System.currentTimeMillis() - startTime;
        System.out.println("\n恢复耗时: " + recoveryDuration + " ms");

        startTime = System.currentTimeMillis();
        int verifyCount = 0;

        for (long i = 1; i <= count; i++) {
            TestUserData userData = map2.get(i);
            if (userData != null && userData.getUserId() == i) {
                verifyCount++;
            }
        }

        long readDuration = System.currentTimeMillis() - startTime;
        System.out.println("读取耗时: " + readDuration + " ms");
        System.out.println("读取吞吐量: " + (count * 1000L / readDuration) + " ops/sec");
        System.out.println("验证成功: " + verifyCount + "/" + count);

        map2.close();
    }

    /**
     * 测试分段索引持久化性能
     */
    @Test
    public void testSegmentedIndexPersistencePerformance() {
        deleteTestFile();
        int count = 500_000;
        System.out.println("\n========== MMAP 分段索引持久化性能测试 ==========");
        System.out.println("数据量: " + count);
        System.out.println("段数: 64");

        // 写入阶段
        RogueMap<String, String> map1 = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .segmentedIndex(64)
                .initialCapacity(count / 64)
                .build();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            map1.put("key" + i, "value" + i);
        }

        long insertDuration = System.currentTimeMillis() - startTime;

        startTime = System.currentTimeMillis();
        map1.close();
        long closeDuration = System.currentTimeMillis() - startTime;

        System.out.println("写入耗时: " + insertDuration + " ms");
        System.out.println("持久化耗时: " + closeDuration + " ms");
        System.out.println("总耗时: " + (insertDuration + closeDuration) + " ms");

        // 恢复阶段
        startTime = System.currentTimeMillis();

        RogueMap<String, String> map2 = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .segmentedIndex(64)
                .initialCapacity(count / 64)
                .build();

        long recoveryDuration = System.currentTimeMillis() - startTime;
        System.out.println("\n恢复耗时: " + recoveryDuration + " ms");
        System.out.println("恢复数据量: " + map2.size());

        map2.close();
    }

    /**
     * 测试使用原始类型索引的内存占用
     */
    @Test
    public void testPrimitiveIndexMemoryUsage() {
        deleteTestFile();
        int count = 2_000_000;
        System.out.println("\n========== MMAP 原始类型索引内存占用测试 ==========");
        System.out.println("数据量: " + count);

        // 获取初始堆内存使用情况
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("初始堆内存占用: " + formatMemorySize(initialMemory));

        long estimatedSize = count * 300L;
        long allocateSize = Math.max(estimatedSize * 2, 50L * 1024 * 1024);
        System.out.println("预估数据大小: " + formatMemorySize(estimatedSize));
        System.out.println("预分配文件大小: " + formatMemorySize(allocateSize));

        // 使用原始类型索引
        RogueMap<Long, TestUserData> map1 = RogueMap.<Long, TestUserData>mmap()
                .persistent(TEST_FILE)
                .allocateSize(allocateSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestUserData.class))
                .primitiveIndex()  // 使用原始类型索引！
                .initialCapacity(count)
                .build();

        long beforeInsertMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("\n创建Map后堆内存占用: " + formatMemorySize(beforeInsertMemory));

        // 插入数据
        long startTime = System.currentTimeMillis();

        for (long i = 1; i <= count; i++) {
            TestUserData userData = createUserData(i);
            map1.put(i, userData);
        }

        long insertDuration = System.currentTimeMillis() - startTime;
        long afterInsertMemory = runtime.totalMemory() - runtime.freeMemory();
        long heapMemoryIncrease = afterInsertMemory - beforeInsertMemory;

        System.out.println("\n--- 写入完成统计 ---");
        System.out.println("插入耗时: " + insertDuration + " ms");
        System.out.println("插入吞吐量: " + (count * 1000L / insertDuration) + " ops/sec");
        System.out.println("堆内存占用: " + formatMemorySize(afterInsertMemory));
        System.out.println("堆内存增长: " + formatMemorySize(heapMemoryIncrease));
        System.out.println("平均每个对象堆内存开销: " + formatMemorySize(heapMemoryIncrease / count));
        System.out.println("当前数据量: " + map1.size() + " 条");

        // 关闭并持久化
        startTime = System.currentTimeMillis();
        map1.close();
        long closeDuration = System.currentTimeMillis() - startTime;

        runtime.gc();
        long afterCloseMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("\n--- 持久化完成统计 ---");
        System.out.println("持久化耗时: " + closeDuration + " ms");
        System.out.println("关闭Map后堆内存占用: " + formatMemorySize(afterCloseMemory));

        // 检查持久化文件
        File dbFile = new File(TEST_FILE);
        long actualFileSize = dbFile.length();

        System.out.println("\n--- 磁盘空间统计 ---");
        System.out.println("实际文件大小: " + formatMemorySize(actualFileSize));
        System.out.println("平均每个对象磁盘占用: " + formatMemorySize(actualFileSize / count));
        System.out.println("平均每个对象堆内存开销: " + formatMemorySize(heapMemoryIncrease / count));

        // 恢复阶段
        System.out.println("\n--- 恢复阶段测试 ---");

        RogueMap<Long, TestUserData> map2 = RogueMap.<Long, TestUserData>mmap()
                .persistent(TEST_FILE)
                .allocateSize(allocateSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestUserData.class))
                .primitiveIndex()  // 使用原始类型索引！
                .initialCapacity(count)
                .build();

        long afterRecoveryMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("恢复后堆内存占用: " + formatMemorySize(afterRecoveryMemory));
        System.out.println("恢复数据量: " + map2.size() + " 条");

        // 验证数据
        int verifyCount = 0;
        startTime = System.currentTimeMillis();
        for (long i = 1; i <= count; i++) {
            TestUserData userData = map2.get(i);
            if (userData != null && userData.getUserId() == i) {
                verifyCount++;
            }
        }
        long verifyDuration = System.currentTimeMillis() - startTime;

        System.out.println("\n--- 验证完成统计 ---");
        System.out.println("验证成功: " + verifyCount + "/" + count);
        System.out.println("验证耗时: " + verifyDuration + " ms");
        System.out.println("验证吞吐量: " + (count * 1000L / verifyDuration) + " ops/sec");

        System.out.println("\n=== 内存优化效果 ===");
        System.out.println("使用 LongPrimitiveIndex 后:");
        System.out.println("  索引内存占用: " + formatMemorySize(heapMemoryIncrease));
        System.out.println("  平均每条索引: " + formatMemorySize(heapMemoryIncrease / count));
        System.out.println("  理论值: 20 bytes (key:8 + address:8 + size:4)");

        map2.close();
    }

    /**
     * 测试大对象持久化后的内存占用情况
     */
    @Test
    public void testLargeObjectMemoryUsage() {
        deleteTestFile();
        int count = 1_000_000;  // 减少数据量以更好地观察内存占用
        System.out.println("\n========== MMAP 大对象内存占用测试 ==========");
        System.out.println("数据量: " + count);

        // 获取初始堆内存使用情况
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();  // 强制垃圾回收
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("初始堆内存占用: " + formatMemorySize(initialMemory));

        // 先创建一个TestUserData对象来估算单个对象大小
        TestUserData sampleUser = createUserData(1L);
        System.out.println("\n--- 单个对象大小估算 ---");
        System.out.println("示例对象: " + sampleUser);

        // 写入阶段 - 使用较小的预分配大小来观察实际使用
        long estimatedSize = count * 300L; // 估算每个对象300字节
        long allocateSize = Math.max(estimatedSize * 2, 50L * 1024 * 1024); // 至少50MB，双倍缓冲
        System.out.println("预估数据大小: " + formatMemorySize(estimatedSize));
        System.out.println("预分配文件大小: " + formatMemorySize(allocateSize));

        RogueMap<Long, TestUserData> map1 = RogueMap.<Long, TestUserData>mmap()
                .persistent(TEST_FILE)
                .allocateSize(allocateSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestUserData.class))
                .initialCapacity(count)
                .build();

        long beforeInsertMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("\n创建Map后堆内存占用: " + formatMemorySize(beforeInsertMemory));

        // 插入数据
        long startTime = System.currentTimeMillis();
        long batchSize = 100_000; // 每10万个对象输出一次进度

        for (long i = 1; i <= count; i++) {
            TestUserData userData = createUserData(i);
            map1.put(i, userData);

            // 每1万个对象输出一次进度
            if (i % batchSize == 0) {
                System.out.printf("已插入 %d/%d 个对象\n", i, count);
            }
        }

        long insertDuration = System.currentTimeMillis() - startTime;
        long afterInsertMemory = runtime.totalMemory() - runtime.freeMemory();
        long heapMemoryIncrease = afterInsertMemory - beforeInsertMemory;

        System.out.println("\n--- 写入完成统计 ---");
        System.out.println("插入耗时: " + insertDuration + " ms");
        System.out.println("插入吞吐量: " + (count * 1000L / insertDuration) + " ops/sec");
        System.out.println("堆内存占用: " + formatMemorySize(afterInsertMemory));
        System.out.println("堆内存增长: " + formatMemorySize(heapMemoryIncrease));
        System.out.println("平均每个对象堆内存开销: " + formatMemorySize(heapMemoryIncrease / count));
        System.out.println("当前数据量: " + map1.size() + " 条");

        // 关闭并持久化
        startTime = System.currentTimeMillis();
        map1.close();
        long closeDuration = System.currentTimeMillis() - startTime;

        runtime.gc();  // 强制垃圾回收
        long afterCloseMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("\n--- 持久化完成统计 ---");
        System.out.println("持久化耗时: " + closeDuration + " ms");
        System.out.println("关闭Map后堆内存占用: " + formatMemorySize(afterCloseMemory));

        // 检查持久化文件实际大小
        File dbFile = new File(TEST_FILE);
        long actualFileSize = dbFile.length();
        long remainingSpace = allocateSize - actualFileSize;

        System.out.println("\n--- 磁盘空间统计 ---");
        System.out.println("预分配文件大小: " + formatMemorySize(allocateSize));
        System.out.println("实际文件大小: " + formatMemorySize(actualFileSize));
        System.out.println("剩余空间: " + formatMemorySize(remainingSpace));
        System.out.println("空间利用率: " + String.format("%.2f%%", (double) actualFileSize / allocateSize * 100));

        System.out.println("\n平均每个对象:");
        System.out.println("  磁盘占用: " + formatMemorySize(actualFileSize / count));
        System.out.println("  堆内存开销: " + formatMemorySize(heapMemoryIncrease / count));

        // 恢复阶段 - 测试重新加载时的内存占用
        System.out.println("\n--- 恢复阶段测试 ---");

        RogueMap<Long, TestUserData> map2 = RogueMap.<Long, TestUserData>mmap()
                .persistent(TEST_FILE)
                .allocateSize(allocateSize)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(TestUserData.class))
                .initialCapacity(count)
                .build();

        long afterRecoveryMemory = runtime.totalMemory() - runtime.freeMemory();

        System.out.println("恢复后堆内存占用: " + formatMemorySize(afterRecoveryMemory));
        System.out.println("恢复数据量: " + map2.size() + " 条");
        System.out.println("恢复内存开销: " + formatMemorySize(afterRecoveryMemory - afterCloseMemory));
        System.out.println("恢复平均每个对象: " + formatMemorySize((afterRecoveryMemory - afterCloseMemory) / map2.size()));

        // 验证数据完整性
        int verifyCount = 0;
        startTime = System.currentTimeMillis();
        for (long i = 1; i <= count; i++) {
            TestUserData userData = map2.get(i);
            if (userData != null && userData.getUserId() == i) {
                verifyCount++;
            }
        }
        long verifyDuration = System.currentTimeMillis() - startTime;

        long afterVerifyMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("\n--- 验证完成统计 ---");
        System.out.println("验证成功: " + verifyCount + "/" + count);
        System.out.println("验证耗时: " + verifyDuration + " ms");
        System.out.println("验证吞吐量: " + (count * 1000L / verifyDuration) + " ops/sec");
        System.out.println("验证后堆内存占用: " + formatMemorySize(afterVerifyMemory));

        // 综合统计
        System.out.println("\n=== 综合统计总结 ===");
        System.out.println("数据量: " + count + " 个TestUserData对象");
        System.out.println("初始堆内存: " + formatMemorySize(initialMemory));
        System.out.println("峰值堆内存: " + formatMemorySize(afterInsertMemory));
        System.out.println("当前堆内存: " + formatMemorySize(afterVerifyMemory));
        System.out.println("预分配磁盘空间: " + formatMemorySize(allocateSize));
        System.out.println("实际磁盘使用: " + formatMemorySize(actualFileSize));
        System.out.println("磁盘空间效率: " + String.format("%.2f%%", (double) actualFileSize / allocateSize * 100));
        System.out.println("内存压缩比: " + String.format("%.2f", (double) actualFileSize / heapMemoryIncrease));

        map2.close();
    }

    /**
     * 测试文件大小和性能关系
     */
    @Test
    public void testFileSizePerformance() {
        System.out.println("\n========== MMAP 文件大小性能测试 ==========");

        long[] fileSizes = {10L * 1024 * 1024, 100L * 1024 * 1024, 1024L * 1024 * 1024};
        String[] sizeNames = {"10MB", "100MB", "1GB"};
        int count = 10_000;

        for (int i = 0; i < fileSizes.length; i++) {
            String testFile = "target/test-size-" + i + ".db";
            System.out.println("\n文件大小: " + sizeNames[i]);

            try {
                long startTime = System.currentTimeMillis();

                RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                        .persistent(testFile)
                        .allocateSize(fileSizes[i])
                        .keyCodec(PrimitiveCodecs.LONG)
                        .valueCodec(PrimitiveCodecs.LONG)
                        .initialCapacity(count)
                        .build();

                for (long j = 1; j <= count; j++) {
                    map.put(j, j * 100);
                }

                map.close();

                long duration = System.currentTimeMillis() - startTime;
                System.out.println("  耗时: " + duration + " ms");
                System.out.println("  吞吐量: " + (count * 1000L / duration) + " ops/sec");
            } finally {
                new File(testFile).delete();
            }
        }
    }

    /**
     * 测试删除操作性能
     */
    @Test
    public void testDeletePerformanceWithPersistence() {
        deleteTestFile();
        int count = 100_000;
        System.out.println("\n========== MMAP 删除操作性能测试 ==========");
        System.out.println("数据量: " + count);

        // 准备数据
        RogueMap<Long, Long> map1 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        for (long i = 1; i <= count; i++) {
            map1.put(i, i * 100);
        }
        map1.close();

        // 重新打开并删除
        RogueMap<Long, Long> map2 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        long startTime = System.currentTimeMillis();

        // 删除一半数据
        for (long i = 1; i <= count / 2; i++) {
            map2.remove(i);
        }

        long deleteDuration = System.currentTimeMillis() - startTime;

        startTime = System.currentTimeMillis();
        map2.close();
        long closeDuration = System.currentTimeMillis() - startTime;

        System.out.println("删除耗时: " + deleteDuration + " ms");
        System.out.println("持久化耗时: " + closeDuration + " ms");
        System.out.println("删除吞吐量: " + (count / 2 * 1000L / deleteDuration) + " ops/sec");

        // 验证删除
        RogueMap<Long, Long> map3 = RogueMap.<Long, Long>mmap()
                .persistent(TEST_FILE)
                .allocateSize(1024L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .initialCapacity(count)
                .build();

        System.out.println("剩余数据量: " + map3.size());
        map3.close();
    }

    // ========== 辅助方法 ==========

    /**
     * 格式化内存大小显示
     */
    private String formatMemorySize(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }

        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = bytes;
        int unitIndex = 0;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return String.format("%.2f %s", size, units[unitIndex]);
    }

    private TestUserData createUserData(long id) {
        return new TestUserData(
                id,
                "user_" + id,
                "user" + id + "@example.com",
                20 + (int) (id % 50),
                1000.0 + (id % 10000),
                System.currentTimeMillis() - (id % 1000000),
                "Address Line 1, City " + (id % 100) + ", Country",
                "+86-138" + String.format("%08d", id % 100000000)
        );
    }

    /**
     * 测试用户数据对象
     */
    public static class TestUserData {
        private long userId;
        private String username;
        private String email;
        private int age;
        private double balance;
        private long lastLoginTime;
        private String address;
        private String phoneNumber;

        public TestUserData() {}

        public TestUserData(long userId, String username, String email, int age,
                            double balance, long lastLoginTime, String address, String phoneNumber) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.age = age;
            this.balance = balance;
            this.lastLoginTime = lastLoginTime;
            this.address = address;
            this.phoneNumber = phoneNumber;
        }

        public long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getEmail() { return email; }
        public int getAge() { return age; }
        public double getBalance() { return balance; }
        public long getLastLoginTime() { return lastLoginTime; }
        public String getAddress() { return address; }
        public String getPhoneNumber() { return phoneNumber; }
    }
}
