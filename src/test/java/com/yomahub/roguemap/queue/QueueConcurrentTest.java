package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueQueue 并发测试
 */
public class QueueConcurrentTest {

    @Test
    public void testLinkedQueueConcurrentOffer() throws Exception {
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .allocateSize(50 * 1024 * 1024L)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
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
                            queue.offer((long) threadId * operationsPerThread + i);
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
            assertEquals(threadCount * operationsPerThread, queue.size());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testCircularQueueConcurrentReadWrite() throws Exception {
        int capacity = 1000;
        RogueQueue<Integer> queue = RogueQueue.<Integer>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .circular(capacity, 16)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            // 先填满队列
            for (int i = 0; i < capacity; i++) {
                queue.offer(i);
            }

            int threadCount = 4; // 2读 + 2写
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);

            AtomicInteger readCount = new AtomicInteger(0);
            AtomicInteger writeCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            // 2个读线程
            for (int t = 0; t < 2; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 5000; i++) {
                            Integer value = queue.poll();
                            if (value != null) {
                                readCount.incrementAndGet();
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

            // 2个写线程
            for (int t = 0; t < 2; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 5000; i++) {
                            if (queue.offer(10000 + threadId * 5000 + i)) {
                                writeCount.incrementAndGet();
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
            System.out.printf("CircularQueue并发测试: 读取 %d, 写入 %d%n",
                    readCount.get(), writeCount.get());
        } finally {
            queue.close();
        }
    }
}
