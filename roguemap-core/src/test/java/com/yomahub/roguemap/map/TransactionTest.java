package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueMapTransaction;
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
 * 事务功能测试
 */
public class TransactionTest {

    private static final String TEST_DIR = System.getProperty("java.io.tmpdir") + "/roguemap_txn_test/";

    @BeforeEach
    public void setUp() {
        new File(TEST_DIR).mkdirs();
    }

    @AfterEach
    public void tearDown() {
        File dir = new File(TEST_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    private RogueMap<String, Long> newMap(String name) {
        return RogueMap.<String, Long>mmap()
                .temporary()
                .allocateSize(16 * 1024 * 1024)
                .keyCodec(new StringCodec())
                .valueCodec(PrimitiveCodecs.LONG)
                .build();
    }

    // ===== 1. 正常 commit：多个 put/remove 后 commit，所有变更生效 =====

    @Test
    public void testCommitAppliesAllChanges() {
        try (RogueMap<String, Long> map = newMap("commit")) {
            map.put("existing", 999L);

            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.put("a", 1L);
                txn.put("b", 2L);
                txn.put("c", 3L);
                txn.remove("existing");
                txn.commit();
            }

            assertEquals(1L, map.get("a").longValue());
            assertEquals(2L, map.get("b").longValue());
            assertEquals(3L, map.get("c").longValue());
            assertNull(map.get("existing"), "remove 操作应生效");
            assertEquals(3, map.size());
        }
    }

    // ===== 2. rollback：commit 前关闭，所有变更不可见 =====

    @Test
    public void testRollbackDiscardsChanges() {
        try (RogueMap<String, Long> map = newMap("rollback")) {
            map.put("original", 100L);

            // 使用 try-with-resources 但不调用 commit
            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.put("new-key", 999L);
                txn.put("original", 200L);
                // 不调用 commit，close 时自动 rollback
            }

            // 变更不应生效
            assertNull(map.get("new-key"), "rollback 后 new-key 不应存在");
            assertEquals(100L, map.get("original").longValue(), "rollback 后 original 不应被修改");
        }
    }

    // ===== 3. 显式 rollback =====

    @Test
    public void testExplicitRollback() {
        try (RogueMap<String, Long> map = newMap("explicit_rollback")) {
            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.put("x", 1L);
                txn.put("y", 2L);
                txn.rollback(); // 显式回滚
            }

            assertNull(map.get("x"));
            assertNull(map.get("y"));
            assertEquals(0, map.size());
        }
    }

    // ===== 4. 事务隔离：事务 A 提交前，外部读不到 A 的写入 =====

    @Test
    public void testIsolation() throws InterruptedException {
        try (RogueMap<String, Long> map = newMap("isolation")) {
            CountDownLatch txnStarted = new CountDownLatch(1);
            CountDownLatch readDone = new CountDownLatch(1);
            CountDownLatch txnCommitted = new CountDownLatch(1);

            // 事务线程：写入 → 等待外部读 → commit
            Thread txnThread = new Thread(() -> {
                try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                    txn.put("isolated-key", 42L);
                    txnStarted.countDown(); // 通知外部已写入（未 commit）
                    readDone.await();       // 等待外部读完
                    txn.commit();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    txnCommitted.countDown();
                }
            });

            txnThread.start();
            txnStarted.await(); // 等事务写入缓冲区

            // 主线程：在事务 commit 之前读取，应看不到未提交的值
            assertNull(map.get("isolated-key"), "事务提交前，外部不应看到未提交数据");
            readDone.countDown();

            txnCommitted.await();

            // commit 之后应可读
            assertEquals(42L, map.get("isolated-key").longValue());
        }
    }

    // ===== 5. 并发事务无死锁（多线程分别提交不同 key 子集）=====

    @Test
    public void testConcurrentTransactionsNoDeadlock() throws InterruptedException {
        try (RogueMap<String, Long> map = newMap("concurrent_txn")) {
            int threadCount = 10;
            int keysPerTxn = 20;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                final int tid = t;
                executor.submit(() -> {
                    try {
                        start.await();
                        try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                            for (int i = 0; i < keysPerTxn; i++) {
                                txn.put("thread-" + tid + "-key-" + i, (long) (tid * 1000 + i));
                            }
                            txn.commit();
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
            assertEquals(threadCount * keysPerTxn, map.size(), "所有事务的写入均应可见");
        }
    }

    // ===== 6. 同一 key 在事务内多次操作，以最后一次为准 =====

    @Test
    public void testLastWriteWinsInSameTransaction() {
        try (RogueMap<String, Long> map = newMap("last_write")) {
            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.put("key", 1L);
                txn.put("key", 2L);
                txn.put("key", 3L);
                txn.commit();
            }
            assertEquals(3L, map.get("key").longValue(), "同一 key 多次 put，以最后一次为准");
        }
    }

    @Test
    public void testPutThenRemoveInSameTxn() {
        try (RogueMap<String, Long> map = newMap("put_then_remove")) {
            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.put("temp", 999L);
                txn.remove("temp"); // 后操作覆盖
                txn.commit();
            }
            assertNull(map.get("temp"), "put 后 remove 应生效，key 不存在");
        }
    }

    // ===== 7. 空事务 commit 不报错 =====

    @Test
    public void testEmptyTransactionCommit() {
        try (RogueMap<String, Long> map = newMap("empty_txn")) {
            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.commit(); // 无操作，不应抛出异常
            }
            assertEquals(0, map.size());
        }
    }

    // ===== 8. 已提交的事务不能再操作 =====

    @Test
    public void testUseAfterCommitThrows() {
        try (RogueMap<String, Long> map = newMap("use_after_commit")) {
            assertThrows(IllegalStateException.class, () -> {
                try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                    txn.put("k", 1L);
                    txn.commit();
                    txn.put("k2", 2L); // 应抛出 IllegalStateException
                }
            });
        }
    }

    // ===== 9. 对已存在 key 的 remove =====

    @Test
    public void testRemoveExistingKey() {
        try (RogueMap<String, Long> map = newMap("remove_existing")) {
            map.put("a", 1L);
            map.put("b", 2L);
            map.put("c", 3L);

            try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                txn.remove("a");
                txn.remove("c");
                txn.commit();
            }

            assertNull(map.get("a"));
            assertEquals(2L, map.get("b").longValue());
            assertNull(map.get("c"));
            assertEquals(1, map.size());
        }
    }

    // ===== 10. 不支持 primitiveIndex 的 beginTransaction =====

    @Test
    public void testPrimitiveIndexTransactionUnsupported() {
        try (RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .allocateSize(1024 * 1024)
                .primitiveIndex()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertThrows(UnsupportedOperationException.class, map::beginTransaction);
        }
    }

    // ===== 11. 事务 + checkpoint 持久化 =====

    @Test
    public void testTransactionWithCheckpoint() throws Exception {
        String path = TEST_DIR + "txn_checkpoint.db";
        try {
            // 写入并 checkpoint
            try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                    .persistent(path)
                    .allocateSize(4 * 1024 * 1024)
                    .keyCodec(new StringCodec())
                    .valueCodec(PrimitiveCodecs.LONG)
                    .build()) {

                try (RogueMapTransaction<String, Long> txn = map.beginTransaction()) {
                    for (int i = 0; i < 50; i++) {
                        txn.put("txn-key-" + i, (long) i);
                    }
                    txn.commit();
                }
                map.checkpoint();
            }

            // 重新打开验证
            try (RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
                    .persistent(path)
                    .allocateSize(4 * 1024 * 1024)
                    .keyCodec(new StringCodec())
                    .valueCodec(PrimitiveCodecs.LONG)
                    .build()) {

                assertEquals(50, map.size());
                for (int i = 0; i < 50; i++) {
                    assertEquals((long) i, map.get("txn-key-" + i).longValue());
                }
            }
        } finally {
            new File(path).delete();
        }
    }
}
