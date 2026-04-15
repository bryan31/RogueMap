package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryApiEnhancementTest {

    private static final String TEST_DIR = "target/test-memory-api-enhancement";
    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        memory.close();
    }

    // ===== exists =====

    @Test
    void existsReturnsFalseForUnknownId() {
        assertFalse(memory.exists("nonexistent-id"));
    }

    @Test
    void existsReturnsTrueAfterAdd() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        assertTrue(memory.exists(id));
    }

    @Test
    void existsReturnsFalseAfterDelete() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        memory.delete(id);
        assertFalse(memory.exists(id));
    }

    @Test
    void existsWithNamespaceReturnsTrueWhenMatches() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        assertTrue(memory.exists(id, "ns1"));
    }

    @Test
    void existsWithNamespaceReturnsFalseWhenNamespaceMismatches() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        assertFalse(memory.exists(id, "ns2"));
    }

    @Test
    void existsWithNamespaceReturnsFalseForUnknownId() {
        assertFalse(memory.exists("nonexistent-id", "ns1"));
    }

    // ===== add with external id =====

    @Test
    void addWithExternalIdStoresEntry() {
        String customId = "my-custom-id-001";
        String returned = memory.add(customId, "外部指定id的内容",
            Collections.singletonMap("source", "llm"), "agent-ns");
        assertEquals(customId, returned);

        MemoryEntry entry = memory.get(customId);
        assertNotNull(entry);
        assertEquals("外部指定id的内容", entry.getContent());
        assertEquals("agent-ns", entry.getNamespace());
        assertEquals("llm", entry.getMetadata().get("source"));
    }

    @Test
    void addWithDuplicateIdThrowsIllegalArgumentException() {
        String customId = "dup-id-001";
        memory.add(customId, "第一次", Collections.emptyMap(), "ns1");
        assertThrows(IllegalArgumentException.class, () ->
            memory.add(customId, "第二次", Collections.emptyMap(), "ns1"));
    }

    @Test
    void addWithExternalIdIsSearchable() {
        String customId = "search-id-001";
        memory.add(customId, "人工智能记忆", Collections.emptyMap(), "ns1");
        List<MemoryResult> results = memory.search("人工智能", 5);
        assertFalse(results.isEmpty());
        assertEquals(customId, results.get(0).getId());
    }

    // ===== helper =====

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
