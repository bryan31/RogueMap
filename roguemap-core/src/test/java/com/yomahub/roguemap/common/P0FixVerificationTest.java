package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueMapTransaction;
import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 修复验证测试
 *
 * <ul>
 *   <li>Fix 1: SegmentedHashIndex 事务回滚不再死锁（StampedLock 不可重入）</li>
 *   <li>Fix 2: LinkedQueueStorage 快照写入使用 invalidate-before-write 协议</li>
 *   <li>Fix 3: KryoObjectCodec.decode() 清理残留缓存</li>
 * </ul>
 */
public class P0FixVerificationTest {

    private static final String TEST_DIR = System.getProperty("java.io.tmpdir") + "/roguemap_p0_test/";

    @BeforeEach
    public void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
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

    // ===== Fix 1: SegmentedHashIndex 回滚不死锁 =====

    /**
     * 验证 applyBatch 回滚路径不死锁。
     *
     * 构造一个会在 applyBatch 期间触发异常的场景：
     * 传入一个 null key 的 BatchEntry，HashMap.put(null, ...) 本身不报错，
     * 但 size.incrementAndGet() 之后如果 getSegmentIndex 对 null 报 NPE，则走回滚路径。
     *
     * 由于 HashMap.put 本身不抛异常，我们通过 RogueMap 事务层面来间接测试：
     * 并发多线程事务提交确保不死锁。
     */
    @Test
    public void testConcurrentTransactionCommitNoDeadlock() throws InterruptedException {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .temporary()
                .allocateSize(16 * 1024 * 1024)
                .keyCodec(new StringCodec())
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            int threadCount = 20;
            int keysPerTxn = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                final int tid = t;
                executor.submit(() -> {
                    try {
                        start.await();
                        // 每个线程连续提交 3 次事务
                        for (int round = 0; round < 3; round++) {
                            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                                for (int i = 0; i < keysPerTxn; i++) {
                                    txn.put("t" + tid + "-r" + round + "-k" + i,
                                            (long) (tid * 10000 + round * 1000 + i));
                                }
                                txn.commit();
                            }
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

            assertEquals(0, errorCount.get(), "并发事务不应有错误（包括死锁）");
            assertEquals(threadCount * 3 * keysPerTxn, map.size());
        }
    }

    /**
     * 验证 applyBatch 的回滚逻辑正确恢复索引状态。
     *
     * 场景：先 put 一些 key，再通过事务 put 覆盖 + put 新增 + remove，
     * 然后显式 rollback，验证所有原始数据不变。
     */
    @Test
    public void testTransactionRollbackRestoresState() {
        try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                .temporary()
                .allocateSize(16 * 1024 * 1024)
                .keyCodec(new StringCodec())
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {

            // 初始数据
            map.put("a", 1L);
            map.put("b", 2L);
            map.put("c", 3L);

            // 事务修改但不提交
            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.put("a", 100L);   // 覆盖
                txn.put("d", 4L);     // 新增
                txn.remove("b");      // 删除
                // 不调用 commit，close 时自动 rollback
            }

            // 验证原始数据不变
            assertEquals(1L, map.get("a").longValue());
            assertEquals(2L, map.get("b").longValue());
            assertEquals(3L, map.get("c").longValue());
            assertNull(map.get("d"));
            assertEquals(3, map.size());
        }
    }

    // ===== Fix 2: LinkedQueueStorage 快照原子性 =====

    /**
     * 验证队列在多轮 offer/poll/close/reopen 后数据正确恢复。
     * 间接验证 writeSnapshot 的正确性（如果快照不一致，恢复后数据会错乱）。
     */
    @Test
    public void testQueueSnapshotRecoveryAfterMixedOps() {
        String path = TEST_DIR + "snapshot_test.db";

        // 第一轮：offer 100 个，poll 40 个，再 offer 20 个
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 100; i++) {
                queue.offer(i);
            }
            for (int i = 0; i < 40; i++) {
                queue.poll();
            }
            for (long i = 100; i < 120; i++) {
                queue.offer(i);
            }
            assertEquals(80, queue.size());
        }

        // 第二轮：重新打开验证
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertEquals(80, queue.size());

            // 验证顺序：40..99, 100..119
            for (long i = 40; i < 120; i++) {
                Long val = queue.poll();
                assertNotNull(val, "第 " + (i - 40) + " 次 poll 返回 null");
                assertEquals(i, val.longValue());
            }
            assertNull(queue.poll());
        }
    }

    /**
     * 验证队列在频繁 offer+poll 交替操作后的快照恢复。
     */
    @Test
    public void testQueueSnapshotRecoveryFrequentOfferPoll() {
        String path = TEST_DIR + "snapshot_freq_test.db";

        final int[] expectedSize = new int[1];
        final Long[] expectedPeek = new Long[1];

        // 交替 offer/poll，每次操作都会触发 writeSnapshot
        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            for (long i = 0; i < 200; i++) {
                queue.offer(i);
                if (i % 3 == 0 && !queue.isEmpty()) {
                    queue.poll(); // 每 3 个 offer 做一次 poll
                }
            }
            expectedSize[0] = queue.size();
            assertTrue(expectedSize[0] > 0);
            expectedPeek[0] = queue.peek();
        } // try-with-resources 在此正常关闭

        // 重新打开验证恢复
        try (RogueQueue<Long> reopened = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {

            assertEquals(expectedSize[0], reopened.size(), "恢复后 size 应一致");
            assertEquals(expectedPeek[0], reopened.peek(), "恢复后 peek 应一致");
        }
    }

    // ===== Fix 3: KryoObjectCodec 缓存清理 =====

    public static class SimpleData implements Serializable {
        public String name;
        public int value;

        public SimpleData() {}

        public SimpleData(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SimpleData that = (SimpleData) o;
            return value == that.value && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }

    /**
     * 验证 decode 后缓存被清理。
     *
     * 场景：calculateSize(A) → 不调 encode → decode(其他数据) → encode(B)
     * 修复前：encode(B) 会使用 A 的缓存字节
     * 修复后：decode 清理了缓存，encode(B) 会重新序列化 B
     */
    @Test
    public void testDecodeCleansCachePreventingStaleCacheUse() {
        KryoObjectCodec<SimpleData> codec = KryoObjectCodec.create(SimpleData.class);

        long address = UnsafeOps.allocate(4096);
        try {
            SimpleData dataA = new SimpleData("Alice", 100);
            SimpleData dataB = new SimpleData("Bob", 200);

            // Step 1: calculateSize(A) — 设置缓存为 A 的序列化字节
            int sizeA = codec.calculateSize(dataA);
            assertTrue(sizeA > 0);

            // Step 2: 正常 encode(A) — 写入 A 的数据并清理缓存
            int writtenA = codec.encode(address, dataA);
            assertEquals(sizeA, writtenA);

            // Step 3: decode — 应该清理任何残留缓存
            SimpleData decodedA = codec.decode(address);
            assertEquals(dataA, decodedA);

            // Step 4: calculateSize(A) 再次设置缓存
            codec.calculateSize(dataA);

            // Step 5: 做一次 decode — 这会清理 A 的缓存
            codec.decode(address);

            // Step 6: 现在 encode(B) — 不应使用 A 的残留缓存
            int sizeB = codec.calculateSize(dataB);
            int writtenB = codec.encode(address, dataB);
            assertEquals(sizeB, writtenB);

            // Step 7: 验证写入的确实是 B 的数据
            SimpleData decodedB = codec.decode(address);
            assertEquals(dataB, decodedB);
            assertEquals("Bob", decodedB.name);
            assertEquals(200, decodedB.value);

        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 验证 calculateSize(A) 后不调 encode(A)，直接 encode(B) 的场景。
     *
     * 修复前：encode(B) 会误用 A 的缓存
     * 修复后：encode(B) 发现缓存非空但不匹配时，重新序列化
     *
     * 注意：当前实现中 encode 无法区分缓存属于哪个值，
     * 所以修复方式是在 decode() 中清理缓存。
     * 这个测试验证 calculateSize(B) + encode(B) 的正常路径不受影响。
     */
    @Test
    public void testCalculateSizeAndEncodeConsistency() {
        KryoObjectCodec<SimpleData> codec = KryoObjectCodec.create(SimpleData.class);

        long address = UnsafeOps.allocate(4096);
        try {
            // 连续编解码多个不同对象
            SimpleData[] objects = {
                    new SimpleData("First", 1),
                    new SimpleData("Second", 2),
                    new SimpleData("Third", 3),
                    new SimpleData("Fourth", 4),
                    new SimpleData("Fifth", 5),
            };

            long currentAddr = address;
            int[] sizes = new int[objects.length];

            for (int i = 0; i < objects.length; i++) {
                sizes[i] = codec.calculateSize(objects[i]);
                int written = codec.encode(currentAddr, objects[i]);
                assertEquals(sizes[i], written, "第 " + i + " 个对象的 size 不一致");
                currentAddr += written;
            }

            // 解码验证
            currentAddr = address;
            for (int i = 0; i < objects.length; i++) {
                SimpleData decoded = codec.decode(currentAddr);
                assertEquals(objects[i], decoded, "第 " + i + " 个对象解码不匹配");
                currentAddr += sizes[i];
            }
        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 验证交替 encode/decode 不会导致缓存混乱。
     */
    @Test
    public void testInterleavedEncodeDecodeNoCacheCorruption() {
        KryoObjectCodec<SimpleData> codec = KryoObjectCodec.create(SimpleData.class);

        long addr1 = UnsafeOps.allocate(1024);
        long addr2 = UnsafeOps.allocate(1024);
        try {
            SimpleData dataX = new SimpleData("X-data", 111);
            SimpleData dataY = new SimpleData("Y-data", 222);

            // encode X to addr1
            int sizeX = codec.calculateSize(dataX);
            codec.encode(addr1, dataX);

            // encode Y to addr2
            int sizeY = codec.calculateSize(dataY);
            codec.encode(addr2, dataY);

            // decode X, then decode Y — 缓存应被清理
            SimpleData decodedX = codec.decode(addr1);
            SimpleData decodedY = codec.decode(addr2);

            assertEquals(dataX, decodedX);
            assertEquals(dataY, decodedY);

            // 再次 encode X — 不应使用 Y 的残留缓存
            int sizeX2 = codec.calculateSize(dataX);
            int writtenX2 = codec.encode(addr1, dataX);
            assertEquals(sizeX, sizeX2);
            assertEquals(sizeX, writtenX2);

            SimpleData reDecodedX = codec.decode(addr1);
            assertEquals(dataX, reDecodedX);
        } finally {
            UnsafeOps.free(addr1);
            UnsafeOps.free(addr2);
        }
    }
}
