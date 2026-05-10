package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAccessStrategyTest {

    @Test
    void autoModeKeepsUnsafeBeforeJdk25() {
        assertEquals("unsafe", UnsafeOps.selectMemoryAccessMode("auto", 8));
        assertEquals("unsafe", UnsafeOps.selectMemoryAccessMode(null, 21));
        assertEquals("unsafe", UnsafeOps.selectMemoryAccessMode("", 24));
    }

    @Test
    void autoModeUsesRegisteredMemoryOnJdk25AndLater() {
        assertEquals("registered", UnsafeOps.selectMemoryAccessMode("auto", 25));
        assertEquals("registered", UnsafeOps.selectMemoryAccessMode(null, 26));
    }

    @Test
    void explicitModeOverridesAutoSelection() {
        assertEquals("unsafe", UnsafeOps.selectMemoryAccessMode("unsafe", 25));
        assertEquals("registered", UnsafeOps.selectMemoryAccessMode("registered", 8));
    }

    @Test
    void registeredMemoryAccessReadsAndWritesDirectBuffer() {
        RegisteredMemoryAccess access = new RegisteredMemoryAccess();
        ByteBuffer buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        long address = access.getDirectBufferAddress(buffer);

        access.putByte(address, (byte) 7);
        access.putShort(address + 1, (short) 300);
        access.putInt(address + 4, 123456);
        access.putLong(address + 8, 123456789L);
        access.putFloat(address + 16, 3.5f);
        access.putDouble(address + 24, 9.25d);

        assertEquals((byte) 7, access.getByte(address));
        assertEquals((short) 300, access.getShort(address + 1));
        assertEquals(123456, access.getInt(address + 4));
        assertEquals(123456789L, access.getLong(address + 8));
        assertEquals(3.5f, access.getFloat(address + 16), 0.0001f);
        assertEquals(9.25d, access.getDouble(address + 24), 0.0001d);
    }

    @Test
    void registeredMemoryAccessCopiesBetweenMemoryAndArrays() {
        RegisteredMemoryAccess access = new RegisteredMemoryAccess();
        long address = access.allocate(16);
        try {
            byte[] source = new byte[]{1, 2, 3, 4, 5, 6};
            byte[] target = new byte[source.length];

            access.copyFromArray(source, 0, address + 2, source.length);
            access.copyToArray(address + 2, target, 0, target.length);

            assertArrayEquals(source, target);
        } finally {
            access.free(address);
        }
    }

    @Test
    void registeredMemoryAccessCopiesBetweenRegisteredRegions() {
        RegisteredMemoryAccess access = new RegisteredMemoryAccess();
        long source = access.allocate(32);
        long target = access.allocate(32);
        try {
            access.setMemory(source, 32, (byte) 0);
            access.setMemory(target, 32, (byte) 0);
            access.putInt(source + 3, 0x01020304);

            access.copyMemory(source, target, 16);

            assertEquals(0x01020304, access.getInt(target + 3));
        } finally {
            access.free(source);
            access.free(target);
        }
    }

    @Test
    void registeredMemoryAccessCopiesOverlappingRangeLikeMemmove() {
        RegisteredMemoryAccess access = new RegisteredMemoryAccess();
        long address = access.allocate(16);
        try {
            for (int i = 0; i < 8; i++) {
                access.putByte(address + i, (byte) (i + 1));
            }

            access.copyMemory(address, address + 1, 8);

            assertEquals((byte) 1, access.getByte(address));
            for (int i = 0; i < 8; i++) {
                assertEquals((byte) (i + 1), access.getByte(address + 1 + i));
            }
        } finally {
            access.free(address);
        }
    }

    @Test
    void registeredMemoryAccessReallocatePreservesExistingBytes() {
        RegisteredMemoryAccess access = new RegisteredMemoryAccess();
        long address = access.allocate(8);
        try {
            access.putLong(address, 0x0102030405060708L);
            long resized = access.reallocate(address, 16);
            address = resized;

            assertEquals(0x0102030405060708L, access.getLong(resized));
            access.putLong(resized + 8, 0x1112131415161718L);
            assertEquals(0x1112131415161718L, access.getLong(resized + 8));
        } finally {
            access.free(address);
        }
    }

    @Test
    void registeredMemoryAccessSupportsVolatileAndCasMethods() {
        RegisteredMemoryAccess access = new RegisteredMemoryAccess();
        long address = access.allocate(16);
        try {
            access.putIntVolatile(address, 100);
            assertEquals(100, access.getIntVolatile(address));
            assertTrue(access.compareAndSwapInt(address, 100, 200));
            assertFalse(access.compareAndSwapInt(address, 100, 300));
            assertEquals(200, access.getInt(address));

            access.putLongVolatile(address + 8, 400L);
            assertEquals(400L, access.getLongVolatile(address + 8));
            assertTrue(access.compareAndSwapLong(address + 8, 400L, 500L));
            assertEquals(500L, access.getLong(address + 8));
        } finally {
            access.free(address);
        }
    }
}
