package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryAutoExpandTest {

    private static final String TEST_DIR = "target/test-memory-autoexpand";

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
    }

    @AfterEach
    void tearDown() {
        // 测试内部自行 close
    }

    /**
     * 核心场景：初始文件很小，写入足够多的记录触发扩容，所有数据仍然可读。
     */
    @Test
    void autoExpandTriggersAndDataReadable() {
        RogueMemory mem = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)   // 16KB，非常小
            .autoExpand(true)
            .build();

        try {
            List<String> ids = new ArrayList<>();
            // 每条记录约 200+ 字节（content 100 字节 + overhead），16KB - 4KB header ≈ 500 条空间不够，但扩容后可以
            for (int i = 0; i < 200; i++) {
                String id = mem.add("auto-expand-test-content-padding-" + i + "-padding-padding-padding");
                ids.add(id);
            }

            // 验证所有记录都可读
            for (int i = 0; i < ids.size(); i++) {
                MemoryEntry entry = mem.get(ids.get(i));
                assertNotNull(entry, "第 " + i + " 条记录不应为 null");
                assertTrue(entry.getContent().contains("auto-expand-test-content-padding-" + i),
                        "第 " + i + " 条记录内容应匹配");
            }
        } finally {
            mem.close();
        }
    }

    /**
     * 扩容后 search 仍然能正确检索。
     */
    @Test
    void autoExpandSearchStillWorks() {
        RogueMemory mem = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)
            .autoExpand(true)
            .build();

        try {
            mem.add("机器学习是人工智能的一个分支", mapOf("topic", "ai"), "tech");
            mem.add("深度学习使用神经网络进行特征学习", mapOf("topic", "ai"), "tech");
            // 写入大量无关数据以触发扩容
            for (int i = 0; i < 200; i++) {
                mem.add("padding-content-to-fill-space-number-" + i, mapOf("topic", "filler"), "filler");
            }

            List<MemoryResult> results = mem.search("人工智能", 5);
            assertFalse(results.isEmpty(), "扩容后 search 应返回结果");
        } finally {
            mem.close();
        }
    }

    /**
     * 扩容后 close 再 reopen，数据完整恢复。
     */
    @Test
    void autoExpandPersistAfterReopen() {
        String path = TEST_DIR + "/mem";
        List<String> ids = new ArrayList<>();

        // 第一轮：写入并关闭
        RogueMemory mem1 = RogueMemory.mmap()
            .persistent(path)
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)
            .autoExpand(true)
            .build();
        try {
            for (int i = 0; i < 100; i++) {
                ids.add(mem1.add("persistent-content-" + i, mapOf("idx", String.valueOf(i)), "ns"));
            }
        } finally {
            mem1.close();
        }

        // 第二轮：重新打开，验证所有数据
        RogueMemory mem2 = RogueMemory.mmap()
            .persistent(path)
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)
            .autoExpand(true)
            .build();
        try {
            for (int i = 0; i < ids.size(); i++) {
                MemoryEntry entry = mem2.get(ids.get(i));
                assertNotNull(entry, "reopen 后第 " + i + " 条记录不应为 null");
                assertEquals("persistent-content-" + i, entry.getContent());
                assertEquals("ns", entry.getNamespace());
                assertEquals(String.valueOf(i), entry.getMetadata().get("idx"));
            }
        } finally {
            mem2.close();
        }
    }

    /**
     * clean close 后 reopen 走快速恢复路径（dirty flag = 0）：
     * BM25 / HNSW 索引从文件加载而非重建，搜索结果应与关闭前一致。
     */
    @Test
    void cleanCloseRestoresIndexesCorrectly() {
        String path = TEST_DIR + "/mem";

        // 第一轮：写入特征鲜明的内容，关闭
        RogueMemory mem1 = RogueMemory.mmap()
            .persistent(path)
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(256 * 1024)
            .build();
        List<MemoryResult> beforeResults;
        try {
            mem1.add("Java 虚拟机内存管理", mapOf("tag", "jvm"), "tech");
            mem1.add("Python 数据科学与机器学习", mapOf("tag", "ml"), "tech");
            mem1.add("关系型数据库事务与锁机制", mapOf("tag", "db"), "tech");
            // 先拿一次搜索结果作为基准
            beforeResults = mem1.search("内存管理", 3);
            assertFalse(beforeResults.isEmpty(), "写入后搜索应有结果");
        } finally {
            mem1.close();  // 正常关闭：dirtyFlag 应被清零
        }

        // 第二轮：重新打开，应走 clean restore 路径，搜索结果应与关闭前一致
        RogueMemory mem2 = RogueMemory.mmap()
            .persistent(path)
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(256 * 1024)
            .build();
        try {
            List<MemoryResult> afterResults = mem2.search("内存管理", 3);
            assertFalse(afterResults.isEmpty(), "reopen 后搜索应有结果");
            assertEquals(beforeResults.size(), afterResults.size(), "reopen 后搜索结果数量应一致");
            assertEquals(beforeResults.get(0).getId(), afterResults.get(0).getId(),
                    "reopen 后 top-1 结果应与关闭前一致");
        } finally {
            mem2.close();
        }
    }

    /**
     * maxFileSize 生效：扩容达到上限后，继续写入应抛出 OutOfMemoryError。
     */
    @Test
    void autoExpandRespectsMaxFileSize() {
        RogueMemory mem = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)
            .autoExpand(true)
            .expandFactor(1.5)
            .maxFileSize(64 * 1024)    // 最大 64KB
            .build();

        try {
            assertThrows(OutOfMemoryError.class, () -> {
                for (int i = 0; i < 5000; i++) {
                    mem.add("fill-content-to-hit-max-size-" + i + "-padding-padding-padding");
                }
            }, "达到 maxFileSize 后应抛出 OutOfMemoryError");
        } finally {
            mem.close();
        }
    }

    /**
     * 自定义 expandFactor 生效：使用较大的因子，一次扩容足够多。
     */
    @Test
    void autoExpandWithCustomFactor() {
        RogueMemory mem = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)
            .autoExpand(true)
            .expandFactor(4.0)         // 每次 4 倍
            .build();

        try {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                ids.add(mem.add("custom-factor-content-" + i));
            }
            // 全部可读
            for (int i = 0; i < ids.size(); i++) {
                assertNotNull(mem.get(ids.get(i)), "第 " + i + " 条记录应存在");
            }
        } finally {
            mem.close();
        }
    }

    /**
     * 不开启 autoExpand 时，文件空间不足 allocate() 返回 0，
     * writeRecordToAllocator 写入地址 0 应抛异常。
     */
    @Test
    void noAutoExpandFailsWhenFull() {
        RogueMemory mem = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .allocateSize(16 * 1024)
            .autoExpand(false)
            .build();

        try {
            assertThrows(Error.class, () -> {
                for (int i = 0; i < 5000; i++) {
                    mem.add("fill-without-expand-" + i + "-padding-padding-padding");
                }
            }, "不扩容时写满应抛出异常");
        } finally {
            mem.close();
        }
    }

    /**
     * KEYWORD_ONLY 模式下（无向量），autoExpand 也正常工作。
     */
    @Test
    void autoExpandWithKeywordOnlyMode() {
        RogueMemory mem = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .searchMode(SearchMode.KEYWORD_ONLY)
            .allocateSize(16 * 1024)
            .autoExpand(true)
            .build();

        try {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                ids.add(mem.add("keyword-only-content-" + i));
            }
            for (int i = 0; i < ids.size(); i++) {
                MemoryEntry entry = mem.get(ids.get(i));
                assertNotNull(entry, "KEYWORD_ONLY 模式下第 " + i + " 条记录应存在");
                assertEquals("keyword-only-content-" + i, entry.getContent());
            }
        } finally {
            mem.close();
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
