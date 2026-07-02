package com.yomahub.roguemap.benchmark;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
