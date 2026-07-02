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

    // ========== SegmentedHashIndex 覆写 ==========

    @Test
    public void testSegmentedPutBatchManyKeys() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entries.add(BatchEntry.put("key" + i, 10000L + i, 8));
        }

        IndexUpdateResult[] results = index.putBatch(entries);

        assertEquals(1000, results.length);
        assertEquals(1000, index.size());
        for (int i = 0; i < 1000; i++) {
            assertFalse(results[i].wasPresent);
            assertEquals(10000L + i, index.get("key" + i));
        }
    }

    @Test
    public void testSegmentedPutBatchReturnsOldValuesInInputOrder() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);
        index.put("exist1", 111L, 1);
        index.put("exist2", 222L, 2);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("new1", 1000L, 10));
        entries.add(BatchEntry.put("exist2", 2000L, 20));
        entries.add(BatchEntry.put("exist1", 3000L, 30));

        IndexUpdateResult[] results = index.putBatch(entries);

        // 结果必须按入参顺序回填，与段分组顺序无关
        assertFalse(results[0].wasPresent);
        assertTrue(results[1].wasPresent);
        assertEquals(222L, results[1].oldAddress);
        assertTrue(results[2].wasPresent);
        assertEquals(111L, results[2].oldAddress);
        assertEquals(3, index.size());
    }

    @Test
    public void testSegmentedPutBatchDuplicateKeyAppliedInOrder() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("dup", 100L, 1));
        entries.add(BatchEntry.put("dup", 200L, 2));

        IndexUpdateResult[] results = index.putBatch(entries);

        // 同键按列表顺序依次应用：后者覆盖前者，前者作为旧值返回
        assertFalse(results[0].wasPresent);
        assertTrue(results[1].wasPresent);
        assertEquals(100L, results[1].oldAddress);
        assertEquals(200L, index.get("dup"));
        assertEquals(1, index.size());
    }

    @Test
    public void testSegmentedPutBatchRejectsRemoveEntries() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));
        entries.add(BatchEntry.remove("b"));

        assertThrows(IllegalArgumentException.class, () -> index.putBatch(entries));
        // 校验在分组阶段完成（任何加锁之前），整批拒绝
        assertEquals(0, index.get("a"));
        assertEquals(0, index.size());
    }

    @Test
    public void testSegmentedPutBatchEmptyAndNull() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);
        assertEquals(0, index.putBatch(new ArrayList<>()).length);
        assertEquals(0, index.putBatch(null).length);
    }
}
