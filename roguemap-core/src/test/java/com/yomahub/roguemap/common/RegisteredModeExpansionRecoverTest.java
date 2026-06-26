package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDK 25 注册式内存后端（RegisteredMemoryAccess）下，四种数据结构「自动扩容 + 持久化 + 重开」的
 * 数据完整性回归测试。
 *
 * <p>背景：注册式后端给每个 mmap 分段分配带间隙的合成基址，打破了「分段虚拟地址连续」的隐含假设。
 * 若持久化时存储「物理地址 - 第0段基址」的相对偏移，多分段（扩容）后重新打开会换算出错误地址、
 * 读到错位/损坏数据。修复后统一以「文件偏移量」持久化（见 AddressTranslator）。
 *
 * <p>静态块强制 registered 后端，以便在任意 JDK 上守护该问题（须在 UnsafeOps 类加载前设置；
 * 单独运行本类时可靠生效）。
 */
public class RegisteredModeExpansionRecoverTest {

    static {
        System.setProperty("roguemap.memory.access", "registered");
    }

    private static final String TEST_DIR =
            System.getProperty("java.io.tmpdir") + "/roguemap_registered_recover_test/";

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
        return TEST_DIR + name + ".db";
    }

    @Test
    public void testMapExpandRecover() {
        String path = testFile("map");
        int count = 2000;

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(8 * 1024L)
                .autoExpand(true)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            for (int i = 0; i < count; i++) {
                map.put("k-" + i, "v-" + i);
            }
        }

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build()) {
            for (int i = 0; i < count; i++) {
                assertEquals("v-" + i, map.get("k-" + i), "Map key k-" + i);
            }
        }
    }

    @Test
    public void testListExpandRecover() {
        String path = testFile("list");
        int count = 2000;

        try (RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(path)
                .allocateSize(4 * 1024L)
                .autoExpand(true)
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            for (long i = 0; i < count; i++) {
                list.addLast(i);
            }
        }

        try (RogueList<Long> list = RogueList.<Long>mmap()
                .persistent(path)
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertEquals(count, list.size());
            assertEquals(0L, list.get(0).longValue());
            assertEquals(1000L, list.get(1000).longValue());
            assertEquals(count - 1L, list.get(count - 1).longValue());
        }
    }

    @Test
    public void testSetExpandRecover() {
        String path = testFile("set");
        int count = 2000;

        try (RogueSet<Long> set = RogueSet.<Long>mmap()
                .persistent(path)
                .allocateSize(4 * 1024L)
                .autoExpand(true)
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            for (long i = 0; i < count; i++) {
                set.add(i);
            }
        }

        try (RogueSet<Long> set = RogueSet.<Long>mmap()
                .persistent(path)
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertEquals(count, set.size());
            for (long i = 0; i < count; i++) {
                assertTrue(set.contains(i), "Set should contain " + i);
            }
        }
    }

    @Test
    public void testQueueExpandRecover() {
        String path = testFile("queue");
        int count = 2000;

        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .allocateSize(4 * 1024L)
                .autoExpand(true)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            for (long i = 0; i < count; i++) {
                assertTrue(queue.offer(i));
            }
        }

        try (RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .persistent(path)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build()) {
            assertEquals(count, queue.size());
            for (long i = 0; i < count; i++) {
                Long v = queue.poll();
                assertNotNull(v);
                assertEquals(i, v.longValue());
            }
        }
    }
}
