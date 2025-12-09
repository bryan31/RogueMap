package com.yomahub.roguemap.mmap;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时文件模式测试
 */
public class MmapTemporaryTest {

    @Test
    public void testTemporaryFileBasicOperations() {
        String tempFilePath;

        try (RogueMap<Long, String> map = RogueMap.<Long, String>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(StringCodec.INSTANCE)
                .allocateSize(100 * 1024 * 1024)
                .build()) {

            tempFilePath = ((com.yomahub.roguemap.storage.MmapStorage) map.getStorage())
                    .getAllocator().getFilePath();
            System.out.println("临时文件路径: " + tempFilePath);

            File tempFile = new File(tempFilePath);
            assertTrue(tempFile.exists(), "临时文件应该存在");

            map.put(1L, "Hello");
            map.put(2L, "World");

            assertEquals("Hello", map.get(1L));
            assertEquals("World", map.get(2L));
            assertEquals(2, map.size());
        }

        File tempFile = new File(tempFilePath);
        System.out.println("临时文件状态: " + (tempFile.exists() ? "仍存在" : "已删除"));
    }

    @Test
    public void testTemporaryFilePerformance() {
        try (RogueMap<Long, String> map = RogueMap.<Long, String>mmap()
                .temporary()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(StringCodec.INSTANCE)
                .primitiveIndex()
                .allocateSize(200 * 1024 * 1024)
                .build()) {

            int operations = 10000;

            for (long i = 1; i <= operations; i++) {
                map.put(i, "Value-" + i);
            }

            for (long i = 1; i <= operations; i++) {
                String value = map.get(i);
                assertNotNull(value);
            }

            System.out.println("成功测试 " + operations + " 次操作");
        }
    }
}
