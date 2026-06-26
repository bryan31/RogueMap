package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.index.SegmentedHashIndex;
import com.yomahub.roguemap.memory.AddressTranslator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向后兼容回归测试：验证「文件偏移量持久化」改造后，仍能正确读取旧版本（相对第0段基址偏移）写出的索引。
 *
 * <p>关键保证（与平台/内存后端无关，可数学证明）：对单分段文件，
 * {@code getFileOffsetForAddress(addr) == addr - baseAddress}，因此新旧两种序列化产生的字节完全一致，
 * 任何旧的单分段持久化文件升级后都能被新代码正确读回。单分段涵盖了最常见的情况：
 * 未触发扩容的文件，以及任何重启后重新打开（≤2GB 被映射为单段）的文件。
 */
public class MapBackwardCompatTest {

    private static final String TEST_DIR =
            System.getProperty("java.io.tmpdir") + "/roguemap_compat_test/";

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

    /**
     * 索引层兼容性核心证明：
     * 1) 用旧语义（relativeTo 第0段基址）序列化的字节，新代码（文件偏移 translator）能正确反序列化并解析出原值；
     * 2) 单分段下，新旧两种序列化产生的字节逐字节相同（格式稳定，不需升版本号）。
     */
    @Test
    public void testNewCodeReadsOldRelativeOffsetIndex() {
        String path = testFile("old_index");
        // 单分段：足够大且不开扩容，所有分配落在第0段
        MmapAllocator allocator = new MmapAllocator(path, 1024 * 1024, false);
        try {
            long baseAddress = allocator.getBaseAddress();

            SegmentedHashIndex<String> index = new SegmentedHashIndex<>(StringCodec.INSTANCE, 64, 16);

            // 写入若干「值」，记录其物理地址，并放入索引
            int n = 50;
            long[] valueAddrs = new long[n];
            for (int i = 0; i < n; i++) {
                long addr = allocator.allocate(8);
                UnsafeOps.putLong(addr, 1000L + i);   // 每个值是一个可校验的 long
                valueAddrs[i] = addr;
                index.put("key-" + i, addr, 8);
            }

            int indexSize = index.serializedSize();

            // 旧语义序列化（相对第0段基址）
            long oldAddr = allocator.allocate(indexSize);
            index.serializeWithOffsets(oldAddr, AddressTranslator.relativeTo(baseAddress));

            // 新语义序列化（文件偏移，allocator 即 AddressTranslator）
            long newAddr = allocator.allocate(indexSize);
            index.serializeWithOffsets(newAddr, allocator);

            // 断言一：单分段下新旧序列化字节完全一致 —— 旧文件即新文件，格式稳定
            for (int i = 0; i < indexSize; i++) {
                assertEquals(UnsafeOps.getByte(oldAddr + i), UnsafeOps.getByte(newAddr + i),
                        "单分段下新旧序列化字节应一致，偏移 " + i);
            }

            // 断言二：用「新代码」反序列化「旧格式」字节，地址应正确还原、值可读
            SegmentedHashIndex<String> restored = new SegmentedHashIndex<>(StringCodec.INSTANCE, 64, 16);
            restored.deserializeWithOffsets(oldAddr, indexSize, allocator);

            assertEquals(n, restored.size());
            for (int i = 0; i < n; i++) {
                long addr = restored.get("key-" + i);
                assertNotEquals(0L, addr, "key-" + i + " 应能在索引中找到");
                assertEquals(valueAddrs[i], addr, "key-" + i + " 还原出的地址应与原地址一致");
                assertEquals(1000L + i, UnsafeOps.getLong(addr), "key-" + i + " 还原出的值应正确");
            }
        } finally {
            allocator.close();
        }
    }

    /**
     * 端到端：单分段持久化文件（未触发扩容，与旧格式逐字节相同）经 close + 重新打开后数据完整。
     * 代表「旧的单分段文件升级后正常读取」的真实路径。
     */
    @Test
    public void testSingleSegmentPersistentReopen() {
        String path = testFile("single_seg");
        int count = 500;

        try (RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(path)
                .allocateSize(4 * 1024 * 1024)   // 足够大，不触发扩容 -> 单分段
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
                assertEquals("v-" + i, map.get("k-" + i), "k-" + i + " 重开后应一致");
            }
        }
    }
}
