package com.yomahub.roguemap.list;

import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueList 并发测试
 */
public class ListConcurrentTest {

    @Test
    public void testConcurrentAddLast() throws Exception {
        RogueList<Long> list = RogueList.<Long>mmap()
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
                            list.addLast((long) threadId * operationsPerThread + i);
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
            assertEquals(threadCount * operationsPerThread, list.size());
        } finally {
            list.close();
        }
    }

    @Test
    public void testConcurrentRead() throws Exception {
        RogueList<Integer> list = RogueList.<Integer>mmap()
                .temporary()
                .allocateSize(50 * 1024 * 1024L)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .initialCapacity(10000)
                .build();

        try {
            // 预先写入数据
            int count = 10000;
            for (int i = 0; i < count; i++) {
                list.addLast(i);
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
                            int value = list.get(i);
                            if (value != i) {
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
            list.close();
        }
    }
}
