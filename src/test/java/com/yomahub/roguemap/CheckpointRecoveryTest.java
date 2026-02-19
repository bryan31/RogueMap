package com.yomahub.roguemap;

import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.storage.MmapStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoint 崩溃恢复测试
 *
 * 模拟崩溃场景（不调 close()，直接关闭 allocator），验证数据能从最近 checkpoint 恢复。
 */
public class CheckpointRecoveryTest {

    private static final String MAP_FILE = "target/test-checkpoint-map.db";
    private static final String SET_FILE = "target/test-checkpoint-set.db";
    private static final String LIST_FILE = "target/test-checkpoint-list.db";

    @BeforeEach
    public void setUp() {
        deleteFiles();
    }

    @AfterEach
    public void tearDown() {
        deleteFiles();
    }

    private void deleteFiles() {
        for (String f : new String[]{MAP_FILE, SET_FILE, LIST_FILE}) {
            File file = new File(f);
            if (file.exists()) file.delete();
        }
    }

    // ========== RogueMap checkpoint 测试 ==========

    @Test
    public void testMapCheckpointRecovery() {
        // 1. 写入 100 条 + checkpoint
        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 100; i++) {
            map.put(i, i * 10);
        }
        map.checkpoint();

        // 2. 再写入 50 条（不做 checkpoint）
        for (long i = 100; i < 150; i++) {
            map.put(i, i * 10);
        }

        // 3. 模拟崩溃：直接关闭底层 storage（不调用 close()）
        MmapAllocator allocator = ((MmapStorage) map.getStorage()).getAllocator();
        allocator.close();

        // 4. 重新打开，应恢复到 checkpoint 状态（100 条）
        RogueMap<Long, Long> recovered = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(100, recovered.size());
            for (long i = 0; i < 100; i++) {
                assertEquals(i * 10, recovered.get(i).longValue(), "key=" + i);
            }
            // checkpoint 后写入的数据应丢失
            assertNull(recovered.get(100L));
        } finally {
            recovered.close();
        }
    }

    @Test
    public void testMapMultipleCheckpoints() {
        // 1. 写入 100 条 + checkpoint
        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 100; i++) {
            map.put(i, i * 10);
        }
        map.checkpoint();

        // 2. 删除 30 条 + 第二次 checkpoint
        for (long i = 0; i < 30; i++) {
            map.remove(i);
        }
        map.checkpoint();

        // 3. 模拟崩溃
        MmapAllocator allocator = ((MmapStorage) map.getStorage()).getAllocator();
        allocator.close();

        // 4. 恢复 → 应有 70 条
        RogueMap<Long, Long> recovered = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(70, recovered.size());
            for (long i = 30; i < 100; i++) {
                assertEquals(i * 10, recovered.get(i).longValue(), "key=" + i);
            }
            // 已删除的 key 不存在
            assertNull(recovered.get(0L));
            assertNull(recovered.get(29L));
        } finally {
            recovered.close();
        }
    }

    @Test
    public void testMapNormalCloseAndReopen() {
        // 正常 close（不用 checkpoint）也能恢复
        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 50; i++) {
            map.put(i, i * 100);
        }
        map.close(); // 正常关闭

        // 重新打开
        RogueMap<Long, Long> recovered = RogueMap.<Long, Long>mmap()
                .persistent(MAP_FILE)
                .allocateSize(16L * 1024 * 1024)
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(50, recovered.size());
            for (long i = 0; i < 50; i++) {
                assertEquals(i * 100, recovered.get(i).longValue());
            }
        } finally {
            recovered.close();
        }
    }

    // ========== RogueSet checkpoint 测试 ==========

    @Test
    public void testSetCheckpointRecovery() {
        // checkpoint + 正常 close + reopen，验证所有数据恢复
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .persistent(SET_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 100; i++) {
            set.add(i);
        }
        set.checkpoint();

        // 再添加 50 条（checkpoint + close 都会保存）
        for (long i = 100; i < 150; i++) {
            set.add(i);
        }
        set.close(); // 正常 close 写入所有 150 条

        RogueSet<Long> recovered = RogueSet.<Long>mmap()
                .persistent(SET_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            // 正常 close 写入了所有 150 条
            assertEquals(150, recovered.size());
            for (long i = 0; i < 150; i++) {
                assertTrue(recovered.contains(i), "应包含元素: " + i);
            }
        } finally {
            recovered.close();
        }
    }

    @Test
    public void testSetCheckpointAndNormalClose() {
        // 写入 + checkpoint + 正常 close，验证恢复
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .persistent(SET_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 80; i++) {
            set.add(i);
        }
        set.checkpoint();
        set.close();

        RogueSet<Long> recovered = RogueSet.<Long>mmap()
                .persistent(SET_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(80, recovered.size());
            for (long i = 0; i < 80; i++) {
                assertTrue(recovered.contains(i));
            }
        } finally {
            recovered.close();
        }
    }

    // ========== RogueList checkpoint 测试 ==========

    @Test
    public void testListCheckpointAndNormalClose() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 100; i++) {
            list.addLast(i);
        }
        list.checkpoint();
        list.close();

        RogueList<Long> recovered = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(100, recovered.size());
            for (int i = 0; i < 100; i++) {
                assertEquals(i, recovered.get(i).longValue(), "index=" + i);
            }
        } finally {
            recovered.close();
        }
    }

    @Test
    public void testListCheckpointMultipleRounds() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        // 第一轮：50 条 + checkpoint
        for (long i = 0; i < 50; i++) {
            list.addLast(i);
        }
        list.checkpoint();

        // 第二轮：再加 50 条 + checkpoint
        for (long i = 50; i < 100; i++) {
            list.addLast(i);
        }
        list.checkpoint();
        list.close();

        RogueList<Long> recovered = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(100, recovered.size());
            for (int i = 0; i < 100; i++) {
                assertEquals(i, recovered.get(i).longValue());
            }
        } finally {
            recovered.close();
        }
    }

    @Test
    public void testListPersistenceWithoutCheckpoint() {
        // 不调 checkpoint，正常 close 也应该恢复
        RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        for (long i = 0; i < 200; i++) {
            list.addLast(i);
        }
        list.close();

        RogueList<Long> recovered = RogueList.<Long>mmap()
                .persistent(LIST_FILE)
                .allocateSize(16L * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            assertEquals(200, recovered.size());
            assertEquals(0, recovered.getFirst().longValue());
            assertEquals(199, recovered.getLast().longValue());
        } finally {
            recovered.close();
        }
    }
}
