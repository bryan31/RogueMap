package com.yomahub.roguemap.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TempFileManagerTest {

    @Test
    void registeredMemoryAccessSkipsUnsafeCleanerOnJdk25() {
        assertEquals("gc-only", TempFileManager.selectUnmapStrategy("registered", 8));
        assertEquals("gc-only", TempFileManager.selectUnmapStrategy("registered", 25));
        assertEquals("gc-only", TempFileManager.selectUnmapStrategy("registered", 26));
    }

    @Test
    void unsafeMemoryAccessKeepsExistingUnmapStrategy() {
        assertEquals("unsafe-invoke-cleaner", TempFileManager.selectUnmapStrategy("unsafe", 25));
        assertEquals("unsafe-invoke-cleaner", TempFileManager.selectUnmapStrategy("unsafe", 11));
        assertEquals("direct-cleaner", TempFileManager.selectUnmapStrategy("unsafe", 8));
    }
}
