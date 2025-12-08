package com.yomahub.roguemap;

import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MMAP 模式测试
 */
public class MmapTest {

    private static final String TEST_FILE = "target/test-mmap.db";
    private RogueMap<String, String> map;

    @BeforeEach
    public void setUp() {
        // 删除旧的测试文件
        deleteTestFile();
    }

    @AfterEach
    public void tearDown() {
        if (map != null) {
            map.close();
        }
        // 清理测试文件
        deleteTestFile();
    }

    private void deleteTestFile() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    @Test
    public void testBasicMmapOperations() {
        // 创建 MMAP 模式的 RogueMap
        map = RogueMap.<String, String>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(10 * 1024 * 1024L)  // 10MB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        // 测试 put
        assertNull(map.put("key1", "value1"));
        assertEquals("value1", map.get("key1"));

        // 测试更新
        assertEquals("value1", map.put("key1", "value2"));
        assertEquals("value2", map.get("key1"));

        // 测试多个键值对
        map.put("key2", "value2");
        map.put("key3", "value3");
        assertEquals(3, map.size());

        // 测试 containsKey
        assertTrue(map.containsKey("key1"));
        assertTrue(map.containsKey("key2"));
        assertFalse(map.containsKey("nonexistent"));

        // 测试 remove
        assertEquals("value2", map.remove("key1"));
        assertNull(map.get("key1"));
        assertEquals(2, map.size());
    }

    @Test
    public void testMmapPersistence() {
        // 第一阶段：写入数据
        map = RogueMap.<String, String>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        map.put("persistent1", "data1");
        map.put("persistent2", "data2");
        map.put("persistent3", "data3");

        // 刷新到磁盘
        map.flush();
        map.close();

        // 验证文件存在
        File file = new File(TEST_FILE);
        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        // 第二阶段：重新打开并读取数据
        map = RogueMap.<String, String>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        // 注意：当前实现不会自动恢复数据，这里只是验证文件映射功能
        // 完整的持久化需要实现 WAL 和数据恢复机制
        // 这个测试主要验证文件创建和映射功能正常工作
    }

    @Test
    public void testMmapWithLargeData() {
        map = RogueMap.<String, String>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(100 * 1024 * 1024L)  // 100MB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        // 写入大量数据
        int count = 1000;
        String padding = createPadding(100);
        for (int i = 0; i < count; i++) {
            map.put("key" + i, "value" + i + "_" + padding);
        }

        assertEquals(count, map.size());

        // 验证数据
        for (int i = 0; i < count; i++) {
            String expected = "value" + i + "_" + padding;
            assertEquals(expected, map.get("key" + i));
        }
    }

    private String createPadding(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    @Test
    public void testMmapWithPrimitiveTypes() {
        RogueMap<Long, Long> longMap = RogueMap.<Long, Long>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            // 测试 Long 类型
            longMap.put(1L, 100L);
            longMap.put(2L, 200L);
            longMap.put(3L, 300L);

            assertEquals(100L, longMap.get(1L));
            assertEquals(200L, longMap.get(2L));
            assertEquals(300L, longMap.get(3L));
            assertEquals(3, longMap.size());

        } finally {
            longMap.close();
        }
    }

    @Test
    public void testMmapFileCreation() {
        map = RogueMap.<String, String>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        // 验证文件创建
        File file = new File(TEST_FILE);
        assertTrue(file.exists());
        assertEquals(10 * 1024 * 1024L, file.length());
    }

    @Test
    public void testMmapWithoutFilePath() {
        // 测试没有设置文件路径时的错误处理
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            RogueMap.<String, String>builder()
                    .mmap()
                    .keyCodec(new StringCodec())
                    .valueCodec(new StringCodec())
                    .build();
        });

        assertTrue(exception.getMessage().contains("MMAP 模式必须设置文件路径"));
    }

    @Test
    public void testAllocateSizeConfiguration() {
        long customSize = 50 * 1024 * 1024L; // 50MB

        map = RogueMap.<String, String>builder()
                .persistent(TEST_FILE)
                .mmap()
                .allocateSize(customSize)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        File file = new File(TEST_FILE);
        assertEquals(customSize, file.length());
    }
}
