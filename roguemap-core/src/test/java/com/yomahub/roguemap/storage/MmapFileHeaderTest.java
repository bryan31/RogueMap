package com.yomahub.roguemap.storage;

import com.yomahub.roguemap.memory.MmapAllocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MmapFileHeaderTest {

    @Test
    void ordinalRegistryOffsetReadWrite() throws Exception {
        Path tempFile = Files.createTempFile("header-test", ".dat");
        try {
            MmapAllocator allocator = new MmapAllocator(tempFile.toString(), 8192, false);
            long baseAddress = allocator.getBaseAddress();

            MmapFileHeader.setOrdinalRegistryOffset(baseAddress, 123456L);
            assertEquals(123456L, MmapFileHeader.getOrdinalRegistryOffset(baseAddress));

            MmapFileHeader.setOrdinalRegistryOffset(baseAddress, 0L);
            assertEquals(0L, MmapFileHeader.getOrdinalRegistryOffset(baseAddress));

            allocator.close();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
