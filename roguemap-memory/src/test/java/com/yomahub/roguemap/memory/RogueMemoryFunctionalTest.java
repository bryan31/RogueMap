package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryFunctionalTest {

    private static final String TEST_DIR = "target/test-memory-functional";
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

    @Test
    void addAndGetById() {
        String id = memory.add("我有一件红衣服");
        assertNotNull(id);

        MemoryEntry entry = memory.get(id);
        assertNotNull(entry);
        assertEquals("我有一件红衣服", entry.getContent());
        assertEquals("default", entry.getNamespace());
        assertTrue(entry.getMetadata().isEmpty());
    }

    @Test
    void addWithMetadataAndNamespace() {
        String id = memory.add("今天天气很好",
            mapOf("userId", "u123", "source", "chat"),
            "session-1");

        MemoryEntry entry = memory.get(id);
        assertEquals("u123", entry.getMetadata().get("userId"));
        assertEquals("session-1", entry.getNamespace());
    }

    @Test
    void deleteRemovesEntry() {
        String id = memory.add("要被删除的记忆");
        memory.delete(id);
        assertNull(memory.get(id));
    }

    @Test
    void updateChangesContent() {
        String id = memory.add("原始内容");
        memory.update(id, "更新后的内容");

        MemoryEntry entry = memory.get(id);
        assertEquals("更新后的内容", entry.getContent());
    }

    @Test
    void updatePreservesMetadataAndNamespace() {
        String id = memory.add("原始内容", mapOf("key", "val"), "ns1");
        memory.update(id, "新内容");

        MemoryEntry entry = memory.get(id);
        assertEquals("ns1", entry.getNamespace());
        assertEquals("val", entry.getMetadata().get("key"));
    }

    @Test
    void getNonExistentReturnsNull() {
        assertNull(memory.get("nonexistent-id"));
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
