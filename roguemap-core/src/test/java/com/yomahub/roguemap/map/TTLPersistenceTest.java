package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTL 持久化测试
 *
 * 验证带 TTL 的数据在 close → reopen 后过期行为正确。
 */
public class TTLPersistenceTest {

    private static final String TEST_DIR = "target/test-files/ttl-persistence";

    @BeforeEach
    void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
        dir.mkdirs();
    }

    @AfterEach
    void tearDown() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    /**
     * 写入带默认 TTL 的数据 → close → 等待过期 → reopen → 验证数据已过期
     */
    @Test
    void testTTLDataExpiresAfterReopen() throws InterruptedException {
        String path = TEST_DIR + "/ttl_persist.db";

        // 写入带 200ms TTL 的数据
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(200, TimeUnit.MILLISECONDS)
                .build()) {
            map.put("expire-key", "expire-value");
            map.put("permanent-key", "permanent-value", 0, TimeUnit.MILLISECONDS);
        }

        // 等待 TTL 过期
        Thread.sleep(300);

        // 重新打开，验证过期行为
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(200, TimeUnit.MILLISECONDS)
                .build()) {
            // 带 TTL 的数据应已过期
            assertNull(map.get("expire-key"), "带 TTL 的数据在 reopen 后应已过期");
            assertFalse(map.containsKey("expire-key"));
            // 永不过期的数据应仍存在
            assertEquals("permanent-value", map.get("permanent-key"));
        }
    }

    /**
     * 写入带 TTL 的数据 → close → 立即 reopen（未过期）→ 验证数据仍可读
     */
    @Test
    void testTTLDataSurvivesReopenBeforeExpiry() {
        String path = TEST_DIR + "/ttl_survive.db";

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(10, TimeUnit.SECONDS)
                .build()) {
            map.put("key1", "value1");
            map.put("key2", "value2");
        }

        // 立即重新打开（远未过期）
        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(10, TimeUnit.SECONDS)
                .build()) {
            assertEquals("value1", map.get("key1"), "未过期数据在 reopen 后应仍可读");
            assertEquals("value2", map.get("key2"));
        }
    }

    /**
     * 测试 per-entry TTL 长于 defaultTTL 的场景
     */
    @Test
    void testPerEntryTTLLongerThanDefault() throws InterruptedException {
        String path = TEST_DIR + "/ttl_per_entry.db";

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(100, TimeUnit.MILLISECONDS)
                .build()) {
            // 使用默认 TTL（100ms）
            map.put("short-ttl", "short");
            // 使用更长的 per-entry TTL（10s）
            map.put("long-ttl", "long", 10, TimeUnit.SECONDS);
        }

        Thread.sleep(200);

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(100, TimeUnit.MILLISECONDS)
                .build()) {
            assertNull(map.get("short-ttl"), "默认 TTL 的数据应已过期");
            assertEquals("long", map.get("long-ttl"), "长 TTL 的数据应仍存在");
        }
    }

    /**
     * forEach 在 reopen 后应跳过已过期的条目
     */
    @Test
    void testForEachSkipsExpiredAfterReopen() throws InterruptedException {
        String path = TEST_DIR + "/ttl_foreach.db";

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            map.put("alive", "yes", 0, TimeUnit.MILLISECONDS);
            map.put("dying", "soon", 100, TimeUnit.MILLISECONDS);
        }

        Thread.sleep(200);

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            int[] count = {0};
            map.forEach((k, v) -> count[0]++);
            assertEquals(1, count[0], "forEach 应跳过已过期条目");
        }
    }
}
