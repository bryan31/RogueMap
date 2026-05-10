package com.yomahub.roguemap.memory;

import java.nio.ByteBuffer;

interface MemoryAccess {

    long allocate(long size);

    long reallocate(long address, long newSize);

    void free(long address);

    void setMemory(long address, long size, byte value);

    void copyMemory(long srcAddress, long dstAddress, long size);

    void copyFromArray(byte[] src, int srcOffset, long dstAddress, int length);

    void copyToArray(long srcAddress, byte[] dst, int dstOffset, int length);

    byte getByte(long address);

    void putByte(long address, byte value);

    short getShort(long address);

    void putShort(long address, short value);

    int getInt(long address);

    void putInt(long address, int value);

    long getLong(long address);

    void putLong(long address, long value);

    float getFloat(long address);

    void putFloat(long address, float value);

    double getDouble(long address);

    void putDouble(long address, double value);

    int getIntVolatile(long address);

    void putIntVolatile(long address, int value);

    long getLongVolatile(long address);

    void putLongVolatile(long address, long value);

    boolean compareAndSwapInt(long address, int expected, int update);

    boolean compareAndSwapLong(long address, long expected, long update);

    long getDirectBufferAddress(ByteBuffer buffer);

    void releaseDirectBufferAddress(long address);

    void fullFence();

    void loadFence();

    void storeFence();
}
