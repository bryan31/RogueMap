package com.yomahub.roguemap.list;

import com.yomahub.roguemap.memory.UnsafeOps;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * List索引管理
 *
 * 负责管理：
 * 1. head/tail指针
 * 2. 位置索引数组（实现O(1)随机访问）
 * 3. 元素计数
 */
public class ListIndex {

    // 节点头大小：prevOffset(8) + nextOffset(8) + elementSize(4) = 20 bytes
    public static final int NODE_HEADER_SIZE = 20;

    // 偏移量字段位置
    public static final int PREV_OFFSET_POS = 0;
    public static final int NEXT_OFFSET_POS = 8;
    public static final int ELEMENT_SIZE_POS = 16;

    // 元数据
    private volatile long headOffset;   // 头节点偏移量（0表示空）
    private volatile long tailOffset;   // 尾节点偏移量（0表示空）
    private final AtomicInteger size;   // 元素数量

    // 位置索引数组（用于O(1)随机访问）
    private long[] positionIndex;
    private int indexCapacity;

    // 并发控制
    private final StampedLock lock;

    public ListIndex() {
        this.headOffset = 0;
        this.tailOffset = 0;
        this.size = new AtomicInteger(0);
        this.lock = new StampedLock();

        // 初始容量
        this.indexCapacity = 1024;
        this.positionIndex = new long[indexCapacity];
    }

    /**
     * 添加节点到链表头部
     *
     * @param newOffset 新节点的偏移量
     */
    public void addToHead(long newOffset) {
        long stamp = lock.writeLock();
        try {
            if (headOffset == 0) {
                // 空链表
                headOffset = newOffset;
                tailOffset = newOffset;
            } else {
                // 设置新节点的next指向原head
                setNodeNext(newOffset, headOffset);
                // 设置原head的prev指向新节点
                setNodePrev(headOffset, newOffset);
                // 更新head
                headOffset = newOffset;
            }

            // 更新位置索引（插入到头部，所有现有元素后移）
            shiftIndexForHeadInsert();
            positionIndex[0] = newOffset;

            size.incrementAndGet();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 添加节点到链表尾部
     *
     * @param newOffset 新节点的偏移量
     */
    public void addToTail(long newOffset) {
        long stamp = lock.writeLock();
        try {
            if (tailOffset == 0) {
                // 空链表
                headOffset = newOffset;
                tailOffset = newOffset;
            } else {
                // 设置新节点的prev指向原tail
                setNodePrev(newOffset, tailOffset);
                // 设置原tail的next指向新节点
                setNodeNext(tailOffset, newOffset);
                // 更新tail
                tailOffset = newOffset;
            }

            // 更新位置索引（追加到尾部）
            int currentSize = size.get();
            ensureIndexCapacity(currentSize + 1);
            positionIndex[currentSize] = newOffset;

            size.incrementAndGet();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 移除头部节点
     *
     * @return 被移除节点的偏移量，如果为空返回0
     */
    public long removeHead() {
        long stamp = lock.writeLock();
        try {
            if (headOffset == 0) {
                return 0;
            }

            long oldHead = headOffset;
            long nextOffset = getNodeNext(headOffset);

            if (nextOffset == 0) {
                // 只有一个节点
                headOffset = 0;
                tailOffset = 0;
            } else {
                // 更新next节点的prev为0
                setNodePrev(nextOffset, 0);
                headOffset = nextOffset;
            }

            // 更新位置索引（移除头部，所有元素前移）
            shiftIndexForHeadRemove();

            size.decrementAndGet();
            return oldHead;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 移除尾部节点
     *
     * @return 被移除节点的偏移量，如果为空返回0
     */
    public long removeTail() {
        long stamp = lock.writeLock();
        try {
            if (tailOffset == 0) {
                return 0;
            }

            long oldTail = tailOffset;
            long prevOffset = getNodePrev(tailOffset);

            if (prevOffset == 0) {
                // 只有一个节点
                headOffset = 0;
                tailOffset = 0;
            } else {
                // 更新prev节点的next为0
                setNodeNext(prevOffset, 0);
                tailOffset = prevOffset;
            }

            // 更新位置索引（移除尾部）
            int currentSize = size.get();
            if (currentSize > 0) {
                positionIndex[currentSize - 1] = 0;
            }

            size.decrementAndGet();
            return oldTail;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 获取指定索引位置的节点偏移量
     *
     * @param index 索引
     * @return 节点偏移量，如果索引无效返回0
     */
    public long get(int index) {
        long stamp = lock.tryOptimisticRead();
        long offset = getOffsetByIndex(index);

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                offset = getOffsetByIndex(index);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return offset;
    }

    private long getOffsetByIndex(int index) {
        if (index < 0 || index >= size.get()) {
            return 0;
        }
        return positionIndex[index];
    }

    /**
     * 获取head偏移量
     */
    public long getHeadOffset() {
        return headOffset;
    }

    /**
     * 获取tail偏移量
     */
    public long getTailOffset() {
        return tailOffset;
    }

    /**
     * 获取元素数量
     */
    public int size() {
        return size.get();
    }

    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return size.get() == 0;
    }

    /**
     * 清空索引
     */
    public void clear() {
        long stamp = lock.writeLock();
        try {
            headOffset = 0;
            tailOffset = 0;
            size.set(0);

            // 清空位置索引
            for (int i = 0; i < indexCapacity; i++) {
                positionIndex[i] = 0;
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ========== 节点操作方法（直接操作内存） ==========

    /**
     * 设置节点的prev偏移量
     */
    public static void setNodePrev(long nodeBaseAddress, long prevOffset) {
        UnsafeOps.putLong(nodeBaseAddress + PREV_OFFSET_POS, prevOffset);
    }

    /**
     * 获取节点的prev偏移量
     */
    public static long getNodePrev(long nodeBaseAddress) {
        return UnsafeOps.getLong(nodeBaseAddress + PREV_OFFSET_POS);
    }

    /**
     * 设置节点的next偏移量
     */
    public static void setNodeNext(long nodeBaseAddress, long nextOffset) {
        UnsafeOps.putLong(nodeBaseAddress + NEXT_OFFSET_POS, nextOffset);
    }

    /**
     * 获取节点的next偏移量
     */
    public static long getNodeNext(long nodeBaseAddress) {
        return UnsafeOps.getLong(nodeBaseAddress + NEXT_OFFSET_POS);
    }

    /**
     * 设置节点的元素大小
     */
    public static void setElementSize(long nodeBaseAddress, int elementSize) {
        UnsafeOps.putInt(nodeBaseAddress + ELEMENT_SIZE_POS, elementSize);
    }

    /**
     * 获取节点的元素大小
     */
    public static int getElementSize(long nodeBaseAddress) {
        return UnsafeOps.getInt(nodeBaseAddress + ELEMENT_SIZE_POS);
    }

    /**
     * 获取元素数据的起始地址
     */
    public static long getElementAddress(long nodeBaseAddress) {
        return nodeBaseAddress + NODE_HEADER_SIZE;
    }

    // ========== 索引数组管理 ==========

    private void ensureIndexCapacity(int required) {
        if (required <= indexCapacity) {
            return;
        }

        int newCapacity = indexCapacity * 2;
        while (newCapacity < required) {
            newCapacity *= 2;
        }

        long[] newIndex = new long[newCapacity];
        System.arraycopy(positionIndex, 0, newIndex, 0, size.get());
        positionIndex = newIndex;
        indexCapacity = newCapacity;
    }

    private void shiftIndexForHeadInsert() {
        int currentSize = size.get();
        ensureIndexCapacity(currentSize + 1);

        // 所有元素后移一位
        for (int i = currentSize; i > 0; i--) {
            positionIndex[i] = positionIndex[i - 1];
        }
    }

    private void shiftIndexForHeadRemove() {
        int currentSize = size.get();
        // 所有元素前移一位
        for (int i = 0; i < currentSize - 1; i++) {
            positionIndex[i] = positionIndex[i + 1];
        }
        if (currentSize > 0) {
            positionIndex[currentSize - 1] = 0;
        }
    }

    // ========== 持久化 ==========

    /**
     * 序列化大小
     */
    public int serializedSize() {
        // headOffset(8) + tailOffset(8) + size(4) + positionIndex
        return 20 + size.get() * 8;
    }

    /**
     * 序列化到内存地址
     */
    public int serialize(long address, long baseAddress) {
        long currentAddr = address;

        long stamp = lock.readLock();
        try {
            // headOffset（转换为相对偏移）
            long headRelOffset = headOffset == 0 ? 0 : (headOffset - baseAddress);
            UnsafeOps.putLong(currentAddr, headRelOffset);
            currentAddr += 8;

            // tailOffset（转换为相对偏移）
            long tailRelOffset = tailOffset == 0 ? 0 : (tailOffset - baseAddress);
            UnsafeOps.putLong(currentAddr, tailRelOffset);
            currentAddr += 8;

            // size
            UnsafeOps.putInt(currentAddr, size.get());
            currentAddr += 4;

            // positionIndex（转换为相对偏移）
            int currentSize = size.get();
            for (int i = 0; i < currentSize; i++) {
                long relOffset = positionIndex[i] == 0 ? 0 : (positionIndex[i] - baseAddress);
                UnsafeOps.putLong(currentAddr, relOffset);
                currentAddr += 8;
            }

            return (int) (currentAddr - address);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * 从内存地址反序列化
     */
    public void deserialize(long address, int totalSize, long baseAddress) {
        long currentAddr = address;

        long stamp = lock.writeLock();
        try {
            // headOffset
            long headRelOffset = UnsafeOps.getLong(currentAddr);
            headOffset = headRelOffset == 0 ? 0 : (baseAddress + headRelOffset);
            currentAddr += 8;

            // tailOffset
            long tailRelOffset = UnsafeOps.getLong(currentAddr);
            tailOffset = tailRelOffset == 0 ? 0 : (baseAddress + tailRelOffset);
            currentAddr += 8;

            // size
            int savedSize = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 确保索引容量足够
            ensureIndexCapacity(savedSize);

            // positionIndex
            for (int i = 0; i < savedSize; i++) {
                long relOffset = UnsafeOps.getLong(currentAddr);
                positionIndex[i] = relOffset == 0 ? 0 : (baseAddress + relOffset);
                currentAddr += 8;
            }

            size.set(savedSize);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 恢复元数据（从header字段）
     */
    public void restoreMetadata(long headOffset, long tailOffset, int size) {
        long stamp = lock.writeLock();
        try {
            this.headOffset = headOffset;
            this.tailOffset = tailOffset;

            // 确保索引容量
            ensureIndexCapacity(size);
            this.size.set(size);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 设置位置索引（用于恢复）
     */
    public void setPositionIndex(int index, long offset) {
        long stamp = lock.writeLock();
        try {
            ensureIndexCapacity(index + 1);
            positionIndex[index] = offset;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
