package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动 checkpoint 功能测试
 */
class AutoCheckpointTest {

    @TempDir
    Path tempDir;

    private String getTestPath(String name) {
        return tempDir.resolve(name).toString();
    }

    @BeforeEach
    void setUp() {
        // 清理可能存在的测试文件
        cleanTestFiles();
    }

    @AfterEach
    void tearDown() {
        cleanTestFiles();
    }

    private void cleanTestFiles() {
        File dir = tempDir.toFile();
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    // ==================== RogueMap 测试 ====================

    @Test
    void testMapAutoCheckpointByOperations() throws Exception {
        String path = getTestPath("map_ops.db");

        // 创建 map，每 5 次操作自动 checkpoint
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .autoCheckpoint(5)  // 每 5 次操作 checkpoint
                .build();

        // 写入 10 个条目
        for (int i = 0; i < 10; i++) {
            map.put("key" + i, "value" + i);
        }

        // 关闭（会触发最后一次 checkpoint）
        map.close();

        // 重新打开验证数据
        map = RogueMap.<String, String>mmap()
                .persistent(path)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        for (int i = 0; i < 10; i++) {
            assertEquals("value" + i, map.get("key" + i));
        }

        map.close();
    }

    @Test
    void testMapAutoCheckpointByTime() throws Exception {
        String path = getTestPath("map_time.db");

        // 创建 map，每 100ms 自动 checkpoint
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .autoCheckpoint(100, TimeUnit.MILLISECONDS)
                .build();

        // 写入数据
        map.put("key1", "value1");

        // 等待超过 checkpoint 间隔
        Thread.sleep(200);

        // 写入更多数据
        map.put("key2", "value2");

        // 等待 checkpoint 触发
        Thread.sleep(200);

        // 不调用 close，直接模拟崩溃（通过删除 map 引用）
        // 但由于我们要验证数据，这里先 close
        map.close();

        // 重新打开验证数据
        map = RogueMap.<String, String>mmap()
                .persistent(path)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        assertEquals("value1", map.get("key1"));
        assertEquals("value2", map.get("key2"));

        map.close();
    }

    @Test
    void testMapAutoCheckpointCombined() throws Exception {
        String path = getTestPath("map_combined.db");

        // 创建 map，同时配置时间和操作次数
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .autoCheckpoint(3)  // 每 3 次操作
                .autoCheckpoint(1, TimeUnit.SECONDS)  // 或每 1 秒
                .build();

        // 快速写入 5 个条目（应该触发操作次数 checkpoint）
        for (int i = 0; i < 5; i++) {
            map.put("key" + i, "value" + i);
        }

        map.close();

        // 重新打开验证
        map = RogueMap.<String, String>mmap()
                .persistent(path)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        for (int i = 0; i < 5; i++) {
            assertEquals("value" + i, map.get("key" + i));
        }

        map.close();
    }

    @Test
    void testMapTemporaryModeIgnoresAutoCheckpoint() throws Exception {
        // 临时模式应该忽略 autoCheckpoint 配置
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .autoCheckpoint(1)  // 配置但不应该生效
                .build();

        // 正常使用
        map.put("key1", "value1");
        assertEquals("value1", map.get("key1"));

        map.close();
    }

    // ==================== RogueList 测试 ====================

    @Test
    void testListAutoCheckpointByOperations() throws Exception {
        String path = getTestPath("list_ops.db");

        RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .autoCheckpoint(5)
                .build();

        // 添加元素
        for (long i = 0; i < 10; i++) {
            list.addLast(i);
        }

        list.close();

        // 重新打开验证
        list = RogueList.<Long>mmap()
                .persistent(path)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        assertEquals(10, list.size());
        for (long i = 0; i < 10; i++) {
            assertEquals(i, list.get((int) i));
        }

        list.close();
    }

    // ==================== RogueSet 测试 ====================

    @Test
    void testSetAutoCheckpointByOperations() throws Exception {
        String path = getTestPath("set_ops.db");

        RogueSet<Long> set = RogueSet.<Long>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .elementCodec(PrimitiveCodecs.LONG)
                .autoCheckpoint(5)
                .build();

        // 添加元素
        for (long i = 0; i < 10; i++) {
            assertTrue(set.add(i));
        }

        set.close();

        // 重新打开验证
        set = RogueSet.<Long>mmap()
                .persistent(path)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        assertEquals(10, set.size());
        for (long i = 0; i < 10; i++) {
            assertTrue(set.contains(i));
        }

        set.close();
    }

    // ==================== RogueQueue 测试 ====================

    @Test
    void testQueueAutoCheckpointByOperations() throws Exception {
        String path = getTestPath("queue_ops.db");

        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .autoCheckpoint(5)
                .build();

        // 入队元素
        for (long i = 0; i < 10; i++) {
            assertTrue(queue.offer(i));
        }

        queue.close();

        // 重新打开验证
        queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        assertEquals(10, queue.size());
        for (long i = 0; i < 10; i++) {
            assertEquals(i, queue.poll());
        }

        queue.close();
    }

    @Test
    void testQueueCheckpointMethod() throws Exception {
        String path = getTestPath("queue_checkpoint.db");

        // 先测试 RogueQueue 的 checkpoint 方法是否存在
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        // 写入数据
        queue.offer(1L);
        queue.offer(2L);

        // 调用 checkpoint
        queue.checkpoint();

        // 写入更多数据
        queue.offer(3L);

        queue.close();

        // 重新打开验证
        queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        assertEquals(3, queue.size());

        queue.close();
    }

    // ==================== close 停止测试 ====================

    @Test
    void testCloseStopsAutoCheckpoint() throws Exception {
        String path = getTestPath("close_stop.db");

        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(10 * 1024 * 1024)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .autoCheckpoint(100, TimeUnit.MILLISECONDS)
                .build();

        // 写入数据
        map.put("key1", "value1");

        // 关闭（应该停止定时任务）
        map.close();

        // 验证文件存在且数据正确
        map = RogueMap.<String, String>mmap()
                .persistent(path)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        assertEquals("value1", map.get("key1"));

        map.close();
    }
}
