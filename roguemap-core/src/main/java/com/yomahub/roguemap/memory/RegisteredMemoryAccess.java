package com.yomahub.roguemap.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class RegisteredMemoryAccess implements MemoryAccess {

    private static final int COPY_CHUNK_SIZE = 8192;
    private static final long BASE_ADDRESS_START = 1L << 40;
    private static final long REGION_GAP = 4096L;

    private final AtomicLong nextBaseAddress = new AtomicLong(BASE_ADDRESS_START);
    private final Object registrationLock = new Object();
    private final Object fenceLock = new Object();
    private volatile Region[] regions = new Region[0];

    @Override
    public long allocate(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("大小必须为正数: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("注册式内存访问单次分配不能超过 " + Integer.MAX_VALUE + " 字节: " + size);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) size).order(ByteOrder.nativeOrder());
        return register(buffer, true);
    }

    @Override
    public long reallocate(long address, long newSize) {
        if (newSize <= 0) {
            throw new IllegalArgumentException("大小必须为正数: " + newSize);
        }
        Region oldRegion = findRegion(address, 1);
        long newAddress = allocate(newSize);
        long copySize = Math.min(oldRegion.size - (address - oldRegion.baseAddress), newSize);
        copyMemory(address, newAddress, copySize);
        free(oldRegion.baseAddress);
        return newAddress;
    }

    @Override
    public void free(long address) {
        unregister(address, true);
    }

    @Override
    public void releaseDirectBufferAddress(long address) {
        unregister(address, false);
    }

    private void unregister(long address, boolean ownedOnly) {
        synchronized (registrationLock) {
            Region[] current = regions;
            List<Region> retained = new ArrayList<Region>(current.length);
            for (Region region : current) {
                if (region.baseAddress != address || (ownedOnly && !region.owned)) {
                    retained.add(region);
                }
            }
            regions = retained.toArray(new Region[retained.size()]);
        }
    }

    @Override
    public void setMemory(long address, long size, byte value) {
        checkSize(size);
        for (long i = 0; i < size; i++) {
            putByte(address + i, value);
        }
    }

    @Override
    public void copyMemory(long srcAddress, long dstAddress, long size) {
        checkSize(size);
        if (size == 0) {
            return;
        }
        Region source = findRegion(srcAddress, size);
        Region target = findRegion(dstAddress, size);
        if (source == target && dstAddress > srcAddress && dstAddress < srcAddress + size) {
            for (long i = size - 1; i >= 0; i--) {
                putByte(dstAddress + i, getByte(srcAddress + i));
            }
            return;
        }
        byte[] chunk = new byte[(int) Math.min(COPY_CHUNK_SIZE, size)];
        long copied = 0;
        while (copied < size) {
            int length = (int) Math.min(chunk.length, size - copied);
            copyToArray(srcAddress + copied, chunk, 0, length);
            copyFromArray(chunk, 0, dstAddress + copied, length);
            copied += length;
        }
    }

    @Override
    public void copyFromArray(byte[] src, int srcOffset, long dstAddress, int length) {
        checkArrayBounds(src.length, srcOffset, length);
        Region region = findRegion(dstAddress, length);
        ByteBuffer buffer = region.buffer.duplicate().order(ByteOrder.nativeOrder());
        buffer.position(region.offset(dstAddress));
        buffer.put(src, srcOffset, length);
    }

    @Override
    public void copyToArray(long srcAddress, byte[] dst, int dstOffset, int length) {
        checkArrayBounds(dst.length, dstOffset, length);
        Region region = findRegion(srcAddress, length);
        ByteBuffer buffer = region.buffer.duplicate().order(ByteOrder.nativeOrder());
        buffer.position(region.offset(srcAddress));
        buffer.get(dst, dstOffset, length);
    }

    @Override
    public byte getByte(long address) {
        Region region = findRegion(address, 1);
        return region.buffer.get(region.offset(address));
    }

    @Override
    public void putByte(long address, byte value) {
        Region region = findRegion(address, 1);
        region.buffer.put(region.offset(address), value);
    }

    @Override
    public short getShort(long address) {
        Region region = findRegion(address, 2);
        return region.buffer.getShort(region.offset(address));
    }

    @Override
    public void putShort(long address, short value) {
        Region region = findRegion(address, 2);
        region.buffer.putShort(region.offset(address), value);
    }

    @Override
    public int getInt(long address) {
        Region region = findRegion(address, 4);
        return region.buffer.getInt(region.offset(address));
    }

    @Override
    public void putInt(long address, int value) {
        Region region = findRegion(address, 4);
        region.buffer.putInt(region.offset(address), value);
    }

    @Override
    public long getLong(long address) {
        Region region = findRegion(address, 8);
        return region.buffer.getLong(region.offset(address));
    }

    @Override
    public void putLong(long address, long value) {
        Region region = findRegion(address, 8);
        region.buffer.putLong(region.offset(address), value);
    }

    @Override
    public float getFloat(long address) {
        Region region = findRegion(address, 4);
        return region.buffer.getFloat(region.offset(address));
    }

    @Override
    public void putFloat(long address, float value) {
        Region region = findRegion(address, 4);
        region.buffer.putFloat(region.offset(address), value);
    }

    @Override
    public double getDouble(long address) {
        Region region = findRegion(address, 8);
        return region.buffer.getDouble(region.offset(address));
    }

    @Override
    public void putDouble(long address, double value) {
        Region region = findRegion(address, 8);
        region.buffer.putDouble(region.offset(address), value);
    }

    @Override
    public int getIntVolatile(long address) {
        Region region = findRegion(address, 4);
        synchronized (region) {
            return region.buffer.getInt(region.offset(address));
        }
    }

    @Override
    public void putIntVolatile(long address, int value) {
        Region region = findRegion(address, 4);
        synchronized (region) {
            region.buffer.putInt(region.offset(address), value);
        }
    }

    @Override
    public long getLongVolatile(long address) {
        Region region = findRegion(address, 8);
        synchronized (region) {
            return region.buffer.getLong(region.offset(address));
        }
    }

    @Override
    public void putLongVolatile(long address, long value) {
        Region region = findRegion(address, 8);
        synchronized (region) {
            region.buffer.putLong(region.offset(address), value);
        }
    }

    @Override
    public boolean compareAndSwapInt(long address, int expected, int update) {
        Region region = findRegion(address, 4);
        synchronized (region) {
            int offset = region.offset(address);
            if (region.buffer.getInt(offset) != expected) {
                return false;
            }
            region.buffer.putInt(offset, update);
            return true;
        }
    }

    @Override
    public boolean compareAndSwapLong(long address, long expected, long update) {
        Region region = findRegion(address, 8);
        synchronized (region) {
            int offset = region.offset(address);
            if (region.buffer.getLong(offset) != expected) {
                return false;
            }
            region.buffer.putLong(offset, update);
            return true;
        }
    }

    @Override
    public long getDirectBufferAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer 必须是 direct 类型");
        }
        return register(buffer, false);
    }

    @Override
    public void fullFence() {
        synchronized (fenceLock) {
            // monitor enter/exit provides a conservative JVM memory barrier.
        }
    }

    @Override
    public void loadFence() {
        synchronized (fenceLock) {
            // monitor enter/exit provides a conservative JVM memory barrier.
        }
    }

    @Override
    public void storeFence() {
        synchronized (fenceLock) {
            // monitor enter/exit provides a conservative JVM memory barrier.
        }
    }

    private long register(ByteBuffer buffer, boolean owned) {
        ByteBuffer nativeOrderBuffer = buffer.duplicate().order(ByteOrder.nativeOrder());
        int size = nativeOrderBuffer.capacity();
        long baseAddress = nextBaseAddress.getAndAdd(size + REGION_GAP);
        Region region = new Region(baseAddress, size, nativeOrderBuffer, owned);
        synchronized (registrationLock) {
            Region[] current = regions;
            Region[] updated = new Region[current.length + 1];
            System.arraycopy(current, 0, updated, 0, current.length);
            updated[current.length] = region;
            regions = updated;
        }
        return baseAddress;
    }

    private Region findRegion(long address, long length) {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("访问长度非法: " + length);
        }
        Region[] snapshot = regions;
        for (Region region : snapshot) {
            if (region.contains(address, length)) {
                return region;
            }
        }
        throw new IllegalArgumentException("地址未注册或访问越界: " + address + ", length=" + length);
    }

    private void checkSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("大小非法: " + size);
        }
    }

    private void checkArrayBounds(int arrayLength, int offset, int length) {
        if (offset < 0 || length < 0 || length > arrayLength - offset) {
            throw new IndexOutOfBoundsException("数组访问越界: offset=" + offset + ", length=" + length);
        }
    }

    private static final class Region {
        private final long baseAddress;
        private final long size;
        private final ByteBuffer buffer;
        private final boolean owned;

        private Region(long baseAddress, long size, ByteBuffer buffer, boolean owned) {
            this.baseAddress = baseAddress;
            this.size = size;
            this.buffer = buffer;
            this.owned = owned;
        }

        private boolean contains(long address, long length) {
            long offset = address - baseAddress;
            return offset >= 0 && offset + length <= size;
        }

        private int offset(long address) {
            return (int) (address - baseAddress);
        }
    }
}
