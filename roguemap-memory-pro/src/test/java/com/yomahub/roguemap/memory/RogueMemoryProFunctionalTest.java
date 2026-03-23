package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryProFunctionalTest {

    private static final String TEST_DIR = "target/test-memory-pro";
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
    void tearDown() { memory.close(); }

    @Test
    void addSearchDeleteWorks() {
        String id = memory.add("jvector 向量搜索测试");
        List<MemoryResult> results = memory.search("向量搜索", 3);
        assertFalse(results.isEmpty());
        memory.delete(id);
        assertNull(memory.get(id));
    }

    @Test
    void persistenceWorks() {
        String id = memory.add("第二条记忆");
        memory.close();

        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .build();
        assertNotNull(memory.get(id));
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
