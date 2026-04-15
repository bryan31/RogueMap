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

    // ===== helper =====

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
