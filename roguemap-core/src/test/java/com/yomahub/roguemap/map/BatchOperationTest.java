package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueMap 批量 API（putAll/getAll）功能测试
 */
public class BatchOperationTest {

    private static final String TEST_FILE = "target/test-batch-persist.db";

    @BeforeEach
    public void setUp() {
        deleteTestFile();
    }

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

    private RogueMap<String, String> newTempMap() {
        return RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
    }

    // ========== putAll 基本功能 ==========

    @Test
    public void testPutAllBasic() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                batch.put("key" + i, "value" + i);
            }
            map.putAll(batch);

            assertEquals(100, map.size());
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, map.get("key" + i));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllOverwriteFreesOldMemory() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch1 = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                batch1.put("key" + i, "old-value-" + i);
            }
            map.putAll(batch1);
            long deadBefore = map.getMetrics().getDeadBytes();

            Map<String, String> batch2 = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                batch2.put("key" + i, "new-value-" + i);
            }
            map.putAll(batch2);

            assertEquals(50, map.size());
            for (int i = 0; i < 50; i++) {
                assertEquals("new-value-" + i, map.get("key" + i));
            }
            // 旧值内存已释放（计入 dead bytes）
            assertTrue(map.getMetrics().getDeadBytes() > deadBefore);
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllWithTTL() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            batch.put("ttl-key1", "v1");
            batch.put("ttl-key2", "v2");
            map.putAll(batch, 300, TimeUnit.MILLISECONDS);

            assertEquals("v1", map.get("ttl-key1"));
            assertEquals("v2", map.get("ttl-key2"));

            Thread.sleep(500);

            assertNull(map.get("ttl-key1"));
            assertNull(map.get("ttl-key2"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllEmptyIsNoop() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.putAll(new HashMap<>());
            assertEquals(0, map.size());
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllNullMapThrows() {
        RogueMap<String, String> map = newTempMap();
        try {
            assertThrows(IllegalArgumentException.class, () -> map.putAll(null));
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllNullKeyRejectsWholeBatch() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            batch.put("ok-key", "v");
            batch.put(null, "v2");

            assertThrows(IllegalArgumentException.class, () -> map.putAll(batch));

            // 校验先于分配：整批拒绝，无任何条目写入
            assertEquals(0, map.size());
            assertNull(map.get("ok-key"));
        } finally {
            map.close();
        }
    }

    // ========== getAll ==========

    @Test
    public void testGetAllRoundtrip() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                batch.put("key" + i, "value" + i);
            }
            map.putAll(batch);

            Map<String, String> got = map.getAll(batch.keySet());
            assertEquals(100, got.size());
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, got.get("key" + i));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllMissingKeysOmitted() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("exists", "v");

            Map<String, String> got = map.getAll(Arrays.asList("exists", "missing1", "missing2"));

            assertEquals(1, got.size());
            assertEquals("v", got.get("exists"));
            assertFalse(got.containsKey("missing1"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllNullElementsSkipped() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("k", "v");

            Map<String, String> got = map.getAll(new ArrayList<>(Arrays.asList("k", null)));

            assertEquals(1, got.size());
            assertEquals("v", got.get("k"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllExpiredKeysOmitted() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("eternal", "v1");
            map.put("mortal", "v2", 200, TimeUnit.MILLISECONDS);

            Thread.sleep(400);

            Map<String, String> got = map.getAll(Arrays.asList("eternal", "mortal"));
            assertEquals(1, got.size());
            assertEquals("v1", got.get("eternal"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllNullCollectionThrows() {
        RogueMap<String, String> map = newTempMap();
        try {
            assertThrows(IllegalArgumentException.class, () -> map.getAll(null));
        } finally {
            map.close();
        }
    }

    // ========== 索引模式矩阵 ==========

    private void verifyBatchRoundtrip(RogueMap<String, String> map) {
        Map<String, String> batch = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            batch.put("key" + i, "value" + i);
        }
        map.putAll(batch);
        assertEquals(200, map.size());

        Map<String, String> got = map.getAll(batch.keySet());
        assertEquals(200, got.size());
        for (int i = 0; i < 200; i++) {
            assertEquals("value" + i, got.get("key" + i));
        }
    }

    @Test
    public void testBatchWithBasicIndex() {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .basicIndex()
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        try {
            verifyBatchRoundtrip(map);
        } finally {
            map.close();
        }
    }

    @Test
    public void testBatchWithLowHeapIndex() {
        // lowHeapIndex() 内部用 == 校验编解码器单例（createNewIndex），必须传 StringCodec.INSTANCE
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .lowHeapIndex()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();
        try {
            verifyBatchRoundtrip(map);
        } finally {
            map.close();
        }
    }

    @Test
    public void testBatchWithPrimitiveIndex() {
        // LongPrimitiveIndex 用 0L 作 EMPTY_KEY 哨兵，键从 1 起以避开哨兵值（单键 put(0L) 同样会抛）
        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .primitiveIndex()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();
        try {
            Map<Long, Long> batch = new HashMap<>();
            for (long i = 1; i <= 200; i++) {
                batch.put(i, i * 10);
            }
            map.putAll(batch);
            assertEquals(200, map.size());

            Map<Long, Long> got = map.getAll(batch.keySet());
            assertEquals(200, got.size());
            assertEquals(Long.valueOf(50L), got.get(5L));
            assertEquals(Long.valueOf(1990L), got.get(199L));
        } finally {
            map.close();
        }
    }

    // ========== 持久化 ==========

    @Test
    public void testPutAllPersistence() {
        Map<String, String> batch = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            batch.put("pk" + i, "pv" + i);
        }

        RogueMap<String, String> map1 = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        map1.putAll(batch);
        map1.close();

        RogueMap<String, String> map2 = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        try {
            assertEquals(300, map2.size());
            Map<String, String> got = map2.getAll(batch.keySet());
            assertEquals(300, got.size());
            assertEquals("pv123", got.get("pk123"));
        } finally {
            map2.close();
        }
    }

    // ========== 并发 ==========

    @Test
    public void testConcurrentPutAllAndGet() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            int threads = 4;
            int rounds = 20;
            int keysPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        for (int round = 0; round < rounds; round++) {
                            Map<String, String> batch = new HashMap<>();
                            for (int i = 0; i < keysPerThread; i++) {
                                batch.put("t" + tid + "-k" + i, "v" + round + "-" + i);
                            }
                            map.putAll(batch);
                            // 写后立即读自己的键，验证读写交错安全
                            Map<String, String> got = map.getAll(batch.keySet());
                            if (got.size() != keysPerThread) {
                                throw new IllegalStateException(
                                        "线程 " + tid + " 第 " + round + " 轮读到 " + got.size() + " 条");
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(120, java.util.concurrent.TimeUnit.SECONDS), "并发测试超时");
            assertNull(error.get(), "并发执行出现异常: " + error.get());

            // 各线程键空间不相交，终态每线程各 keysPerThread 条
            assertEquals(threads * keysPerThread, map.size());
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < keysPerThread; i++) {
                    assertEquals("v" + (rounds - 1) + "-" + i, map.get("t" + t + "-k" + i));
                }
            }
        } finally {
            pool.shutdownNow();
            map.close();
        }
    }

    /**
     * 多线程混合 putAll 与单键 put 对同一组键并发覆写。
     *
     * <p>不断言具体值（多次覆写谁赢不确定），只断言：
     * <ul>
     *   <li>全程无异常</li>
     *   <li>终态每个键都可读且值合法（属于已知写入集合）</li>
     *   <li>size 等于键空间大小（不会因并发导致索引泄漏或重复计数）</li>
     * </ul>
     * 参考 {@link ConcurrentSameKeyTest} 的风格。
     */
    @Test
    public void testConcurrentPutAllAndPutSameKey() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int keyCount = 200;
            int rounds = 50;
            CountDownLatch latch = new CountDownLatch(8);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < 8; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        Random rnd = new Random(tid);
                        for (int round = 0; round < rounds; round++) {
                            // 每轮随机选 50 个键，整批 putAll
                            Map<String, String> batch = new HashMap<>();
                            for (int i = 0; i < 50; i++) {
                                String k = "shared-k" + rnd.nextInt(keyCount);
                                batch.put(k, "t" + tid + "-r" + round + "-v" + i);
                            }
                            if ((round & 1) == 0) {
                                map.putAll(batch);
                            } else {
                                // 单键 put 路径
                                for (Map.Entry<String, String> e : batch.entrySet()) {
                                    map.put(e.getKey(), e.getValue());
                                }
                            }
                            // 读校验：随机读一个键，确认是合法值（不抛异常、能解码）
                            String probe = map.get("shared-k" + rnd.nextInt(keyCount));
                            if (probe != null && !probe.startsWith("t")) {
                                throw new IllegalStateException("线程 " + tid + " 读到非法值: " + probe);
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(120, TimeUnit.SECONDS), "并发同键测试超时");
            assertNull(error.get(), "并发执行出现异常: " + error.get());

            // 终态：恰好 keyCount 个键，每个键可读
            assertEquals(keyCount, map.size(), "并发覆写后 size 应等于键空间大小");
            for (int i = 0; i < keyCount; i++) {
                String v = map.get("shared-k" + i);
                assertNotNull(v, "键 shared-k" + i + " 不可读");
                assertTrue(v.startsWith("t"), "键 shared-k" + i + " 值非法: " + v);
            }
        } finally {
            pool.shutdownNow();
            map.close();
        }
    }
}
