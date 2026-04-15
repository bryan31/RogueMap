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

    // ===== delete(id, namespace) =====

    @Test
    void deleteWithMatchingNamespaceRemovesEntry() {
        String id = memory.add("待删除", Collections.emptyMap(), "target-ns");
        memory.delete(id, "target-ns");
        assertNull(memory.get(id));
        assertFalse(memory.exists(id));
    }

    @Test
    void deleteWithMismatchedNamespaceIgnores() {
        String id = memory.add("不应被删除", Collections.emptyMap(), "target-ns");
        memory.delete(id, "wrong-ns");
        assertNotNull(memory.get(id));
        assertTrue(memory.exists(id));
    }

    @Test
    void deleteWithNamespaceOnUnknownIdIsNoOp() {
        assertDoesNotThrow(() -> memory.delete("nonexistent", "any-ns"));
    }

    // ===== update(id, namespace, newContent) =====

    @Test
    void updateWithMatchingNamespaceUpdatesContent() {
        String id = memory.add("原始内容", Collections.emptyMap(), "update-ns");
        memory.update(id, "update-ns", "新内容");
        MemoryEntry entry = memory.get(id);
        assertNotNull(entry);
        assertEquals("新内容", entry.getContent());
        assertEquals("update-ns", entry.getNamespace());
    }

    @Test
    void updateWithMismatchedNamespaceIgnores() {
        String id = memory.add("原始内容", Collections.emptyMap(), "update-ns");
        memory.update(id, "wrong-ns", "新内容");
        MemoryEntry entry = memory.get(id);
        assertNotNull(entry);
        assertEquals("原始内容", entry.getContent());
    }

    @Test
    void updateWithNamespaceOnUnknownIdIsNoOp() {
        assertDoesNotThrow(() -> memory.update("nonexistent", "any-ns", "新内容"));
    }

    @Test
    void updateWithNamespacePreservesMetadata() {
        Map<String, String> meta = Collections.singletonMap("key", "value");
        String id = memory.add("原始内容", meta, "meta-ns");
        memory.update(id, "meta-ns", "更新内容");
        MemoryEntry entry = memory.get(id);
        assertEquals("value", entry.getMetadata().get("key"));
    }

    // ===== deleteByNamespace =====

    @Test
    void deleteByNamespaceRemovesAllInNamespace() {
        String id1 = memory.add("内容1", Collections.emptyMap(), "del-ns");
        String id2 = memory.add("内容2", Collections.emptyMap(), "del-ns");
        String id3 = memory.add("内容3", Collections.emptyMap(), "other-ns");

        memory.deleteByNamespace("del-ns");

        assertNull(memory.get(id1));
        assertNull(memory.get(id2));
        assertNotNull(memory.get(id3));
    }

    @Test
    void deleteByNamespaceOnEmptyNamespaceIsNoOp() {
        String id = memory.add("内容", Collections.emptyMap(), "keep-ns");
        memory.deleteByNamespace("nonexistent-ns");
        assertNotNull(memory.get(id));
    }

    // ===== helper =====

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
