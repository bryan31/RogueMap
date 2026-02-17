package com.yomahub.roguemap.set;

import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueSet 并发测试
 */
public class SetConcurrentTest {

    @Test
    public void testConcurrentAdd() throws Exception {
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .allocateSize(50 * 1024 * 1024L)
                .elementCodec(PrimitiveCodecs.LONG)
                .initialCapacity(10000)
                .build();

        try {
            int threadCount = 10;
            int operationsPerThread = 1000;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);

            AtomicInteger errorCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            set.add((long) threadId * operationsPerThread + i);
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            finishLatch.await();
            executor.shutdown();

            assertEquals(0, errorCount.get());
            assertEquals(threadCount * operationsPerThread, set.size());
        } finally {
            set.close();
        }
    }

    @Test
    public void testConcurrentContains() throws Exception {
        RogueSet<Integer> set = RogueSet.<Integer>mmap()
                .temporary()
                .allocateSize(50 * 1024 * 1024L)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .initialCapacity(10000)
                .build();

        try {
            // 预先写入数据
            int count = 10000;
            for (int i = 0; i < count; i++) {
                set.add(i);
            }

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);

            AtomicInteger errorCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < count; i++) {
                            if (!set.contains(i)) {
                                errorCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            finishLatch.await();
            executor.shutdown();

            assertEquals(0, errorCount.get());
        } finally {
            set.close();
        }
    }
}
