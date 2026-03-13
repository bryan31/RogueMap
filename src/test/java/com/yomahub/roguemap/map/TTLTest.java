package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import com.yomahub.roguemap.util.TTLUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTL 功能测试
 *
 * <p>注意：TTL 功能目前仅在 RogueMap 中完整实现。
 * RogueSet、RogueList、RogueQueue 的 TTL 功能为预留接口。
 */
public class TTLTest {

    private static final String TEST_DIR = "target/test-files/ttl";

    @BeforeEach
    void setUp() {
        // 清理测试目录
        File dir = new File(TEST_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @AfterEach
    void tearDown() {
        // 清理测试文件
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

    // ========== RogueMap TTL 测试 ==========

    @Test
    void testMapBasicTTL() throws InterruptedException {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(100, TimeUnit.MILLISECONDS)
                .build();

        // 使用默认 TTL 的数据在 100ms 后过期
        map.put("key1", "value1");

        // 显式设置 TTL=0 的数据永不过期
        map.put("key2", "value2", 0, TimeUnit.MILLISECONDS);

        Thread.sleep(150); // 等待过期

        // key1 应该已过期（使用了 defaultTTL）
        assertNull(map.get("key1"));
        // key2 应该还在（显式设置 TTL=0 表示永不过期）
        assertEquals("value2", map.get("key2"));

        map.close();
    }

    @Test
    void testMapNoTTL() throws InterruptedException {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        map.put("key1", "value1");
        map.put("key2", "value2");

        Thread.sleep(100);
        // 没有 TTL 的数据不应该过期
        assertEquals("value1", map.get("key1"));
        assertEquals("value2", map.get("key2"));

        map.close();
    }

    @Test
    void testMapZeroTTL() throws InterruptedException {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(100, TimeUnit.MILLISECONDS)
                .build();

        // TTL=0 表示永不过期
        map.put("key1", "value1", 0, TimeUnit.MILLISECONDS);

        Thread.sleep(150);
        // key1 应该还在
        assertEquals("value1", map.get("key1"));

        map.close();
    }

    @Test
    void testMapContainsKey() throws InterruptedException {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .defaultTTL(100, TimeUnit.MILLISECONDS)
                .build();

        map.put("key1", "value1");

        // 未过期时 containsKey 应该返回 true
        assertTrue(map.containsKey("key1"));

        Thread.sleep(150); // 等待过期

        // 过期后 containsKey 应该返回 false
        assertFalse(map.containsKey("key1"));
        map.close();
    }

    @Test
    void testMapForEach() throws InterruptedException {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        map.put("key1", "value1");
        map.put("key2", "value2", 100, TimeUnit.MILLISECONDS);

        Thread.sleep(150); // 等待过期

        // forEach 应该跳过过期的条目
        int[] count = {0};
        map.forEach((k, v) -> count[0]++);
        assertEquals(1, count[0]); // 只有 key1 未过期
        map.close();
    }

    @Test
    void testMapRemove() throws InterruptedException {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        map.put("key1", "value1");

        // 未过期时 remove 应该返回旧值
        assertEquals("value1", map.remove("key1"));
        assertNull(map.remove("key1")); // 删除后应该返回 null
        map.close();
    }

    // ========== TTLUtils 测试 ==========

    @Test
    void testTTLUtilsCalculateExpireTime() {
        long ttl = 1000;
        long expireTime = TTLUtils.calculateExpireTime(ttl);
        assertTrue(expireTime > System.currentTimeMillis());

        // TTL <= 0 表示永不过期
        assertEquals(0, TTLUtils.calculateExpireTime(0));
        assertEquals(0, TTLUtils.calculateExpireTime(-1));
    }

    @Test
    void testTTLUtilsIsExpired() throws InterruptedException {
        long expireTime = System.currentTimeMillis() + 50;
        assertFalse(TTLUtils.isExpired(expireTime));

        Thread.sleep(100);
        assertTrue(TTLUtils.isExpired(expireTime));

        // expireTime <= 0 表示永不过期
        assertFalse(TTLUtils.isExpired(0));
        assertFalse(TTLUtils.isExpired(-1));
    }
}
