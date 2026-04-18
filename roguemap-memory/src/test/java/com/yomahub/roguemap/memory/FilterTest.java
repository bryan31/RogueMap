package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FilterTest {

    private static final String TEST_DIR = "target/test-filter";
    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(16))
            .searchMode(SearchMode.HYBRID)
            .build();

        // 插入带 importance 的测试数据
        memory.add("低优先级任务", mapOf("importance", "1"), "default");
        memory.add("普通任务", mapOf("importance", "5"), "default");
        memory.add("重要任务", mapOf("importance", "8"), "default");
        memory.add("紧急任务", mapOf("importance", "10"), "default");
        memory.add("无优先级任务", Collections.emptyMap(), "default");
        memory.add("非数字优先级", mapOf("importance", "high"), "default");
    }

    @AfterEach
    void tearDown() throws Exception { memory.close(); }

    @Test
    void eqFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.eq("5")).build());
        assertTrue(results.stream().allMatch(r -> "5".equals(r.getMetadata().get("importance"))));
    }

    @Test
    void gtFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.gt("5")).build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            return v != null && Double.parseDouble(v) > 5;
        }));
    }

    @Test
    void gteFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.gte("5")).build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            return v != null && Double.parseDouble(v) >= 5;
        }));
    }

    @Test
    void ltFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.lt("5")).build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            return v != null && Double.parseDouble(v) < 5;
        }));
    }

    @Test
    void lteFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.lte("5")).build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            return v != null && Double.parseDouble(v) <= 5;
        }));
    }

    @Test
    void inFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.in("8", "10")).build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            return "8".equals(v) || "10".equals(v);
        }));
    }

    @Test
    void betweenFilter() {
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.between("5", "8")).build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            if (v == null) return false;
            double d = Double.parseDouble(v);
            return d >= 5 && d <= 8;
        }));
    }

    @Test
    void nonNumericValueSilentlySkipped() {
        // gt("5") 不应匹配 importance="high"、importance=null
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", Filter.gt("5")).build());
        assertTrue(results.stream().noneMatch(r -> "high".equals(r.getMetadata().get("importance"))));
    }

    @Test
    void legacyStringFilterStillWorks() {
        // 旧写法 filter(key, value) 仍然有效
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder().filter("importance", "5").build());
        assertTrue(results.stream().allMatch(r -> "5".equals(r.getMetadata().get("importance"))));
    }

    @Test
    void multipleFiltersCombined() {
        // importance >= 5 AND importance <= 8
        List<MemoryResult> results = memory.search("任务", 10,
            SearchOptions.builder()
                .filter("importance", Filter.gte("5"))
                .filter("importance", Filter.lte("8"))
                .build());
        assertTrue(results.stream().allMatch(r -> {
            String v = r.getMetadata().get("importance");
            if (v == null) return false;
            double d = Double.parseDouble(v);
            return d >= 5 && d <= 8;
        }));
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }

    private static Map<String, String> mapOf(String... kvs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) m.put(kvs[i], kvs[i + 1]);
        return m;
    }
}
