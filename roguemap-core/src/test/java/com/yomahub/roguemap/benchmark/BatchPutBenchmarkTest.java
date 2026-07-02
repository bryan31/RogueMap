package com.yomahub.roguemap.benchmark;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * putAll vs 循环 put 吞吐对比（非门禁：只校验数据正确性，不断言耗时）
 */
public class BatchPutBenchmarkTest {

    private static final int N = 100_000;

    private RogueMap<String, String> newMap() {
        return RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(64 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
    }

    @Test
    public void compareLoopPutVsPutAll() {
        // 预生成数据，排除数据构造开销
        Map<String, String> data = new HashMap<>(N * 2);
        for (int i = 0; i < N; i++) {
            data.put("bench-key-" + i, "bench-value-" + i);
        }

        long loopNanos;
        RogueMap<String, String> map1 = newMap();
        try {
            long t0 = System.nanoTime();
            for (Map.Entry<String, String> e : data.entrySet()) {
                map1.put(e.getKey(), e.getValue());
            }
            loopNanos = System.nanoTime() - t0;
            assertEquals(N, map1.size());
        } finally {
            map1.close();
        }

        long batchNanos;
        RogueMap<String, String> map2 = newMap();
        try {
            long t0 = System.nanoTime();
            map2.putAll(data);
            batchNanos = System.nanoTime() - t0;
            assertEquals(N, map2.size());
        } finally {
            map2.close();
        }

        System.out.printf("[BatchPutBenchmark] %,d 条 | 循环 put: %,d ms | putAll: %,d ms | 加速比: %.2fx%n",
                N, loopNanos / 1_000_000, batchNanos / 1_000_000,
                (double) loopNanos / batchNanos);
    }

    /**
     * 多线程写竞争基准：对比"每线程循环 put 一批"与"每线程 putAll 一批"。
     *
     * <p>键空间不相交（重点是对 allocator CAS 与段锁的竞争压力，而非键冲突）。
     * 断言仅校验各 map 终态 size 正确，不断言耗时。
     */
    @Test
    public void multiThreadCompare() throws InterruptedException {
        int threads = 6;
        int perThread = N / threads; // 总量约 10 万
        // 每线程预生成的不相交键空间
        Map<String, String>[] perThreadData = new Map[threads];
        for (int t = 0; t < threads; t++) {
            perThreadData[t] = new HashMap<>(perThread * 2);
            for (int i = 0; i < perThread; i++) {
                perThreadData[t].put("mt-" + t + "-key-" + i, "mt-" + t + "-value-" + i);
            }
        }

        // 1) 循环 put：每线程独立循环写入自己的批次
        long loopNanos;
        RogueMap<String, String> map1 = newMap();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        Map<String, String> d = perThreadData[tid];
                        for (Map.Entry<String, String> e : d.entrySet()) {
                            map1.put(e.getKey(), e.getValue());
                        }
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            long t0 = System.nanoTime();
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS));
            loopNanos = System.nanoTime() - t0;
            pool.shutdownNow();
            assertEquals(threads * perThread, map1.size());
        } finally {
            map1.close();
        }

        // 2) putAll：每线程一次性 putAll 自己的批次
        long batchNanos;
        RogueMap<String, String> map2 = newMap();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        map2.putAll(perThreadData[tid]);
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            long t0 = System.nanoTime();
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS));
            batchNanos = System.nanoTime() - t0;
            pool.shutdownNow();
            assertEquals(threads * perThread, map2.size());
        } finally {
            map2.close();
        }

        System.out.printf("[BatchPutBenchmark-MT] %d 线程 × %,d 条 | 循环 put: %,d ms | putAll: %,d ms | 加速比: %.2fx%n",
                threads, perThread, loopNanos / 1_000_000, batchNanos / 1_000_000,
                (double) loopNanos / batchNanos);
    }
}
