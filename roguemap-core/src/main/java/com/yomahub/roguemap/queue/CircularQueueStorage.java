package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * 基于环形缓冲区的有界队列存储实现
 *
 * 环形缓冲区结构：
 * [header(32B)][slot0][slot1]...[slotN-1]
 *
 * Header结构：
 * [headIdx(4B)][tailIdx(4B)][capacity(4B)][count(4B)][slotSize(4B)][reserved(8B)]
 *
 * Slot结构：
 * [elementSize(4B)][element data(maxSlotSize bytes)]
 */
public class CircularQueueStorage<E> implements QueueStorage<E> {

    // 存储类型标识
    public static final int STORAGE_TYPE = 4; // DATA_TYPE_QUEUE_CIRCULAR

    // Header 大小
    public static final int HEADER_SIZE = 32;

    // Header 字段位置
    public static final int HEAD_IDX_POS = 0;
    public static final int TAIL_IDX_POS = 4;
    public static final int CAPACITY_POS = 8;
    public static final int COUNT_POS = 12;
    public static final int SLOT_SIZE_POS = 16;

    // Slot 头大小
    public static final int SLOT_HEADER_SIZE = 4;

    private final Allocator allocator;
    private final Codec<E> elementCodec;
    private final long bufferAddress;   // 缓冲区起始地址（header之后）
    private final int capacity;         // 队列容量
    private final int slotSize;         // 每个slot的大小（含头）
    private final int maxElementSize;   // 最大元素大小

    // 状态变量（从内存读取/写入）
    private final AtomicInteger size;

    // 并发控制
    private final StampedLock lock;

    /**
     * 创建环形队列
     *
     * @param allocator       内存分配器
     * @param elementCodec    元素编解码器
     * @param capacity        队列容量
     * @param maxElementSize  最大元素大小
     */
    public CircularQueueStorage(Allocator allocator, Codec<E> elementCodec,
                                int capacity, int maxElementSize) {
        this.allocator = allocator;
        this.elementCodec = elementCodec;
        this.capacity = capacity;
        this.maxElementSize = maxElementSize;
        this.slotSize = SLOT_HEADER_SIZE + maxElementSize;
        this.size = new AtomicInteger(0);
        this.lock = new StampedLock();

        // 分配缓冲区
        long totalSize = HEADER_SIZE + (long) capacity * slotSize;
        this.bufferAddress = allocator.allocate((int) totalSize);
        if (this.bufferAddress == 0) {
            throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
        }

        // 初始化header
        initHeader();
    }

    /**
     * 恢复环形队列（从已有地址）
     */
    public CircularQueueStorage(Allocator allocator, Codec<E> elementCodec,
                                long bufferAddress, int capacity, int maxElementSize) {
        this.allocator = allocator;
        this.elementCodec = elementCodec;
        this.bufferAddress = bufferAddress;
        this.capacity = capacity;
        this.maxElementSize = maxElementSize;
        this.slotSize = SLOT_HEADER_SIZE + maxElementSize;
        this.lock = new StampedLock();

        // Fix 9: 从 headIdx/tailIdx 重算 count，避免 crash 导致 COUNT_POS 与实际不一致
        int head = UnsafeOps.getInt(bufferAddress + HEAD_IDX_POS);
        int tail = UnsafeOps.getInt(bufferAddress + TAIL_IDX_POS);
        int computedCount = tail >= head ? tail - head : capacity - head + tail;
        // 写回校正后的 count，确保后续 getCount() 读取正确
        UnsafeOps.putInt(bufferAddress + COUNT_POS, computedCount);
        this.size = new AtomicInteger(computedCount);
    }

    private void initHeader() {
        long stamp = lock.writeLock();
        try {
            UnsafeOps.putInt(bufferAddress + HEAD_IDX_POS, 0);
            UnsafeOps.putInt(bufferAddress + TAIL_IDX_POS, 0);
            UnsafeOps.putInt(bufferAddress + CAPACITY_POS, capacity);
            UnsafeOps.putInt(bufferAddress + COUNT_POS, 0);
            UnsafeOps.putInt(bufferAddress + SLOT_SIZE_POS, slotSize);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }

        // 检查元素大小
        int elementSize = elementCodec.calculateSize(element);
        if (elementSize > maxElementSize) {
            throw new IllegalArgumentException("元素大小 " + elementSize +
                    " 超过最大限制 " + maxElementSize);
        }

        long stamp = lock.writeLock();
        try {
            // 检查是否已满
            if (isFullInternal()) {
                return false;
            }

            // 获取tail索引
            int tailIdx = getTailIdx();
            long slotAddr = getSlotAddress(tailIdx);

            // 写入元素大小
            UnsafeOps.putInt(slotAddr, elementSize);

            // 写入元素数据
            long elementAddr = slotAddr + SLOT_HEADER_SIZE;
            elementCodec.encode(elementAddr, element);

            // 更新tail和count
            int newTailIdx = (tailIdx + 1) % capacity;
            UnsafeOps.putInt(bufferAddress + TAIL_IDX_POS, newTailIdx);
            int newCount = getCount() + 1;
            UnsafeOps.putInt(bufferAddress + COUNT_POS, newCount);
            size.set(newCount);

            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public E poll() {
        long stamp = lock.writeLock();
        try {
            // 检查是否为空
            if (isEmptyInternal()) {
                return null;
            }

            // 获取head索引
            int headIdx = getHeadIdx();
            long slotAddr = getSlotAddress(headIdx);

            // 读取元素大小
            int elementSize = UnsafeOps.getInt(slotAddr);

            // 读取元素
            long elementAddr = slotAddr + SLOT_HEADER_SIZE;
            E element = elementCodec.decode(elementAddr);

            // 更新head和count
            int newHeadIdx = (headIdx + 1) % capacity;
            UnsafeOps.putInt(bufferAddress + HEAD_IDX_POS, newHeadIdx);
            int newCount = getCount() - 1;
            UnsafeOps.putInt(bufferAddress + COUNT_POS, newCount);
            size.set(newCount);

            return element;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public E peek() {
        long stamp = lock.tryOptimisticRead();

        if (isEmptyInternal()) {
            return null;
        }

        int headIdx = getHeadIdx();
        long slotAddr = getSlotAddress(headIdx);
        long elementAddr = slotAddr + SLOT_HEADER_SIZE;

        E element = elementCodec.decode(elementAddr);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                if (isEmptyInternal()) {
                    return null;
                }
                headIdx = getHeadIdx();
                slotAddr = getSlotAddress(headIdx);
                elementAddr = slotAddr + SLOT_HEADER_SIZE;
                element = elementCodec.decode(elementAddr);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return element;
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public boolean isEmpty() {
        return size.get() == 0;
    }

    @Override
    public boolean isFull() {
        return size.get() >= capacity;
    }

    private boolean isEmptyInternal() {
        return getCount() == 0;
    }

    private boolean isFullInternal() {
        return getCount() >= capacity;
    }

    @Override
    public void clear() {
        long stamp = lock.writeLock();
        try {
            UnsafeOps.putInt(bufferAddress + HEAD_IDX_POS, 0);
            UnsafeOps.putInt(bufferAddress + TAIL_IDX_POS, 0);
            UnsafeOps.putInt(bufferAddress + COUNT_POS, 0);
            size.set(0);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public void close() {
        // 环形缓冲区在close时不需要单独释放（由RogueQueue统一管理）
    }

    @Override
    public int getStorageType() {
        return STORAGE_TYPE;
    }

    // ========== 辅助方法 ==========

    private int getHeadIdx() {
        return UnsafeOps.getInt(bufferAddress + HEAD_IDX_POS);
    }

    private int getTailIdx() {
        return UnsafeOps.getInt(bufferAddress + TAIL_IDX_POS);
    }

    private int getCount() {
        return UnsafeOps.getInt(bufferAddress + COUNT_POS);
    }

    private long getSlotAddress(int index) {
        return bufferAddress + HEADER_SIZE + (long) index * slotSize;
    }

    // ========== 持久化 ==========

    @Override
    public int serializedSize() {
        return 20; // bufferOffset(8) + capacity(4) + maxElementSize(4) + count(4)
    }

    @Override
    public int serialize(long address, long baseAddress) {
        long stamp = lock.readLock();
        try {
            // buffer 文件偏移
            long bufferRelOffset = toStoredOffset(bufferAddress);
            UnsafeOps.putLong(address, bufferRelOffset);

            // capacity
            UnsafeOps.putInt(address + 8, capacity);

            // maxElementSize
            UnsafeOps.putInt(address + 12, maxElementSize);

            // count
            UnsafeOps.putInt(address + 16, getCount());

            return 20;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    @Override
    public void deserialize(long address, int size, long baseAddress) {
        // CircularQueue的恢复通过构造函数完成
        // 这里不需要实现
    }

    public long getBufferAddress() {
        return bufferAddress;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getMaxElementSize() {
        return maxElementSize;
    }

    private long toStoredOffset(long address) {
        if (allocator instanceof MmapAllocator) {
            return ((MmapAllocator) allocator).getFileOffsetForAddress(address);
        }
        return address;
    }
}
