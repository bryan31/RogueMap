package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemorySearchTest {

    private static final String TEST_DIR = "target/test-memory-search";
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
    }

    @AfterEach
    void tearDown() throws Exception { memory.close(); }

    @Test
    void searchReturnsResults() {
        memory.add("我有一件红衣服");
        memory.add("今天天气很好");
        memory.add("明天要去开会");

        List<MemoryResult> results = memory.search("衣服", 3);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getScore() > 0);
    }

    @Test
    void searchTopKRespected() {
        for (int i = 0; i < 10; i++) {
            memory.add("记忆内容 " + i);
        }
        List<MemoryResult> results = memory.search("记忆内容", 3);
        assertTrue(results.size() <= 3);
    }

    @Test
    void searchFiltersByNamespace() {
        memory.add("session1的记忆", Collections.emptyMap(), "session-1");
        memory.add("session2的记忆", Collections.emptyMap(), "session-2");

        List<MemoryResult> results = memory.search("记忆", 5,
            SearchOptions.builder().namespace("session-1").build());

        assertTrue(results.stream().allMatch(r -> "session-1".equals(r.getNamespace())));
    }

    @Test
    void searchFiltersByMetadata() {
        memory.add("用户A的记忆", mapOf("userId", "A"), "default");
        memory.add("用户B的记忆", mapOf("userId", "B"), "default");

        List<MemoryResult> results = memory.search("记忆", 5,
            SearchOptions.builder().filter("userId", "A").build());

        assertTrue(results.stream().allMatch(r -> "A".equals(r.getMetadata().get("userId"))));
    }

    @Test
    void deletedEntriesNotInResults() {
        String id = memory.add("要被删除的记忆");
        memory.delete(id);

        List<MemoryResult> results = memory.search("删除", 5);
        assertTrue(results.stream().noneMatch(r -> r.getId().equals(id)));
    }

    @Test
    void vectorOnlyModeWorks() {
        RogueMemory vectorOnly = RogueMemory.mmap()
            .persistent(TEST_DIR + "/vec")
            .embeddingProvider(new MockEmbeddingProvider(16))
            .searchMode(SearchMode.VECTOR_ONLY)
            .build();

        vectorOnly.add("向量搜索测试内容");
        List<MemoryResult> results = vectorOnly.search("测试内容", 3);
        assertFalse(results.isEmpty());
        vectorOnly.close();
    }

    @Test
    void keywordOnlyModeWorks() {
        RogueMemory keywordOnly = RogueMemory.mmap()
            .persistent(TEST_DIR + "/kw")
            .searchMode(SearchMode.KEYWORD_ONLY)
            .build();

        keywordOnly.add("关键词搜索测试内容");
        List<MemoryResult> results = keywordOnly.search("关键词搜索", 3);
        assertFalse(results.isEmpty());
        keywordOnly.close();
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
