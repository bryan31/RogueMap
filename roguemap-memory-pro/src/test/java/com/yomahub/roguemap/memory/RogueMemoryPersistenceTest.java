package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryPersistenceTest {

    private static final String TEST_DIR = "target/test-memory-persistence";

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
    }

    @Test
    void dataSurvivesCloseAndReopen() {
        String id;
        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/mem")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            id = m.add("持久化测试内容", mapOf("k", "v"), "ns1");
        }

        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/mem")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            MemoryEntry entry = m.get(id);
            assertNotNull(entry);
            assertEquals("持久化测试内容", entry.getContent());
            assertEquals("ns1", entry.getNamespace());
            assertEquals("v", entry.getMetadata().get("k"));
        }
    }

    @Test
    void searchWorksAfterReopen() {
        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/search")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            m.add("衣服记忆内容");
            m.add("天气记忆内容");
        }

        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/search")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            List<MemoryResult> results = m.search("衣服", 3);
            assertFalse(results.isEmpty());
        }
    }

    @Test
    void checkpointAllowsRecovery() {
        RogueMemory m = RogueMemory.mmap()
            .persistent(TEST_DIR + "/checkpoint")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .build();
        String id = m.add("checkpoint 测试");
        m.checkpoint();
        m.close();

        try (RogueMemory m2 = RogueMemory.mmap()
                .persistent(TEST_DIR + "/checkpoint")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            assertNotNull(m2.get(id));
        }
    }

    @Test
    void compactRemovesTombstones() {
        String id;
        try (RogueMemory m = RogueMemory.mmap()
                .persistent(TEST_DIR + "/compact")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .build()) {
            id = m.add("要被删除的内容");
            m.add("要保留的内容");
            m.delete(id);

            RogueMemory compacted = m.compact(64 * 1024 * 1024);
            assertNull(compacted.get(id));

            List<MemoryResult> results = compacted.search("保留", 5);
            assertFalse(results.isEmpty());
            compacted.close();
        }
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
