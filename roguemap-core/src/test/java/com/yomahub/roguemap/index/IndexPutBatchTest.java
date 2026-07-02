package com.yomahub.roguemap.index;

import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Index.putBatch 单元测试
 *
 * <p>索引层不触碰真实内存，测试使用假地址（如 1000L）。
 */
public class IndexPutBatchTest {

    // ========== default 实现（经由 HashIndex）==========

    @Test
    public void testDefaultPutBatchBasic() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));
        entries.add(BatchEntry.put("b", 2000L, 20));

        IndexUpdateResult[] results = index.putBatch(entries);

        assertEquals(2, results.length);
        assertFalse(results[0].wasPresent);
        assertFalse(results[1].wasPresent);
        assertEquals(1000L, index.get("a"));
        assertEquals(2000L, index.get("b"));
        assertEquals(2, index.size());
    }

    @Test
    public void testDefaultPutBatchReturnsOldValues() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);
        index.put("a", 500L, 5);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));

        IndexUpdateResult[] results = index.putBatch(entries);

        assertTrue(results[0].wasPresent);
        assertEquals(500L, results[0].oldAddress);
        assertEquals(5, results[0].oldSize);
        assertEquals(1000L, index.get("a"));
        assertEquals(1, index.size());
    }

    @Test
    public void testDefaultPutBatchEmptyAndNull() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);
        assertEquals(0, index.putBatch(new ArrayList<>()).length);
        assertEquals(0, index.putBatch(null).length);
    }

    @Test
    public void testDefaultPutBatchRejectsRemoveEntries() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);
        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));
        entries.add(BatchEntry.remove("b"));

        assertThrows(IllegalArgumentException.class, () -> index.putBatch(entries));
        // 校验先于应用：整批拒绝，"a" 未写入
        assertEquals(0, index.get("a"));
    }
}
