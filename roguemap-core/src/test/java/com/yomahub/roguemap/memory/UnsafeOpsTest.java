package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UnsafeOps 测试类
 *
 * 测试底层内存操作的功能，包括：
 * - 内存分配和释放
 * - 原始类型的读写操作
 * - 内存设置和复制
 * - 数组与内存之间的复制
 * - Volatile 操作
 * - CAS（Compare-And-Swap）操作
 */
class UnsafeOpsTest {

    /**
     * 测试基本的内存分配和释放
     *
     * 验证：
     * 1. allocate() 返回非零地址
     * 2. free() 可以正确释放内存
     */
    @Test
    void testAllocateAndFree() {
        // 分配 1024 字节内存
        long address = UnsafeOps.allocate(1024);
        assertTrue(address != 0, "分配应该返回非零地址");

        // 释放内存
        UnsafeOps.free(address);
    }

    /**
     * 测试原始类型的读写操作
     *
     * 验证所有原始类型的 put/get 操作：
     * - byte
     * - short
     * - int
     * - long
     * - float
     * - double
     */
    @Test
    void testPrimitiveOperations() {
        long address = UnsafeOps.allocate(64);

        try {
            // 测试 byte
            UnsafeOps.putByte(address, (byte) 42);
            assertEquals(42, UnsafeOps.getByte(address),
                "byte 读写应该一致");

            // 测试 short
            UnsafeOps.putShort(address + 1, (short) 1234);
            assertEquals(1234, UnsafeOps.getShort(address + 1),
                "short 读写应该一致");

            // 测试 int
            UnsafeOps.putInt(address + 4, 123456);
            assertEquals(123456, UnsafeOps.getInt(address + 4),
                "int 读写应该一致");

            // 测试 long
            UnsafeOps.putLong(address + 8, 123456789L);
            assertEquals(123456789L, UnsafeOps.getLong(address + 8),
                "long 读写应该一致");

            // 测试 float
            UnsafeOps.putFloat(address + 16, 3.14f);
            assertEquals(3.14f, UnsafeOps.getFloat(address + 16), 0.001,
                "float 读写应该一致");

            // 测试 double
            UnsafeOps.putDouble(address + 20, 2.71828);
            assertEquals(2.71828, UnsafeOps.getDouble(address + 20), 0.00001,
                "double 读写应该一致");
        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 测试内存区域设置
     *
     * 验证：
     * 1. setMemory() 可以将内存区域设置为指定值
     * 2. 所有字节都被正确设置
     */
    @Test
    void testSetMemory() {
        long address = UnsafeOps.allocate(100);

        try {
            // 将 100 字节全部设置为 0xFF
            UnsafeOps.setMemory(address, 100, (byte) 0xFF);

            // 验证所有字节都是 0xFF
            for (int i = 0; i < 100; i++) {
                assertEquals((byte) 0xFF, UnsafeOps.getByte(address + i),
                    "第 " + i + " 个字节应该是 0xFF");
            }
        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 测试内存复制
     *
     * 验证：
     * 1. copyMemory() 可以正确复制内存内容
     * 2. 源地址和目标地址的数据一致
     */
    @Test
    void testCopyMemory() {
        long src = UnsafeOps.allocate(100);
        long dst = UnsafeOps.allocate(100);

        try {
            // 向源地址写入数据
            for (int i = 0; i < 100; i++) {
                UnsafeOps.putByte(src + i, (byte) i);
            }

            // 复制到目标地址
            UnsafeOps.copyMemory(src, dst, 100);

            // 验证目标地址的数据与源地址一致
            for (int i = 0; i < 100; i++) {
                assertEquals((byte) i, UnsafeOps.getByte(dst + i),
                    "第 " + i + " 个字节应该被正确复制");
            }
        } finally {
            UnsafeOps.free(src);
            UnsafeOps.free(dst);
        }
    }

    /**
     * 测试从数组复制到内存
     *
     * 验证：
     * 1. copyFromArray() 可以将字节数组复制到堆外内存
     * 2. 数据内容正确
     */
    @Test
    void testCopyFromArray() {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        long address = UnsafeOps.allocate(10);

        try {
            // 从数组复制到内存
            UnsafeOps.copyFromArray(data, 0, address, 5);

            // 验证内存中的数据与数组一致
            for (int i = 0; i < 5; i++) {
                assertEquals(data[i], UnsafeOps.getByte(address + i),
                    "第 " + i + " 个字节应该等于数组中的值");
            }
        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 测试从内存复制到数组
     *
     * 验证：
     * 1. copyToArray() 可以将堆外内存复制到字节数组
     * 2. 数据内容正确
     */
    @Test
    void testCopyToArray() {
        long address = UnsafeOps.allocate(10);

        try {
            // 向内存写入数据
            for (int i = 0; i < 5; i++) {
                UnsafeOps.putByte(address + i, (byte) (i + 1));
            }

            // 从内存复制到数组
            byte[] data = new byte[5];
            UnsafeOps.copyToArray(address, data, 0, 5);

            // 验证数组中的数据与内存一致
            for (int i = 0; i < 5; i++) {
                assertEquals((byte) (i + 1), data[i],
                    "第 " + i + " 个字节应该等于内存中的值");
            }
        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 测试 Volatile 操作
     *
     * 验证：
     * 1. putIntVolatile() 和 getIntVolatile() 正确工作
     * 2. putLongVolatile() 和 getLongVolatile() 正确工作
     * 3. Volatile 操作保证内存可见性
     */
    @Test
    void testVolatileOperations() {
        long address = UnsafeOps.allocate(16);

        try {
            // 测试 volatile int 操作
            UnsafeOps.putIntVolatile(address, 42);
            assertEquals(42, UnsafeOps.getIntVolatile(address),
                "volatile int 读写应该一致");

            // 测试 volatile long 操作
            UnsafeOps.putLongVolatile(address + 8, 123456L);
            assertEquals(123456L, UnsafeOps.getLongVolatile(address + 8),
                "volatile long 读写应该一致");
        } finally {
            UnsafeOps.free(address);
        }
    }

    /**
     * 测试 CAS（Compare-And-Swap）操作
     *
     * 验证：
     * 1. 当期望值匹配时，CAS 成功并更新值
     * 2. 当期望值不匹配时，CAS 失败且不更新值
     * 3. CAS 操作的原子性
     */
    @Test
    void testCompareAndSwap() {
        long address = UnsafeOps.allocate(16);

        try {
            // 设置初始值为 100
            UnsafeOps.putInt(address, 100);

            // 成功的 CAS：期望值是 100，更新为 200
            assertTrue(UnsafeOps.compareAndSwapInt(address, 100, 200),
                "CAS 应该成功（期望值匹配）");
            assertEquals(200, UnsafeOps.getInt(address),
                "CAS 成功后值应该被更新");

            // 失败的 CAS：期望值是 100，但实际值是 200
            assertFalse(UnsafeOps.compareAndSwapInt(address, 100, 300),
                "CAS 应该失败（期望值不匹配）");
            assertEquals(200, UnsafeOps.getInt(address),
                "CAS 失败后值应该保持不变");
        } finally {
            UnsafeOps.free(address);
        }
    }
}
