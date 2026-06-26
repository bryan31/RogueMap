package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueMap 增强 for 遍历（Iterable）功能测试。
 *
 * 覆盖：
 * - for-each 遍历键值对
 * - keys() / values() 视图遍历
 * - 空 map 遍历
 * - 显式 Iterator + NoSuchElementException
 * - TTL 过期条目在遍历时被跳过
 */
public class MapIterationTest {

    private static final String TEST_FILE = "target/test-mmap-iteration.db";

    @BeforeEach
    public void setUp() {
        deleteTestFile();
    }

    @AfterEach
    public void tearDown() {
        deleteTestFile();
    }

    private void deleteTestFile() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    private RogueMap<String, String> newMap() {
        return RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
    }

    @Test
    public void testForEachLoopOverEntries() {
        try (RogueMap<String, String> map = newMap()) {
            Map<String, String> expected = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                map.put("k" + i, "v" + i);
                expected.put("k" + i, "v" + i);
            }

            Map<String, String> seen = new HashMap<>();
            // 关键诉求：直接用增强 for 循环遍历 RogueMap
            for (Map.Entry<String, String> entry : map) {
                seen.put(entry.getKey(), entry.getValue());
            }

            assertEquals(expected, seen);
        }
    }

    @Test
    public void testKeysAndValuesViews() {
        try (RogueMap<String, String> map = newMap()) {
            Set<String> expectedKeys = new HashSet<>();
            Set<String> expectedValues = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                map.put("key" + i, "val" + i);
                expectedKeys.add("key" + i);
                expectedValues.add("val" + i);
            }

            Set<String> keys = new HashSet<>();
            for (String k : map.keys()) {
                keys.add(k);
            }
            Set<String> values = new HashSet<>();
            for (String v : map.values()) {
                values.add(v);
            }

            assertEquals(expectedKeys, keys);
            assertEquals(expectedValues, values);
        }
    }

    @Test
    public void testEmptyMapIteration() {
        try (RogueMap<String, String> map = newMap()) {
            int count = 0;
            for (Map.Entry<String, String> ignored : map) {
                count++;
            }
            assertEquals(0, count);
            assertFalse(map.iterator().hasNext());
        }
    }

    @Test
    public void testIteratorNoSuchElement() {
        try (RogueMap<String, String> map = newMap()) {
            map.put("only", "one");
            Iterator<Map.Entry<String, String>> it = map.iterator();
            assertTrue(it.hasNext());
            assertEquals("only", it.next().getKey());
            assertFalse(it.hasNext());
            assertThrows(NoSuchElementException.class, it::next);
        }
    }

    @Test
    public void testForEachAndIteratorAgree() {
        try (RogueMap<String, String> map = newMap()) {
            for (int i = 0; i < 30; i++) {
                map.put("k" + i, "v" + i);
            }

            Map<String, String> viaForEach = new HashMap<>();
            map.forEach(viaForEach::put);

            Map<String, String> viaIterator = new HashMap<>();
            for (Map.Entry<String, String> e : map) {
                viaIterator.put(e.getKey(), e.getValue());
            }

            assertEquals(viaForEach, viaIterator);
        }
    }

    @Test
    public void testExpiredEntriesSkippedDuringIteration() throws InterruptedException {
        try (RogueMap<String, String> map = newMap()) {
            // 永不过期
            map.put("persistent", "stays");
            // 立即过期（TTL 10ms）
            map.put("temp", "gone", 10, java.util.concurrent.TimeUnit.MILLISECONDS);

            Thread.sleep(50);

            Map<String, String> seen = new HashMap<>();
            for (Map.Entry<String, String> e : map) {
                seen.put(e.getKey(), e.getValue());
            }

            assertTrue(seen.containsKey("persistent"));
            assertFalse(seen.containsKey("temp"), "过期条目应在遍历时被跳过");
        }
    }
}
