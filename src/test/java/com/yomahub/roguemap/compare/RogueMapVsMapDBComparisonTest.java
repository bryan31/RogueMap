package com.yomahub.roguemap.compare;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;

/**
 * RogueMap vs MapDB 性能和内存对比测试
 *
 * 测试内容：
 * 1. 堆内存占用对比
 * 2. 写入性能对比
 * 3. 读取性能对比
 * 4. 吞吐量统计
 *
 * 测试模式：
 * - RogueMap OffHeap 模式
 * - RogueMap Mmap 临时文件模式
 * - RogueMap Mmap 持久化模式
 * - MapDB OffHeap 模式
 * - MapDB 临时文件模式
 * - MapDB 持久化模式
 */
public class RogueMapVsMapDBComparisonTest {

    private static final int DATASET_SIZE = 1_000_000;
    private static final String TEST_DIR = "test_data/roguemap_vs_mapdb";
    private static final long RANDOM_SEED = 42;

    public static void main(String[] args) throws IOException {
        System.out.println("=== RogueMap vs MapDB 性能与内存对比测试 ===");
        System.out.printf("数据集大小: %,d 条记录%n", DATASET_SIZE);
        System.out.println();

        // 确保测试目录存在
        new File(TEST_DIR).mkdirs();

        System.out.println("开始测试各种模式...\n");

        TestResult[] results = new TestResult[6];

        // RogueMap 测试
        System.out.println("【RogueMap 测试】\n");
        results[0] = testRogueMapOffHeap();
        forceGC();

        results[1] = testRogueMapMmapTemporary();
        forceGC();

        results[2] = testRogueMapMmapPersistent();
        forceGC();

        // MapDB 测试
        System.out.println("\n【MapDB 测试】\n");
        results[3] = testMapDBOffHeap();
        forceGC();

        results[4] = testMapDBTemporary();
        forceGC();

        results[5] = testMapDBPersistent();
        forceGC();

        // 输出对比结果
        printComparisonResults(results);

        // 清理测试文件
        cleanupTestFiles();
    }

    /**
     * 测试 RogueMap OffHeap 模式
     */
    private static TestResult testRogueMapOffHeap() throws IOException {
        System.out.println("测试 RogueMap OffHeap 模式...");

        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        RogueMap<Long, TestValueObject> map = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            map = RogueMap.<Long, TestValueObject>offHeap()
                    .keyCodec(PrimitiveCodecs.LONG)
                    .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                    .primitiveIndex()
                    .build();

            Random random = new Random(RANDOM_SEED);

            // 写入测试
            long writeStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = i + 1L;
                TestValueObject value = createTestValue(i, random);
                map.put(key, value);
            }
            long writeEndTime = System.nanoTime();
            writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

            // 准备随机读取的key列表
            long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

            // 随机读取测试
            long readStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = randomKeys[i];
                map.get(key);
            }
            long readEndTime = System.nanoTime();
            readTimeMs = (readEndTime - readStartTime) / 1_000_000;

            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  包含 %,d 个条目%n", map.size());
            System.out.printf("  写入耗时: %,d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %,d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (map != null) {
                map.close();
            }
            forceGC();
        }

        return new TestResult("RogueMap OffHeap", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试 RogueMap Mmap 临时文件模式
     */
    private static TestResult testRogueMapMmapTemporary() throws IOException {
        System.out.println("测试 RogueMap Mmap 临时文件模式...");

        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        RogueMap<Long, TestValueObject> map = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            map = RogueMap.<Long, TestValueObject>mmap()
                    .temporary()
                    .keyCodec(PrimitiveCodecs.LONG)
                    .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                    .primitiveIndex()
                    .build();

            Random random = new Random(RANDOM_SEED);

            // 写入测试
            long writeStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = i + 1L;
                TestValueObject value = createTestValue(i, random);
                map.put(key, value);
            }
            long writeEndTime = System.nanoTime();
            writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

            // 准备随机读取的key列表
            long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

            // 随机读取测试
            long readStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = randomKeys[i];
                map.get(key);
            }
            long readEndTime = System.nanoTime();
            readTimeMs = (readEndTime - readStartTime) / 1_000_000;

            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  包含 %,d 个条目%n", map.size());
            System.out.printf("  写入耗时: %,d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %,d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (map != null) {
                map.close();
            }
            forceGC();
        }

        return new TestResult("RogueMap Mmap 临时文件", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试 RogueMap Mmap 持久化模式
     */
    private static TestResult testRogueMapMmapPersistent() throws IOException {
        System.out.println("测试 RogueMap Mmap 持久化模式...");

        String filePath = TEST_DIR + "/roguemap_persistent.map";
        new File(filePath).delete();

        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        RogueMap<Long, TestValueObject> map = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            map = RogueMap.<Long, TestValueObject>mmap()
                    .persistent(filePath)
                    .keyCodec(PrimitiveCodecs.LONG)
                    .valueCodec(KryoObjectCodec.create(TestValueObject.class))
                    .primitiveIndex()
                    .build();

            Random random = new Random(RANDOM_SEED);

            // 写入测试
            long writeStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = i + 1L;
                TestValueObject value = createTestValue(i, random);
                map.put(key, value);
            }
            long writeEndTime = System.nanoTime();
            writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

            // 准备随机读取的key列表
            long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

            // 随机读取测试
            long readStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = randomKeys[i];
                map.get(key);
            }
            long readEndTime = System.nanoTime();
            readTimeMs = (readEndTime - readStartTime) / 1_000_000;

            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  包含 %,d 个条目%n", map.size());
            System.out.printf("  写入耗时: %,d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %,d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (map != null) {
                map.close();
            }
            new File(filePath).delete();
            forceGC();
        }

        return new TestResult("RogueMap Mmap 持久化", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试 MapDB OffHeap 模式
     */
    @SuppressWarnings("unchecked")
    private static TestResult testMapDBOffHeap() {
        System.out.println("测试 MapDB OffHeap 模式...");

        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        DB db = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            db = DBMaker
                    .memoryDirectDB()
                    .make();

            HTreeMap<Long, TestValueObject> map = db
                    .hashMap("test")
                    .keySerializer(Serializer.LONG)
                    .valueSerializer(Serializer.JAVA)
                    .create();

            Random random = new Random(RANDOM_SEED);

            // 写入测试
            long writeStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = i + 1L;
                TestValueObject value = createTestValue(i, random);
                map.put(key, value);
            }
            long writeEndTime = System.nanoTime();
            writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

            // 准备随机读取的key列表
            long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

            // 随机读取测试
            long readStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = randomKeys[i];
                map.get(key);
            }
            long readEndTime = System.nanoTime();
            readTimeMs = (readEndTime - readStartTime) / 1_000_000;

            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  包含 %,d 个条目%n", map.size());
            System.out.printf("  写入耗时: %,d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %,d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (db != null) {
                db.close();
            }
            forceGC();
        }

        return new TestResult("MapDB OffHeap", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试 MapDB 临时文件模式
     */
    @SuppressWarnings("unchecked")
    private static TestResult testMapDBTemporary() {
        System.out.println("测试 MapDB 临时文件模式...");

        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        DB db = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            db = DBMaker
                    .tempFileDB()
                    .fileMmapEnable()
                    .fileMmapPreclearDisable()
                    .fileDeleteAfterClose()
                    .make();

            HTreeMap<Long, TestValueObject> map = db
                    .hashMap("test")
                    .keySerializer(Serializer.LONG)
                    .valueSerializer(Serializer.JAVA)
                    .create();

            Random random = new Random(RANDOM_SEED);

            // 写入测试
            long writeStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = i + 1L;
                TestValueObject value = createTestValue(i, random);
                map.put(key, value);
            }
            long writeEndTime = System.nanoTime();
            writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

            // 准备随机读取的key列表
            long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

            // 随机读取测试
            long readStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = randomKeys[i];
                map.get(key);
            }
            long readEndTime = System.nanoTime();
            readTimeMs = (readEndTime - readStartTime) / 1_000_000;

            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  包含 %,d 个条目%n", map.size());
            System.out.printf("  写入耗时: %,d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %,d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (db != null) {
                db.close();
            }
            forceGC();
        }

        return new TestResult("MapDB 临时文件", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试 MapDB 持久化模式
     */
    @SuppressWarnings("unchecked")
    private static TestResult testMapDBPersistent() {
        System.out.println("测试 MapDB 持久化模式...");

        String filePath = TEST_DIR + "/mapdb_persistent";
        new File(filePath).delete();

        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        DB db = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            db = DBMaker.fileDB(filePath)
                    .fileMmapEnable()
                    .fileMmapPreclearDisable().make();

            ConcurrentMap<Long, TestValueObject> map = db
                    .hashMap("test")
                    .keySerializer(Serializer.LONG)
                    .valueSerializer(Serializer.JAVA)
                    .createOrOpen();

            Random random = new Random(RANDOM_SEED);

            // 写入测试
            long writeStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = i + 1L;
                TestValueObject value = createTestValue(i, random);
                map.put(key, value);
            }
            long writeEndTime = System.nanoTime();
            writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

            // 准备随机读取的key列表
            long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

            // 随机读取测试
            long readStartTime = System.nanoTime();
            for (int i = 0; i < DATASET_SIZE; i++) {
                long key = randomKeys[i];
                map.get(key);
            }
            long readEndTime = System.nanoTime();
            readTimeMs = (readEndTime - readStartTime) / 1_000_000;

            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  包含 %,d 个条目%n", map.size());
            System.out.printf("  写入耗时: %,d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %,d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (db != null) {
                db.close();
            }
            new File(filePath).delete();
            forceGC();
        }

        return new TestResult("MapDB 持久化", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 生成随机的key数组用于随机读取测试
     */
    private static long[] generateRandomKeys(int size, long seed) {
        long[] keys = new long[size];
        // 先填充顺序key
        for (int i = 0; i < size; i++) {
            keys[i] = i + 1L;
        }
        // Fisher-Yates 洗牌算法打乱顺序
        Random random = new Random(seed + 1); // 使用不同的种子避免与数据生成冲突
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long temp = keys[i];
            keys[i] = keys[j];
            keys[j] = temp;
        }
        return keys;
    }

    /**
     * 创建测试值对象
     */
    private static TestValueObject createTestValue(int i, Random random) {
        return new TestValueObject(
                i,
                System.currentTimeMillis() + i,
                99.99 + (random.nextDouble() * 100),
                random.nextBoolean(),
                "Product_" + i,
                "Description for product " + i + " with details",
                (byte) (random.nextInt(5)),
                (short) (random.nextInt(1000)),
                random.nextFloat() * 5,
                (char) ('A' + random.nextInt(26))
        );
    }

    /**
     * 强制垃圾回收
     */
    private static void forceGC() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 获取当前堆内存使用量（字节）
     */
    private static long getCurrentHeapMemory() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return memoryBean.getHeapMemoryUsage().getUsed();
    }

    /**
     * 创建指定字符的分隔符
     */
    private static String createSeparator(char c, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 打印对比结果
     */
    private static void printComparisonResults(TestResult[] results) {
        String separator100 = createSeparator('=', 100);
        String separator100dash = createSeparator('-', 100);

        System.out.println("\n" + separator100);
        System.out.println("RogueMap vs MapDB 性能与内存对比结果");
        System.out.println(separator100);

        // 综合对比表
        System.out.println("\n【综合指标对比】\n");
        System.out.printf("%-30s %-15s %-15s %-20s %-20s %-15s%n",
                "实现方式", "写入(ms)", "读取(ms)", "写吞吐(ops/s)", "读吞吐(ops/s)", "堆内存(MB)");
        System.out.println(separator100dash);

        for (TestResult result : results) {
            long writeThroughput = result.writeTimeMs > 0 ?
                (DATASET_SIZE * 1000L / result.writeTimeMs) : 0;
            long readThroughput = result.readTimeMs > 0 ?
                (DATASET_SIZE * 1000L / result.readTimeMs) : 0;

            System.out.printf("%-30s %-15s %-15s %-20s %-20s %-15.2f%n",
                    result.name,
                    String.format("%,d", result.writeTimeMs),
                    String.format("%,d", result.readTimeMs),
                    String.format("%,d", writeThroughput),
                    String.format("%,d", readThroughput),
                    result.getHeapMemoryMB());
        }

        // 详细分析
        System.out.println("\n【详细分析】");

        // 按实现方式分组对比
        System.out.println("\n1. OffHeap 模式对比：");
        compareResults(results[0], results[3]);

        System.out.println("\n2. 临时文件模式对比：");
        compareResults(results[1], results[4]);

        System.out.println("\n3. 持久化模式对比：");
        compareResults(results[2], results[5]);

        // RogueMap 内部对比
        System.out.println("\n【RogueMap 不同模式对比】");
        System.out.printf("%-25s %-15s %-15s %-15s%n", "模式", "写入(ms)", "读取(ms)", "堆内存(MB)");
        System.out.println(createSeparator('-', 70));
        System.out.printf("%-25s %-15s %-15s %-15.2f%n",
                "OffHeap", String.format("%,d", results[0].writeTimeMs),
                String.format("%,d", results[0].readTimeMs), results[0].getHeapMemoryMB());
        System.out.printf("%-25s %-15s %-15s %-15.2f%n",
                "Mmap 临时文件", String.format("%,d", results[1].writeTimeMs),
                String.format("%,d", results[1].readTimeMs), results[1].getHeapMemoryMB());
        System.out.printf("%-25s %-15s %-15s %-15.2f%n",
                "Mmap 持久化", String.format("%,d", results[2].writeTimeMs),
                String.format("%,d", results[2].readTimeMs), results[2].getHeapMemoryMB());

        // MapDB 内部对比
        System.out.println("\n【MapDB 不同模式对比】");
        System.out.printf("%-25s %-15s %-15s %-15s%n", "模式", "写入(ms)", "读取(ms)", "堆内存(MB)");
        System.out.println(createSeparator('-', 70));
        System.out.printf("%-25s %-15s %-15s %-15.2f%n",
                "OffHeap", String.format("%,d", results[3].writeTimeMs),
                String.format("%,d", results[3].readTimeMs), results[3].getHeapMemoryMB());
        System.out.printf("%-25s %-15s %-15s %-15.2f%n",
                "临时文件", String.format("%,d", results[4].writeTimeMs),
                String.format("%,d", results[4].readTimeMs), results[4].getHeapMemoryMB());
        System.out.printf("%-25s %-15s %-15s %-15.2f%n",
                "持久化", String.format("%,d", results[5].writeTimeMs),
                String.format("%,d", results[5].readTimeMs), results[5].getHeapMemoryMB());

        // 总结
        System.out.println("\n【总结】");
        System.out.println("\nRogueMap 的优势：");
        System.out.println("  ✓ 更低的堆内存占用");
        System.out.println("  ✓ 更灵活的序列化配置（支持Kryo等高性能序列化）");
        System.out.println("  ✓ 更简洁的API设计");
        System.out.println("  ✓ 原生支持原始类型优化");

        System.out.println("\nMapDB 的优势：");
        System.out.println("  ✓ 成熟稳定的生态系统");
        System.out.println("  ✓ 更多的数据结构支持");
        System.out.println("  ✓ 事务支持");

        System.out.println("\n推荐使用场景：");
        System.out.println("  • 如果你需要极致的内存优化和性能，选择 RogueMap");
        System.out.println("  • 如果你需要复杂的数据结构和事务支持，选择 MapDB");

        System.out.println("\n" + separator100);
    }

    /**
     * 对比两个结果
     */
    private static void compareResults(TestResult rogueMap, TestResult mapDB) {
        double heapRatio = (double) rogueMap.heapMemory / mapDB.heapMemory;
        double writeSpeedRatio = (double) mapDB.writeTimeMs / rogueMap.writeTimeMs;
        double readSpeedRatio = (double) mapDB.readTimeMs / rogueMap.readTimeMs;

        System.out.printf("  %s vs %s:%n", rogueMap.name, mapDB.name);
        System.out.printf("    堆内存: RogueMap %.2f MB / MapDB %.2f MB (比例: %.2f)%n",
                rogueMap.getHeapMemoryMB(), mapDB.getHeapMemoryMB(), heapRatio);
        System.out.printf("    写入性能: RogueMap %,d ms / MapDB %,d ms (RogueMap %.2fx %s)%n",
                rogueMap.writeTimeMs, mapDB.writeTimeMs,
                Math.abs(writeSpeedRatio),
                writeSpeedRatio >= 1 ? "更快" : "更慢");
        System.out.printf("    读取性能: RogueMap %,d ms / MapDB %,d ms (RogueMap %.2fx %s)%n",
                rogueMap.readTimeMs, mapDB.readTimeMs,
                Math.abs(readSpeedRatio),
                readSpeedRatio >= 1 ? "更快" : "更慢");
    }

    /**
     * 清理测试文件
     */
    private static void cleanupTestFiles() {
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

    /**
     * 测试结果类
     */
    private static class TestResult {
        final String name;
        final long heapMemory;      // 堆内存（字节）
        final long writeTimeMs;     // 写入耗时（毫秒）
        final long readTimeMs;      // 读取耗时（毫秒）

        TestResult(String name, long heapMemory, long writeTimeMs, long readTimeMs) {
            this.name = name;
            this.heapMemory = heapMemory;
            this.writeTimeMs = writeTimeMs;
            this.readTimeMs = readTimeMs;
        }

        double getHeapMemoryMB() {
            return heapMemory / 1024.0 / 1024.0;
        }
    }
}
