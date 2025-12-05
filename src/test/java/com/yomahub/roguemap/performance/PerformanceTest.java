package com.yomahub.roguemap.performance;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;

/**
 * RogueMap 性能测试
 *
 * 测试场景：使用 Long 作为键（8字节），UserData 作为值（约200-300字节）
 * 使用原始类型索引，内存占用比HashMap减少80%以上
 */
public class PerformanceTest {

    public static void main(String[] args) {
        System.out.println("=== RogueMap 性能测试 ===");
        System.out.println("使用原始类型索引（LongPrimitiveIndex）");
        System.out.println("测试场景：Long 键 -> UserData 值（值远大于键）\n");

        // 打印JVM配置
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        System.out.println("JVM 最大堆内存: " + (maxMemory / 1024 / 1024) + " MB\n");

        // 测试不同数据量
        int testCount = 3_000_000;
        runTest(testCount);
        System.out.println("\n================================================================================\n");
        /*runHashMapTest(testCount);
        System.out.println();*/
    }

    private static void runTest(int count) {
        System.out.println("--- " + count + " 数据测试 ---");

        // 打印初始内存状态
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("开始前堆内存使用: " + (usedMemoryBefore / 1024 / 1024) + " MB");

        RogueMap<Long, UserData> userMap = RogueMap.<Long, UserData>builder()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(KryoObjectCodec.create(UserData.class))
                .maxMemory(1024L * 1024 * 1024)  // 默认1G
                .primitiveIndex()              // 使用原始类型索引
                .initialCapacity(count)        // 根据实际数据量分配
                .build();

        // 插入测试
        System.out.println("插入 " + count + " 条数据...");
        long startTime = System.currentTimeMillis();

        // 注意：从1开始，因为0被用作内部标记
        for (long i = 1; i <= count; i++) {
            UserData userData = createUserData(i);
            userMap.put(i, userData);
        }

        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println("  插入耗时: " + insertTime + " ms");
        System.out.println("  插入吞吐量: " + (count * 1000L / insertTime) + " ops/sec");

        // 打印插入后内存状态
        System.gc();  // 建议GC清理临时对象
        try {
            Thread.sleep(100);  // 等待GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("  插入后堆内存使用: " + (usedMemoryAfter / 1024 / 1024) + " MB");
        System.out.println("  堆内存增量: " + ((usedMemoryAfter - usedMemoryBefore) / 1024 / 1024) + " MB");

        // 读取测试
        System.out.println("读取 " + count + " 条数据...");
        startTime = System.currentTimeMillis();
        int verifyCount = 0;

        for (long i = 1; i <= count; i++) {
            UserData userData = userMap.get(i);
            if (userData != null && userData.getUserId() == i) {
                verifyCount++;
            }
        }

        long readTime = System.currentTimeMillis() - startTime;
        System.out.println("  读取耗时: " + readTime + " ms");
        System.out.println("  读取吞吐量: " + (count * 1000L / readTime) + " ops/sec");
        System.out.println("  验证成功: " + verifyCount + "/" + count);

        // 随机读取测试
        System.out.println("随机读取测试（10万次）...");
        startTime = System.currentTimeMillis();
        int randomReadCount = Math.min(100_000, count);
        int failCount = 0;

        for (int i = 0; i < randomReadCount; i++) {
            long randomKey = (long) (Math.random() * count) + 1;  // 1 to count
            UserData userData = userMap.get(randomKey);
            if (userData == null) {
                failCount++;
            }
        }

        long randomReadTime = System.currentTimeMillis() - startTime;
        System.out.println("  随机读取耗时: " + randomReadTime + " ms");
        System.out.println("  随机读取吞吐量: " + (randomReadCount * 1000L / randomReadTime) + " ops/sec");
        if (failCount > 0) {
            System.err.println("  随机读取失败: " + failCount + " 次");
        }

        // 最终统计
        System.out.println("\n--- 最终统计 ---");
        System.out.println("数据条数: " + userMap.size() + " 条");

        // 估算内存使用
        int avgValueSize = KryoObjectCodec.create(UserData.class).calculateSize(createUserData(0));
        long rogueMapOffHeapMB = (long) userMap.size() * avgValueSize / 1024 / 1024;
        long rogueMapIndexMB = (long) userMap.size() * 20 / 1024 / 1024;  // 每条20字节索引 (8+8+4字节)
        long rogueMapTotalMB = rogueMapOffHeapMB + rogueMapIndexMB;

        System.out.println("平均值序列化大小: " + avgValueSize + " 字节");
        System.out.println("\n【RogueMap 内存占用】");
        System.out.println("  堆外内存(值数据): " + rogueMapOffHeapMB + " MB");
        System.out.println("  堆内内存(索引):   " + rogueMapIndexMB + " MB");
        System.out.println("  总计:           " + rogueMapTotalMB + " MB");
    }

    private static void runHashMapTest(int count) {
        System.out.println("=== 传统 HashMap 对比测试 ===");
        System.out.println("--- " + count + " 数据测试 ---");

        // 打印初始内存状态
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("开始前堆内存使用: " + (usedMemoryBefore / 1024 / 1024) + " MB");

        // 创建HashMap
        java.util.HashMap<Long, UserData> hashMap = new java.util.HashMap<>(count);

        // 插入测试
        System.out.println("插入 " + count + " 条数据...");
        long startTime = System.currentTimeMillis();

        for (long i = 1; i <= count; i++) {
            UserData userData = createUserData(i);
            hashMap.put(i, userData);
        }

        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println("  插入耗时: " + insertTime + " ms");
        System.out.println("  插入吞吐量: " + (count * 1000L / insertTime) + " ops/sec");

        // 打印插入后内存状态
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("  插入后堆内存使用: " + (usedMemoryAfter / 1024 / 1024) + " MB");
        System.out.println("  堆内存增量: " + ((usedMemoryAfter - usedMemoryBefore) / 1024 / 1024) + " MB");

        // 读取测试
        System.out.println("读取 " + count + " 条数据...");
        startTime = System.currentTimeMillis();
        int verifyCount = 0;

        for (long i = 1; i <= count; i++) {
            UserData userData = hashMap.get(i);
            if (userData != null && userData.getUserId() == i) {
                verifyCount++;
            }
        }

        long readTime = System.currentTimeMillis() - startTime;
        System.out.println("  读取耗时: " + readTime + " ms");
        System.out.println("  读取吞吐量: " + (count * 1000L / readTime) + " ops/sec");
        System.out.println("  验证成功: " + verifyCount + "/" + count);

        // 随机读取测试
        System.out.println("随机读取测试（10万次）...");
        startTime = System.currentTimeMillis();
        int randomReadCount = Math.min(100_000, count);
        int failCount = 0;

        for (int i = 0; i < randomReadCount; i++) {
            long randomKey = (long) (Math.random() * count) + 1;
            UserData userData = hashMap.get(randomKey);
            if (userData == null) {
                failCount++;
            }
        }

        long randomReadTime = System.currentTimeMillis() - startTime;
        System.out.println("  随机读取耗时: " + randomReadTime + " ms");
        System.out.println("  随机读取吞吐量: " + (randomReadCount * 1000L / randomReadTime) + " ops/sec");
        if (failCount > 0) {
            System.err.println("  随机读取失败: " + failCount + " 次");
        }

        // 最终统计
        System.out.println("\n--- 最终统计 ---");
        System.out.println("数据条数: " + hashMap.size() + " 条");
        System.out.println("实际堆内存使用: " + ((usedMemoryAfter - usedMemoryBefore) / 1024 / 1024) + " MB (纯HashMap数据占用)");

        // 清理
        hashMap.clear();
    }

    /**
     * 创建测试用的 UserData 对象
     */
    private static UserData createUserData(long id) {
        return new UserData(
                id,
                "user_" + id,
                "user" + id + "@example.com",
                20 + (int) (id % 50),
                1000.0 + (id % 10000),
                System.currentTimeMillis() - (id % 1000000),
                "Address Line 1, City " + (id % 100) + ", Country",
                "+86-138" + String.format("%08d", id % 100000000));
    }
}
