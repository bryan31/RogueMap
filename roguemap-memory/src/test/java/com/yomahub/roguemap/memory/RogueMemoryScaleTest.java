package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.embedding.EmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规模验证测试 - 验证百万级记录下的堆内存使用
 *
 * 目标:
 * - 100万条记录
 * - 1536维向量
 * - 堆内存 < 1GB (相比原架构的 20GB+ 大幅降低)
 */
class RogueMemoryScaleTest {

    private static final String TEST_DIR = "target/test-scale";
    private static final int RECORD_COUNT = 1_000_000;
    private static final int DIMENSION = 1536;

    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (memory != null) memory.close();
    }

    @Test
    void millionRecordsLowHeap() throws Exception {
        System.out.println("=== RogueMemory 百万级规模测试 ===");
        System.out.println("记录数: " + RECORD_COUNT);
        System.out.println("向量维度: " + DIMENSION);

        // 创建 mock embedding provider
        MockEmbeddingProvider embeddingProvider = new MockEmbeddingProvider(DIMENSION);

        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(embeddingProvider)
            .searchMode(SearchMode.HYBRID)
            .allocateSize(8L * 1024 * 1024 * 1024) // 8GB 文件
            .build();

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        Thread.sleep(100);
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("初始堆内存: " + (heapBefore / 1024 / 1024) + " MB");

        // 添加 100万条记录
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < RECORD_COUNT; i++) {
            String content = "测试内容 " + i;
            Map<String, String> meta = new HashMap<>();
            meta.put("index", String.valueOf(i));
            memory.add(content, meta, "test");

            if ((i + 1) % 100000 == 0) {
                System.out.println("已添加: " + (i + 1) + " 条记录");
            }
        }
        long addTime = System.currentTimeMillis() - startTime;
        System.out.println("添加耗时: " + addTime + " ms");

        System.gc();
        Thread.sleep(100);
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();
        long heapUsed = heapAfter - heapBefore;
        System.out.println("添加后堆内存: " + (heapAfter / 1024 / 1024) + " MB");
        System.out.println("堆内存增长: " + (heapUsed / 1024 / 1024) + " MB");

        // 验证堆内存 < 1GB
        assertTrue(heapUsed < 1024L * 1024 * 1024,
            "堆内存使用应 < 1GB, 实际: " + (heapUsed / 1024 / 1024) + " MB");

        // 搜索测试
        startTime = System.currentTimeMillis();
        List<MemoryResult> results = memory.search("测试内容 500000", 10);
        long searchTime = System.currentTimeMillis() - startTime;
        System.out.println("搜索耗时: " + searchTime + " ms");
        System.out.println("搜索结果数: " + results.size());

        assertFalse(results.isEmpty(), "应该返回搜索结果");

        System.out.println("=== 测试完成 ===");
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    static class MockEmbeddingProvider implements EmbeddingProvider {
        private final int dimension;
        private final Random random = new Random(42);

        MockEmbeddingProvider(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = random.nextFloat();
            }
            return vector;
        }

        @Override
        public int getDimension() {
            return dimension;
        }
    }
}
