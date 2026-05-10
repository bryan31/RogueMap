package com.yomahub.roguemap.memory;

import java.nio.ByteBuffer;

/**
 * 底层内存操作门面。
 *
 * <p>Java 8-24 默认保持原有 sun.misc.Unsafe 实现，避免改变既有性能和行为。
 * Java 25+ 默认切换到注册式 ByteBuffer 后端，避开 JDK 25 对 Unsafe 内存访问方法的运行时告警。
 * 可通过 -Droguemap.memory.access=unsafe 或 -Droguemap.memory.access=registered 显式指定后端。</p>
 */
public class UnsafeOps {

    private static final String ACCESS_MODE_PROPERTY = "roguemap.memory.access";
    private static final String MODE_AUTO = "auto";
    private static final String MODE_UNSAFE = "unsafe";
    private static final String MODE_REGISTERED = "registered";

    private static final MemoryAccess ACCESS = createMemoryAccess();

    private UnsafeOps() {
    }

    static String selectMemoryAccessMode(String configuredMode, int featureVersion) {
        String mode = configuredMode;
        if (mode == null || mode.trim().isEmpty()) {
            mode = MODE_AUTO;
        }
        mode = mode.trim().toLowerCase();
        if (MODE_UNSAFE.equals(mode) || MODE_REGISTERED.equals(mode)) {
            return mode;
        }
        if (!MODE_AUTO.equals(mode)) {
            throw new IllegalArgumentException("未知的 RogueMap 内存访问模式: " + configuredMode
                    + "（支持 auto、unsafe、registered）");
        }
        return featureVersion >= 25 ? MODE_REGISTERED : MODE_UNSAFE;
    }

    public static String memoryAccessMode() {
        return ACCESS instanceof RegisteredMemoryAccess ? MODE_REGISTERED : MODE_UNSAFE;
    }

    private static MemoryAccess createMemoryAccess() {
        String mode = selectMemoryAccessMode(System.getProperty(ACCESS_MODE_PROPERTY), currentFeatureVersion());
        if (MODE_REGISTERED.equals(mode)) {
            return new RegisteredMemoryAccess();
        }
        return new UnsafeMemoryAccess();
    }

    private static int currentFeatureVersion() {
        String specVersion = System.getProperty("java.specification.version", "8");
        if (specVersion.startsWith("1.")) {
            specVersion = specVersion.substring(2);
        }
        int dot = specVersion.indexOf('.');
        if (dot >= 0) {
            specVersion = specVersion.substring(0, dot);
        }
        try {
            return Integer.parseInt(specVersion);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /**
     * 分配堆外内存
     *
     * @param size 字节大小
     * @return 内存地址
     */
    public static long allocate(long size) {
        return ACCESS.allocate(size);
    }

    /**
     * 重新分配内存到新大小
     *
     * @param address 原内存地址
     * @param newSize 新大小（字节）
     * @return 新内存地址
     */
    public static long reallocate(long address, long newSize) {
        return ACCESS.reallocate(address, newSize);
    }

    /**
     * 释放已分配的内存
     *
     * @param address 要释放的内存地址
     */
    public static void free(long address) {
        ACCESS.free(address);
    }

    /**
     * 将内存区域设置为特定字节值
     *
     * @param address 起始地址
     * @param size 字节数
     * @param value 要设置的字节值
     */
    public static void setMemory(long address, long size, byte value) {
        ACCESS.setMemory(address, size, value);
    }

    /**
     * 从一个地址复制内存到另一个地址
     *
     * @param srcAddress 源地址
     * @param dstAddress 目标地址
     * @param size 要复制的字节数
     */
    public static void copyMemory(long srcAddress, long dstAddress, long size) {
        ACCESS.copyMemory(srcAddress, dstAddress, size);
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
        ACCESS.copyFromArray(src, srcOffset, dstAddress, length);
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
        ACCESS.copyToArray(srcAddress, dst, dstOffset, length);
    }

    public static byte getByte(long address) {
        return ACCESS.getByte(address);
    }

    public static void putByte(long address, byte value) {
        ACCESS.putByte(address, value);
    }

    public static short getShort(long address) {
        return ACCESS.getShort(address);
    }

    public static void putShort(long address, short value) {
        ACCESS.putShort(address, value);
    }

    public static int getInt(long address) {
        return ACCESS.getInt(address);
    }

    public static void putInt(long address, int value) {
        ACCESS.putInt(address, value);
    }

    public static long getLong(long address) {
        return ACCESS.getLong(address);
    }

    public static void putLong(long address, long value) {
        ACCESS.putLong(address, value);
    }

    public static float getFloat(long address) {
        return ACCESS.getFloat(address);
    }

    public static void putFloat(long address, float value) {
        ACCESS.putFloat(address, value);
    }

    public static double getDouble(long address) {
        return ACCESS.getDouble(address);
    }

    public static void putDouble(long address, double value) {
        ACCESS.putDouble(address, value);
    }

    /**
     * Volatile 读取操作，用于并发访问
     */
    public static int getIntVolatile(long address) {
        return ACCESS.getIntVolatile(address);
    }

    public static void putIntVolatile(long address, int value) {
        ACCESS.putIntVolatile(address, value);
    }

    public static long getLongVolatile(long address) {
        return ACCESS.getLongVolatile(address);
    }

    public static void putLongVolatile(long address, long value) {
        ACCESS.putLongVolatile(address, value);
    }

    /**
     * 比较并交换操作，用于无锁算法
     */
    public static boolean compareAndSwapInt(long address, int expected, int update) {
        return ACCESS.compareAndSwapInt(address, expected, update);
    }

    public static boolean compareAndSwapLong(long address, long expected, long update) {
        return ACCESS.compareAndSwapLong(address, expected, update);
    }

    /**
     * 从 DirectByteBuffer 获取地址
     *
     * @param buffer DirectByteBuffer 实例
     * @return 内存地址
     */
    public static long getDirectBufferAddress(ByteBuffer buffer) {
        return ACCESS.getDirectBufferAddress(buffer);
    }

    static void releaseDirectBufferAddress(long address) {
        ACCESS.releaseDirectBufferAddress(address);
    }

    /**
     * 内存屏障 - 确保所有加载/存储操作在线程间可见
     */
    public static void fullFence() {
        ACCESS.fullFence();
    }

    public static void loadFence() {
        ACCESS.loadFence();
    }

    public static void storeFence() {
        ACCESS.storeFence();
    }

    /**
     * 写入float数组到内存
     */
    public static void putFloatArray(long address, float[] array) {
        for (int i = 0; i < array.length; i++) {
            ACCESS.putFloat(address + (i * 4L), array[i]);
        }
    }

    /**
     * 从内存读取float数组
     */
    public static float[] getFloatArray(long address, int length) {
        float[] array = new float[length];
        for (int i = 0; i < length; i++) {
            array[i] = ACCESS.getFloat(address + (i * 4L));
        }
        return array;
    }
}
