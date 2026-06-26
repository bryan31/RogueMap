package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：当 allocateSize 小于文件头大小（HEADER_SIZE=4096）时，
 * close() 写入 4KB 文件头会越过第一个 mmap 分段边界。
 *
 * <p>JDK 25 起默认使用注册式内存后端（RegisteredMemoryAccess，带严格边界检查），
 * 会抛出 "地址未注册或访问越界"；旧版 Unsafe 后端虽不报错，但会写入分段之外、无法保证持久化。
 *
 * <p>用户配置：.allocateSize(3 * 1024L) + persistent + autoExpand(true)，close() 时崩溃；
 * allocateSize >= 4096 时正常。
 */
public class MapSmallAllocateSizeTest {

    static {
        // 强制使用 JDK 25 的注册式内存后端（带严格边界检查），
        // 以便在任意 JDK 版本上复现并守护该问题。必须在 UnsafeOps 类加载前设置。
        System.setProperty("roguemap.memory.access", "registered");
    }

    private static final String TEST_DIR = System.getProperty("java.io.tmpdir") + "/roguemap_small_alloc_test/";

    @BeforeEach
    public void setUp() {
        File dir = new File(TEST_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.mkdirs();
    }

    @AfterEach
    public void tearDown() {
        File dir = new File(TEST_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    private String testFile(String name) {
        return TEST_DIR + name + ".meta";
    }

    /**
     * allocateSize 远小于 HEADER_SIZE：
     * 1）写入 + close 不应抛出越界异常（#1）；
     * 2）重新打开后数据完整（#2：扩容产生多分段后，索引以文件偏移量持久化才能正确恢复）。
     */
    @Test
    public void testAllocateSizeSmallerThanHeaderRoundTrip() {
        String path = testFile("small_alloc");

        assertDoesNotThrow(() -> {
            try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                    .persistent(path)
                    .allocateSize(3 * 1024L)   // 3072 < HEADER_SIZE(4096)，首次 put 即触发扩容
                    .keyCodec(StringCodec.INSTANCE)
                    .autoExpand(true)
                    .expandFactor(2.0)
                    .valueCodec(StringCodec.INSTANCE)
                    .build()) {

                map.put("k1", "v1");
                map.put("k2", "v2");
                assertEquals("v1", map.get("k1"));
                assertEquals("v2", map.get("k2"));
            } // close() 在此触发 saveMmapIndex -> writeHeader，旧版本会越界
        });

        // 重新打开应能完整读回（旧版本相对偏移在多分段下会错位）
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(3 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .autoExpand(true)
                .expandFactor(2.0)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            assertEquals("v1", map.get("k1"));
            assertEquals("v2", map.get("k2"));
        }
    }

    /** 触发多次扩容后写入 + close + 重开，数据应完整（多分段文件偏移恢复）。 */
    @Test
    public void testMultiSegmentReopenIntegrity() {
        String path = testFile("multi_seg");
        int count = 5000;

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(8 * 1024L)   // 小初值，写入过程中多次扩容产生多分段
                .keyCodec(StringCodec.INSTANCE)
                .autoExpand(true)
                .expandFactor(2.0)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            for (int i = 0; i < count; i++) {
                map.put("key-" + i, "value-" + i);
            }
        }

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            for (int i = 0; i < count; i++) {
                assertEquals("value-" + i, map.get("key-" + i), "key-" + i + " 重开后应一致");
            }
        }
    }

    /** 边界值：allocateSize == HEADER_SIZE 也应正常工作。 */
    @Test
    public void testAllocateSizeEqualsHeader() {
        String path = testFile("equal_header");

        assertDoesNotThrow(() -> {
            try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                    .persistent(path)
                    .allocateSize(4096L)
                    .keyCodec(StringCodec.INSTANCE)
                    .autoExpand(true)
                    .valueCodec(StringCodec.INSTANCE)
                    .build()) {

                map.put("a", "1");
                assertEquals("1", map.get("a"));
            }
        });
    }
}
