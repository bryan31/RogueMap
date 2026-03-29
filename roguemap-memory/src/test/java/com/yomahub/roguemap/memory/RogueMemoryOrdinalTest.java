package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryOrdinalTest {

    private static final String TEST_DIR = "target/test-memory-ordinal";

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
    }

    @Test
    void ordinalRegistryPersistence() throws Exception {
        String id1, id2;

        // 第一次打开：添加 2 条记录
        try (RogueMemory mem = RogueMemory.mmap()
                .persistent(TEST_DIR + "/mem")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .searchMode(SearchMode.HYBRID)
                .build()) {
            id1 = mem.add("第一条记录");
            id2 = mem.add("第二条记录");
            assertNotNull(id1);
            assertNotNull(id2);
        }

        // 第二次打开：验证 ordinals 被正确恢复
        try (RogueMemory mem = RogueMemory.mmap()
                .persistent(TEST_DIR + "/mem")
                .embeddingProvider(new MockEmbeddingProvider(4))
                .searchMode(SearchMode.HYBRID)
                .build()) {

            MemoryEntry e1 = mem.get(id1);
            MemoryEntry e2 = mem.get(id2);

            assertNotNull(e1);
            assertNotNull(e2);
            assertEquals("第一条记录", e1.getContent());
            assertEquals("第二条记录", e2.getContent());
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
}
