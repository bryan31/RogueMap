package com.yomahub.roguemap.memory;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

final class UnsafeMemoryAccess implements MemoryAccess {

    private static final Unsafe UNSAFE;
    private static final long BUFFER_ADDRESS_OFFSET;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("获取 Unsafe 实例失败", e);
        }
        try {
            Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(addressField);
        } catch (Exception e) {
            throw new RuntimeException("无法获取 Buffer.address 字段偏移量", e);
        }
    }

    @Override
    public long allocate(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("大小必须为正数: " + size);
        }
        long address = UNSAFE.allocateMemory(size);
        if (address == 0) {
            throw new OutOfMemoryError("分配 " + size + " 字节失败");
        }
        return address;
    }

    @Override
    public long reallocate(long address, long newSize) {
        if (newSize <= 0) {
            throw new IllegalArgumentException("大小必须为正数: " + newSize);
        }
        long newAddress = UNSAFE.reallocateMemory(address, newSize);
        if (newAddress == 0) {
            throw new OutOfMemoryError("重新分配到 " + newSize + " 字节失败");
        }
        return newAddress;
    }

    @Override
    public void free(long address) {
        if (address != 0) {
            UNSAFE.freeMemory(address);
        }
    }

    @Override
    public void setMemory(long address, long size, byte value) {
        UNSAFE.setMemory(address, size, value);
    }

    @Override
    public void copyMemory(long srcAddress, long dstAddress, long size) {
        UNSAFE.copyMemory(srcAddress, dstAddress, size);
    }

    @Override
    public void copyFromArray(byte[] src, int srcOffset, long dstAddress, int length) {
        UNSAFE.copyMemory(src, Unsafe.ARRAY_BYTE_BASE_OFFSET + srcOffset, null, dstAddress, length);
    }

    @Override
    public void copyToArray(long srcAddress, byte[] dst, int dstOffset, int length) {
        UNSAFE.copyMemory(null, srcAddress, dst, Unsafe.ARRAY_BYTE_BASE_OFFSET + dstOffset, length);
    }

    @Override
    public byte getByte(long address) {
        return UNSAFE.getByte(address);
    }

    @Override
    public void putByte(long address, byte value) {
        UNSAFE.putByte(address, value);
    }

    @Override
    public short getShort(long address) {
        return UNSAFE.getShort(address);
    }

    @Override
    public void putShort(long address, short value) {
        UNSAFE.putShort(address, value);
    }

    @Override
    public int getInt(long address) {
        return UNSAFE.getInt(address);
    }

    @Override
    public void putInt(long address, int value) {
        UNSAFE.putInt(address, value);
    }

    @Override
    public long getLong(long address) {
        return UNSAFE.getLong(address);
    }

    @Override
    public void putLong(long address, long value) {
        UNSAFE.putLong(address, value);
    }

    @Override
    public float getFloat(long address) {
        return UNSAFE.getFloat(address);
    }

    @Override
    public void putFloat(long address, float value) {
        UNSAFE.putFloat(address, value);
    }

    @Override
    public double getDouble(long address) {
        return UNSAFE.getDouble(address);
    }

    @Override
    public void putDouble(long address, double value) {
        UNSAFE.putDouble(address, value);
    }

    @Override
    public int getIntVolatile(long address) {
        return UNSAFE.getIntVolatile(null, address);
    }

    @Override
    public void putIntVolatile(long address, int value) {
        UNSAFE.putIntVolatile(null, address, value);
    }

    @Override
    public long getLongVolatile(long address) {
        return UNSAFE.getLongVolatile(null, address);
    }

    @Override
    public void putLongVolatile(long address, long value) {
        UNSAFE.putLongVolatile(null, address, value);
    }

    @Override
    public boolean compareAndSwapInt(long address, int expected, int update) {
        return UNSAFE.compareAndSwapInt(null, address, expected, update);
    }

    @Override
    public boolean compareAndSwapLong(long address, long expected, long update) {
        return UNSAFE.compareAndSwapLong(null, address, expected, update);
    }

    @Override
    public long getDirectBufferAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer 必须是 direct 类型");
        }
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }

    @Override
    public void releaseDirectBufferAddress(long address) {
        // Unsafe 后端返回真实 native address，不持有需要释放的注册引用。
    }

    @Override
    public void fullFence() {
        UNSAFE.fullFence();
    }

    @Override
    public void loadFence() {
        UNSAFE.loadFence();
    }

    @Override
    public void storeFence() {
        UNSAFE.storeFence();
    }
}
