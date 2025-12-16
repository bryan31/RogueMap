package com.yomahub.roguemap.compare;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;

/**
 * RogueMap 综合性能与内存对比测试 (Object类型)
 *
 * 测试内容：
 * 1. 堆内存占用对比
 * 2. 写入性能对比
 * 3. 读取性能对比
 * 4. 吞吐量统计
 *
 * 对比实现：
 * - HashMap 模式
 * - Caffeine 缓存模式
 * - FastUtil 模式
 * - RogueMap OffHeap 模式
 * - RogueMap Mmap 临时文件模式
 * - RogueMap Mmap 持久化模式
 * - MapDB OffHeap 模式
 * - MapDB 临时文件模式
 * - MapDB 持久化模式
 */
public class PerformanceComparisonTest {

    private static final int DATASET_SIZE = 1_000_000;
    private static final String TEST_DIR = "test_data/comprehensive_test";
    private static final long RANDOM_SEED = 42;

    public static void main(String[] args) throws IOException {
        System.out.println("=== RogueMap 综合性能与内存对比测试 ===");
        System.out.printf("数据集大小: %d 条记录%n", DATASET_SIZE);
        System.out.println();

        // 确保测试目录存在
        new File(TEST_DIR).mkdirs();

        System.out.println("开始测试各种模式...\n");

        // 运行各种模式的测试
        Map<String, TestResult> results = new HashMap<>();

        results.put("HashMap模式", testHashMapMode());
        forceGC();

        results.put("Caffeine缓存模式", testCaffeineMode());
        forceGC();

        results.put("FastUtil模式", testFastUtilMode());
        forceGC();

        results.put("RogueMap.OffHeap模式", testOffHeapMode());
        forceGC();

        results.put("RogueMap.Mmap临时文件模式", testMmapTemporaryMode());
        forceGC();

        results.put("RogueMap.Mmap持久化模式", testMmapPersistentMode());
        forceGC();

        results.put("MapDB.OffHeap模式", testMapDBOffHeap());
        forceGC();

        results.put("MapDB.临时文件模式", testMapDBTemporary());
        forceGC();

        results.put("MapDB.持久化模式", testMapDBPersistent());

        // 输出对比结果
        printComparisonResults(results);

        // 清理测试文件
        cleanupTestFiles();
    }

    /**
     * 测试HashMap模式
     */
    private static TestResult testHashMapMode() {
        System.out.println("测试 HashMap 模式...");

        // 清理内存，建立基准
        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        // 创建HashMap并填充数据（不保留数据引用）
        Map<Long, TestValueObject> map = new HashMap<>(DATASET_SIZE);

        Random random = new Random(RANDOM_SEED);

        // 写入测试
        long writeStartTime = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            long key = i + 1L;
            TestValueObject value = createTestValue(i, random);
            map.put(key, value);
        }
        long writeEndTime = System.nanoTime();
        long writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

        // 准备随机读取的key列表
        long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

        // 随机读取测试
        long readStartTime = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            long key = randomKeys[i];
            map.get(key);
        }
        long readEndTime = System.nanoTime();
        long readTimeMs = (readEndTime - readStartTime) / 1_000_000;

        // 强制GC，获取稳定的内存使用量
        forceGC();
        long usedMemory = getCurrentHeapMemory();
        long heapUsed = usedMemory - baselineMemory;

        System.out.printf("  HashMap 包含 %d 个条目%n", map.size());
        System.out.printf("  写入耗时: %d ms%n", writeTimeMs);
        System.out.printf("  读取耗时: %d ms%n", readTimeMs);
        System.out.printf("  堆内存占用: %.2f MB%n", heapUsed / 1024.0 / 1024.0);

        // 清理
        map.clear();
        map = null;
        forceGC();

        return new TestResult("HashMap模式", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试OffHeap模式
     */
    private static TestResult testOffHeapMode() throws IOException {
        System.out.println("测试 RogueMap.OffHeap 模式...");

        // 清理内存，建立基准
        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        RogueMap<Long, TestValueObject> map = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            // 创建OffHeap RogueMap
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

            // 强制GC，清理临时对象
            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  OffHeap Map 包含 %d 个条目%n", map.size());
            System.out.printf("  写入耗时: %d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (map != null) {
                map.close();
            }
            forceGC();
        }

        return new TestResult("RogueMap.OffHeap模式", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试Mmap临时文件模式
     */
    private static TestResult testMmapTemporaryMode() throws IOException {
        System.out.println("测试 RogueMap.Mmap 临时文件模式...");

        // 清理内存，建立基准
        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        RogueMap<Long, TestValueObject> map = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            // 创建Mmap临时文件 RogueMap
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

            // 强制GC，清理临时对象
            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  Mmap临时文件 Map 包含 %d 个条目%n", map.size());
            System.out.printf("  写入耗时: %d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (map != null) {
                map.close();
            }
            forceGC();
        }

        return new TestResult("RogueMap.Mmap临时文件模式", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试Mmap持久化模式
     */
    private static TestResult testMmapPersistentMode() throws IOException {
        System.out.println("测试 RogueMap.Mmap 持久化模式...");

        String filePath = TEST_DIR + "/persistent_test.map";
        new File(filePath).delete();

        // 清理内存，建立基准
        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        RogueMap<Long, TestValueObject> map = null;
        long writeTimeMs = 0;
        long readTimeMs = 0;
        long heapUsed = 0;

        try {
            // 创建Mmap持久化 RogueMap
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

            // 强制GC，清理临时对象
            forceGC();
            long usedMemory = getCurrentHeapMemory();
            heapUsed = usedMemory - baselineMemory;

            System.out.printf("  Mmap持久化 Map 包含 %d 个条目%n", map.size());
            System.out.printf("  写入耗时: %d ms%n", writeTimeMs);
            System.out.printf("  读取耗时: %d ms%n", readTimeMs);
            System.out.printf("  堆内存占用: %.2f MB%n", heapUsed / 1024.0 / 1024.0);
        } finally {
            if (map != null) {
                map.close();
            }
            new File(filePath).delete();
            forceGC();
        }

        return new TestResult("RogueMap.Mmap持久化模式", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试Caffeine缓存模式
     */
    private static TestResult testCaffeineMode() {
        System.out.println("测试 Caffeine 缓存模式...");

        // 清理内存，建立基准
        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        // 创建Caffeine缓存，设置最大容量
        Cache<Long, TestValueObject> cache = Caffeine.newBuilder()
                .maximumSize(DATASET_SIZE)
                .build();

        Random random = new Random(RANDOM_SEED);

        // 写入测试
        long writeStartTime = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            long key = i + 1L;
            TestValueObject value = createTestValue(i, random);
            cache.put(key, value);
        }
        long writeEndTime = System.nanoTime();
        long writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

        // 准备随机读取的key列表
        long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

        // 随机读取测试
        long readStartTime = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            long key = randomKeys[i];
            cache.getIfPresent(key);
        }
        long readEndTime = System.nanoTime();
        long readTimeMs = (readEndTime - readStartTime) / 1_000_000;

        // 强制GC，获取稳定的内存使用量
        forceGC();
        long usedMemory = getCurrentHeapMemory();
        long heapUsed = usedMemory - baselineMemory;

        System.out.printf("  Caffeine 缓存包含 %d 个条目%n", cache.estimatedSize());
        System.out.printf("  写入耗时: %d ms%n", writeTimeMs);
        System.out.printf("  读取耗时: %d ms%n", readTimeMs);
        System.out.printf("  堆内存占用: %.2f MB%n", heapUsed / 1024.0 / 1024.0);

        // 清理
        cache.invalidateAll();
        cache.cleanUp();
        cache = null;
        forceGC();

        return new TestResult("Caffeine缓存模式", heapUsed, writeTimeMs, readTimeMs);
    }

    /**
     * 测试FastUtil模式
     */
    private static TestResult testFastUtilMode() {
        System.out.println("测试 FastUtil 模式...");

        // 清理内存，建立基准
        forceGC();
        long baselineMemory = getCurrentHeapMemory();

        // 创建FastUtil Long2ObjectOpenHashMap
        Long2ObjectMap<TestValueObject> map = new Long2ObjectOpenHashMap<>(DATASET_SIZE);

        Random random = new Random(RANDOM_SEED);

        // 写入测试
        long writeStartTime = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            long key = i + 1L;
            TestValueObject value = createTestValue(i, random);
            map.put(key, value);
        }
        long writeEndTime = System.nanoTime();
        long writeTimeMs = (writeEndTime - writeStartTime) / 1_000_000;

        // 准备随机读取的key列表
        long[] randomKeys = generateRandomKeys(DATASET_SIZE, RANDOM_SEED);

        // 随机读取测试
        long readStartTime = System.nanoTime();
        for (int i = 0; i < DATASET_SIZE; i++) {
            long key = randomKeys[i];
            map.get(key);
        }
        long readEndTime = System.nanoTime();
        long readTimeMs = (readEndTime - readStartTime) / 1_000_000;

        // 强制GC，获取稳定的内存使用量
        forceGC();
        long usedMemory = getCurrentHeapMemory();
        long heapUsed = usedMemory - baselineMemory;

        System.out.printf("  FastUtil Map 包含 %d 个条目%n", map.size());
        System.out.printf("  写入耗时: %d ms%n", writeTimeMs);
        System.out.printf("  读取耗时: %d ms%n", readTimeMs);
        System.out.printf("  堆内存占用: %.2f MB%n", heapUsed / 1024.0 / 1024.0);

        // 清理
        map.clear();
        map = null;
        forceGC();

        return new TestResult("FastUtil模式", heapUsed, writeTimeMs, readTimeMs);
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

        return new TestResult("MapDB.OffHeap模式", heapUsed, writeTimeMs, readTimeMs);
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

        return new TestResult("MapDB.临时文件模式", heapUsed, writeTimeMs, readTimeMs);
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

        return new TestResult("MapDB.持久化模式", heapUsed, writeTimeMs, readTimeMs);
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
     * 创建测试值对象（独立方法，便于GC）
     */
    private static TestValueObject createTestValue(int i, Random random) {
        return new TestValueObject(
                i,                                          // id
                System.currentTimeMillis() + i,            // timestamp
                99.99 + (random.nextDouble() * 100),       // price
                random.nextBoolean(),                      // active
                "Product_" + i,                            // name
                "Description for product " + i + " with details", // description
                (byte) (random.nextInt(5)),                // status
                (short) (random.nextInt(1000)),            // quantity
                random.nextFloat() * 5,                    // rating
                (char) ('A' + random.nextInt(26))          // category
        );
    }

    /**
     * 强制垃圾回收，确保内存测量准确
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
     * 创建指定字符的分隔符（Java 8 兼容）
     */
    private static String createSeparator(char c, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 打印综合对比结果
     */
    private static void printComparisonResults(Map<String, TestResult> results) {
        String separator100 = createSeparator('=', 100);
        String separator100dash = createSeparator('-', 100);

        System.out.println("\n" + separator100);
        System.out.println("综合性能与内存对比结果");
        System.out.println(separator100);

        // 找出HashMap作为基准
        TestResult hashMapResult = results.get("HashMap模式");
        double hashMapHeapMB = hashMapResult != null ? hashMapResult.getHeapMemoryMB() : 0;
        long hashMapWriteTime = hashMapResult != null ? hashMapResult.writeTimeMs : 0;
        long hashMapReadTime = hashMapResult != null ? hashMapResult.readTimeMs : 0;

        // 1. 综合性能与内存对比表
        System.out.println("\n【综合指标对比】\n");
        System.out.printf("%-30s %-15s %-15s %-20s %-20s %-15s%n",
                "模式", "写入(ms)", "读取(ms)", "写吞吐(ops/s)", "读吞吐(ops/s)", "堆内存(MB)");
        System.out.println(separator100dash);

        // 按照特定顺序显示结果
        String[] order = {
            "HashMap模式",
            "Caffeine缓存模式",
            "FastUtil模式",
            "RogueMap.OffHeap模式",
            "RogueMap.Mmap临时文件模式",
            "RogueMap.Mmap持久化模式",
            "MapDB.OffHeap模式",
            "MapDB.临时文件模式",
            "MapDB.持久化模式"
        };

        for (String key : order) {
            TestResult result = results.get(key);
            if (result != null) {
                long writeThroughput = result.writeTimeMs > 0 ?
                    (DATASET_SIZE * 1000L / result.writeTimeMs) : 0;
                long readThroughput = result.readTimeMs > 0 ?
                    (DATASET_SIZE * 1000L / result.readTimeMs) : 0;

                System.out.printf("%-30s %-15s %-15s %-20s %-20s %-15.2f%n",
                        result.modeName,
                        String.format("%,d", result.writeTimeMs),
                        String.format("%,d", result.readTimeMs),
                        String.format("%,d", writeThroughput),
                        String.format("%,d", readThroughput),
                        result.getHeapMemoryMB());
            }
        }

        // 2. 详细分析
        System.out.println("\n【详细分析】");
        System.out.println("\n以 HashMap 为基准的性能对比：");
        System.out.printf("  基准堆内存: %.2f MB%n", hashMapHeapMB);
        System.out.printf("  基准写入时间: %,d ms%n", hashMapWriteTime);
        System.out.printf("  基准读取时间: %,d ms%n%n", hashMapReadTime);

        for (String key : order) {
            TestResult result = results.get(key);
            if (result != null && result != hashMapResult) {
                double heapSavings = (1 - result.getHeapMemoryMB() / hashMapHeapMB) * 100;
                double writeSpeedRatio = (double) hashMapWriteTime / result.writeTimeMs;
                double readSpeedRatio = (double) hashMapReadTime / result.readTimeMs;

                System.out.printf("%s:%n", result.modeName);
                System.out.printf("  • 堆内存节省: %.1f%%%n", heapSavings);
                System.out.printf("  • 写入性能: %.2fx %s%n",
                    Math.abs(writeSpeedRatio),
                    writeSpeedRatio >= 1 ? "(更快)" : "(更慢)");
                System.out.printf("  • 读取性能: %.2fx %s%n",
                    Math.abs(readSpeedRatio),
                    readSpeedRatio >= 1 ? "(更快)" : "(更慢)");
                System.out.println();
            }
        }

        // 3. RogueMap vs MapDB 对比
        System.out.println("【RogueMap vs MapDB 对比】");

        TestResult rogueOffHeap = results.get("RogueMap.OffHeap模式");
        TestResult mapdbOffHeap = results.get("MapDB.OffHeap模式");
        if (rogueOffHeap != null && mapdbOffHeap != null) {
            System.out.println("\n1. OffHeap 模式对比：");
            compareResults(rogueOffHeap, mapdbOffHeap);
        }

        TestResult rogueMmapTemp = results.get("RogueMap.Mmap临时文件模式");
        TestResult mapdbTemp = results.get("MapDB.临时文件模式");
        if (rogueMmapTemp != null && mapdbTemp != null) {
            System.out.println("\n2. 临时文件模式对比：");
            compareResults(rogueMmapTemp, mapdbTemp);
        }

        TestResult rogueMmapPersist = results.get("RogueMap.Mmap持久化模式");
        TestResult mapdbPersist = results.get("MapDB.持久化模式");
        if (rogueMmapPersist != null && mapdbPersist != null) {
            System.out.println("\n3. 持久化模式对比：");
            compareResults(rogueMmapPersist, mapdbPersist);
        }

        // 4. 总结和建议
        System.out.println("\n【总结与建议】");
        System.out.println("\nRogueMap 的关键优势:");
        System.out.println("  ✓ 堆内存占用显著降低");
        System.out.println("  ✓ GC 压力大幅减少，适合大数据量场景");
        System.out.println("  ✓ 支持数据持久化，进程重启后快速恢复");
        System.out.println("  ✓ 可突破 JVM 堆内存限制");
        System.out.println("  ✓ 更灵活的序列化配置（支持Kryo等高性能序列化）");

        System.out.println("\nMapDB 的优势:");
        System.out.println("  ✓ 成熟稳定的生态系统");
        System.out.println("  ✓ 更多的数据结构支持");
        System.out.println("  ✓ 事务支持");

        System.out.println("\n适用场景:");
        System.out.println("  • 大数据量缓存（GB级别）");
        System.out.println("  • 需要持久化的KV存储");
        System.out.println("  • GC敏感的实时系统");
        System.out.println("  • 大对象存储场景");

        System.out.println("\n性能考虑:");
        System.out.println("  • 写入性能略低于HashMap（因序列化开销）");
        System.out.println("  • 读取性能接近HashMap");
        System.out.println("  • 整体性能可接受，内存优势明显");

        System.out.println("\n推荐选择:");
        System.out.println("  • 如果你需要极致的内存优化和性能，选择 RogueMap");
        System.out.println("  • 如果你需要复杂的数据结构和事务支持，选择 MapDB");

        System.out.println("\n" + separator100);
    }

    /**
     * 对比两个结果
     */
    private static void compareResults(TestResult result1, TestResult result2) {
        double heapRatio = (double) result1.heapMemory / result2.heapMemory;
        double writeSpeedRatio = (double) result2.writeTimeMs / result1.writeTimeMs;
        double readSpeedRatio = (double) result2.readTimeMs / result1.readTimeMs;

        System.out.printf("  %s vs %s:%n", result1.modeName, result2.modeName);
        System.out.printf("    堆内存: %s %.2f MB / %s %.2f MB (比例: %.2f)%n",
                result1.modeName, result1.getHeapMemoryMB(),
                result2.modeName, result2.getHeapMemoryMB(), heapRatio);
        System.out.printf("    写入性能: %s %,d ms / %s %,d ms (%s %.2fx %s)%n",
                result1.modeName, result1.writeTimeMs,
                result2.modeName, result2.writeTimeMs,
                result1.modeName,
                Math.abs(writeSpeedRatio),
                writeSpeedRatio >= 1 ? "更快" : "更慢");
        System.out.printf("    读取性能: %s %,d ms / %s %,d ms (%s %.2fx %s)%n",
                result1.modeName, result1.readTimeMs,
                result2.modeName, result2.readTimeMs,
                result1.modeName,
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
        final String modeName;
        final long heapMemory;      // 堆内存（字节）
        final long writeTimeMs;     // 写入耗时（毫秒）
        final long readTimeMs;      // 读取耗时（毫秒）

        TestResult(String modeName, long heapMemory, long writeTimeMs, long readTimeMs) {
            this.modeName = modeName;
            this.heapMemory = heapMemory;
            this.writeTimeMs = writeTimeMs;
            this.readTimeMs = readTimeMs;
        }

        double getHeapMemoryMB() {
            return heapMemory / 1024.0 / 1024.0;
        }
    }
}
