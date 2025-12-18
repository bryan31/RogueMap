package com.yomahub.roguemap.memory;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * 使用 sun.misc.Unsafe 实现的底层内存操作（兼容 Java 8）
 *
 * 本类提供直接内存访问操作，用于高性能堆外存储。
 * 使用 Unsafe 实现零开销的内存操作。
 */
public class UnsafeOps {

    private static final Unsafe UNSAFE;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Unsafe instance", e);
        }
    }

    /**
     * 分配堆外内存
     *
     * @param size 字节大小
     * @return 内存地址
     */
    public static long allocate(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        long address = UNSAFE.allocateMemory(size);
        if (address == 0) {
            throw new OutOfMemoryError("Failed to allocate " + size + " bytes");
        }
        return address;
    }

    /**
     * 重新分配内存到新大小
     *
     * @param address 原内存地址
     * @param newSize 新大小（字节）
     * @return 新内存地址
     */
    public static long reallocate(long address, long newSize) {
        if (newSize <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + newSize);
        }
        long newAddress = UNSAFE.reallocateMemory(address, newSize);
        if (newAddress == 0) {
            throw new OutOfMemoryError("Failed to reallocate to " + newSize + " bytes");
        }
        return newAddress;
    }

    /**
     * 释放已分配的内存
     *
     * @param address 要释放的内存地址
     */
    public static void free(long address) {
        if (address != 0) {
            UNSAFE.freeMemory(address);
        }
    }

    /**
     * 将内存区域设置为特定字节值
     *
     * @param address 起始地址
     * @param size 字节数
     * @param value 要设置的字节值
     */
    public static void setMemory(long address, long size, byte value) {
        UNSAFE.setMemory(address, size, value);
    }

    /**
     * 从一个地址复制内存到另一个地址
     *
     * @param srcAddress 源地址
     * @param dstAddress 目标地址
     * @param size 要复制的字节数
     */
    public static void copyMemory(long srcAddress, long dstAddress, long size) {
        UNSAFE.copyMemory(srcAddress, dstAddress, size);
    }

    /**
     * 从字节数组复制内存到堆外内存
     *
     * @param src 源字节数组
     * @param srcOffset 源数组中的偏移量
     * @param dstAddress 目标地址
     * @param length 要复制的字节数
     */
    public static void copyFromArray(byte[] src, int srcOffset, long dstAddress, int length) {
        UNSAFE.copyMemory(src, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, null, dstAddress, length);
    }

    /**
     * 从堆外内存复制内存到字节数组
     *
     * @param srcAddress 源地址
     * @param dst 目标字节数组
     * @param dstOffset 目标数组中的偏移量
     * @param length 要复制的字节数
     */
    public static void copyToArray(long srcAddress, byte[] dst, int dstOffset, int length) {
        UNSAFE.copyMemory(null, srcAddress, dst, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length);
    }

    // 原始类型操作

    public static byte getByte(long address) {
        return UNSAFE.getByte(address);
    }

    public static void putByte(long address, byte value) {
        UNSAFE.putByte(address, value);
    }

    public static short getShort(long address) {
        return UNSAFE.getShort(address);
    }

    public static void putShort(long address, short value) {
        UNSAFE.putShort(address, value);
    }

    public static int getInt(long address) {
        return UNSAFE.getInt(address);
    }

    public static void putInt(long address, int value) {
        UNSAFE.putInt(address, value);
    }

    public static long getLong(long address) {
        return UNSAFE.getLong(address);
    }

    public static void putLong(long address, long value) {
        UNSAFE.putLong(address, value);
    }

    public static float getFloat(long address) {
        return UNSAFE.getFloat(address);
    }

    public static void putFloat(long address, float value) {
        UNSAFE.putFloat(address, value);
    }

    public static double getDouble(long address) {
        return UNSAFE.getDouble(address);
    }

    public static void putDouble(long address, double value) {
        UNSAFE.putDouble(address, value);
    }

    /**
     * Volatile 读取操作，用于并发访问
     */
    public static int getIntVolatile(long address) {
        return UNSAFE.getIntVolatile(null, address);
    }

    public static void putIntVolatile(long address, int value) {
        UNSAFE.putIntVolatile(null, address, value);
    }

    public static long getLongVolatile(long address) {
        return UNSAFE.getLongVolatile(null, address);
    }

    public static void putLongVolatile(long address, long value) {
        UNSAFE.putLongVolatile(null, address, value);
    }

    /**
     * 比较并交换操作，用于无锁算法
     */
    public static boolean compareAndSwapInt(long address, int expected, int update) {
        return UNSAFE.compareAndSwapInt(null, address, expected, update);
    }

    public static boolean compareAndSwapLong(long address, long expected, long update) {
        return UNSAFE.compareAndSwapLong(null, address, expected, update);
    }

    /**
     * 从 DirectByteBuffer 获取地址
     *
     * @param buffer DirectByteBuffer 实例
     * @return 内存地址
     */
    public static long getDirectBufferAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }
        return ((sun.nio.ch.DirectBuffer) buffer).address();
    }

    /**
     * 内存屏障 - 确保所有加载/存储操作在线程间可见
     */
    public static void fullFence() {
        UNSAFE.fullFence();
    }

    public static void loadFence() {
        UNSAFE.loadFence();
    }

    public static void storeFence() {
        UNSAFE.storeFence();
    }
}
