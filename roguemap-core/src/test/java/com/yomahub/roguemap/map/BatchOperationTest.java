package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueMap 批量 API（putAll/getAll）功能测试
 */
public class BatchOperationTest {

    private RogueMap<String, String> newTempMap() {
        return RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
    }

    // ========== putAll 基本功能 ==========

    @Test
    public void testPutAllBasic() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                batch.put("key" + i, "value" + i);
            }
            map.putAll(batch);

            assertEquals(100, map.size());
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, map.get("key" + i));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllOverwriteFreesOldMemory() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch1 = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                batch1.put("key" + i, "old-value-" + i);
            }
            map.putAll(batch1);
            long deadBefore = map.getMetrics().getDeadBytes();

            Map<String, String> batch2 = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                batch2.put("key" + i, "new-value-" + i);
            }
            map.putAll(batch2);

            assertEquals(50, map.size());
            for (int i = 0; i < 50; i++) {
                assertEquals("new-value-" + i, map.get("key" + i));
            }
            // 旧值内存已释放（计入 dead bytes）
            assertTrue(map.getMetrics().getDeadBytes() > deadBefore);
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllWithTTL() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            batch.put("ttl-key1", "v1");
            batch.put("ttl-key2", "v2");
            map.putAll(batch, 300, TimeUnit.MILLISECONDS);

            assertEquals("v1", map.get("ttl-key1"));
            assertEquals("v2", map.get("ttl-key2"));

            Thread.sleep(500);

            assertNull(map.get("ttl-key1"));
            assertNull(map.get("ttl-key2"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllEmptyIsNoop() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.putAll(new HashMap<>());
            assertEquals(0, map.size());
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllNullMapThrows() {
        RogueMap<String, String> map = newTempMap();
        try {
            assertThrows(IllegalArgumentException.class, () -> map.putAll(null));
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllNullKeyRejectsWholeBatch() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            batch.put("ok-key", "v");
            batch.put(null, "v2");

            assertThrows(IllegalArgumentException.class, () -> map.putAll(batch));

            // 校验先于分配：整批拒绝，无任何条目写入
            assertEquals(0, map.size());
            assertNull(map.get("ok-key"));
        } finally {
            map.close();
        }
    }

    // ========== getAll ==========

    @Test
    public void testGetAllRoundtrip() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                batch.put("key" + i, "value" + i);
            }
            map.putAll(batch);

            Map<String, String> got = map.getAll(batch.keySet());
            assertEquals(100, got.size());
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, got.get("key" + i));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllMissingKeysOmitted() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("exists", "v");

            Map<String, String> got = map.getAll(Arrays.asList("exists", "missing1", "missing2"));

            assertEquals(1, got.size());
            assertEquals("v", got.get("exists"));
            assertFalse(got.containsKey("missing1"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllNullElementsSkipped() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("k", "v");

            Map<String, String> got = map.getAll(new ArrayList<>(Arrays.asList("k", null)));

            assertEquals(1, got.size());
            assertEquals("v", got.get("k"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllExpiredKeysOmitted() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("eternal", "v1");
            map.put("mortal", "v2", 200, TimeUnit.MILLISECONDS);

            Thread.sleep(400);

            Map<String, String> got = map.getAll(Arrays.asList("eternal", "mortal"));
            assertEquals(1, got.size());
            assertEquals("v1", got.get("eternal"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllNullCollectionThrows() {
        RogueMap<String, String> map = newTempMap();
        try {
            assertThrows(IllegalArgumentException.class, () -> map.getAll(null));
        } finally {
            map.close();
        }
    }
}
