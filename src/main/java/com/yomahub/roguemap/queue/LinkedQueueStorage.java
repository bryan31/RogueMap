package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * 基于链表的无界队列存储实现
 *
 * 节点结构：
 * [nextOffset(8B)][elementSize(4B)][element data...]
 */
public class LinkedQueueStorage<E> implements QueueStorage<E> {

    // 节点头大小
    public static final int NODE_HEADER_SIZE = 12;

    // 字段位置
    public static final int NEXT_OFFSET_POS = 0;
    public static final int ELEMENT_SIZE_POS = 8;

    // 存储类型标识
    public static final int STORAGE_TYPE = 3; // DATA_TYPE_QUEUE_LINKED

    private final Allocator allocator;
    private final Codec<E> elementCodec;

    // 队列元数据
    private volatile long headOffset;   // 队首节点偏移量
    private volatile long tailOffset;   // 队尾节点偏移量
    private final AtomicInteger size;   // 元素数量

    // 并发控制
    private final StampedLock lock;

    public LinkedQueueStorage(Allocator allocator, Codec<E> elementCodec) {
        this.allocator = allocator;
        this.elementCodec = elementCodec;
        this.headOffset = 0;
        this.tailOffset = 0;
        this.size = new AtomicInteger(0);
        this.lock = new StampedLock();
    }

    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }

        // 计算所需大小
        int elementSize = elementCodec.calculateSize(element);
        int totalSize = NODE_HEADER_SIZE + elementSize;

        // 分配内存
        long nodeAddress = allocator.allocate(totalSize);
        if (nodeAddress == 0) {
            throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
        }

        try {
            // 初始化节点
            setNodeNext(nodeAddress, 0);
            setElementSize(nodeAddress, elementSize);

            // 写入元素数据
            long elementAddr = nodeAddress + NODE_HEADER_SIZE;
            elementCodec.encode(elementAddr, element);

            // 更新队列指针
            long stamp = lock.writeLock();
            try {
                if (tailOffset == 0) {
                    // 空队列
                    headOffset = nodeAddress;
                    tailOffset = nodeAddress;
                } else {
                    // 链接到尾部
                    setNodeNext(tailOffset, nodeAddress);
                    tailOffset = nodeAddress;
                }
                size.incrementAndGet();
            } finally {
                lock.unlockWrite(stamp);
            }

            return true;
        } catch (Exception e) {
            allocator.free(nodeAddress, totalSize);
            throw e;
        }
    }

    @Override
    public E poll() {
        long stamp = lock.writeLock();
        try {
            if (headOffset == 0) {
                return null;
            }

            long oldHead = headOffset;
            long nextOffset = getNodeNext(headOffset);
            int elementSize = getElementSize(oldHead);

            // 更新head指针
            if (nextOffset == 0) {
                headOffset = 0;
                tailOffset = 0;
            } else {
                headOffset = nextOffset;
            }
            size.decrementAndGet();

            // 读取元素
            long elementAddr = oldHead + NODE_HEADER_SIZE;
            E element = elementCodec.decode(elementAddr);

            // 释放内存
            allocator.free(oldHead, NODE_HEADER_SIZE + elementSize);

            return element;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public E peek() {
        long stamp = lock.tryOptimisticRead();
        long currentHead = headOffset;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                currentHead = headOffset;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (currentHead == 0) {
            return null;
        }

        long elementAddr = currentHead + NODE_HEADER_SIZE;
        return elementCodec.decode(elementAddr);
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
        return false; // 链表队列永不満
    }

    @Override
    public void clear() {
        long stamp = lock.writeLock();
        try {
            // 遍历并释放所有节点
            long current = headOffset;
            while (current != 0) {
                long next = getNodeNext(current);
                int elementSize = getElementSize(current);
                allocator.free(current, NODE_HEADER_SIZE + elementSize);
                current = next;
            }

            headOffset = 0;
            tailOffset = 0;
            size.set(0);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public int getStorageType() {
        return STORAGE_TYPE;
    }

    // ========== 节点操作方法 ==========

    private static void setNodeNext(long nodeBaseAddress, long nextOffset) {
        UnsafeOps.putLong(nodeBaseAddress + NEXT_OFFSET_POS, nextOffset);
    }

    private static long getNodeNext(long nodeBaseAddress) {
        return UnsafeOps.getLong(nodeBaseAddress + NEXT_OFFSET_POS);
    }

    private static void setElementSize(long nodeBaseAddress, int elementSize) {
        UnsafeOps.putInt(nodeBaseAddress + ELEMENT_SIZE_POS, elementSize);
    }

    private static int getElementSize(long nodeBaseAddress) {
        return UnsafeOps.getInt(nodeBaseAddress + ELEMENT_SIZE_POS);
    }

    // ========== 持久化 ==========

    @Override
    public int serializedSize() {
        // headOffset(8) + tailOffset(8) + size(4)
        return 20;
    }

    @Override
    public int serialize(long address, long baseAddress) {
        long stamp = lock.readLock();
        try {
            // headOffset（相对偏移）
            long headRelOffset = headOffset == 0 ? 0 : (headOffset - baseAddress);
            UnsafeOps.putLong(address, headRelOffset);

            // tailOffset（相对偏移）
            long tailRelOffset = tailOffset == 0 ? 0 : (tailOffset - baseAddress);
            UnsafeOps.putLong(address + 8, tailRelOffset);

            // size
            UnsafeOps.putInt(address + 16, size.get());

            return 20;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    @Override
    public void deserialize(long address, int size, long baseAddress) {
        long stamp = lock.writeLock();
        try {
            // headOffset
            long headRelOffset = UnsafeOps.getLong(address);
            headOffset = headRelOffset == 0 ? 0 : (baseAddress + headRelOffset);

            // tailOffset
            long tailRelOffset = UnsafeOps.getLong(address + 8);
            tailOffset = tailRelOffset == 0 ? 0 : (baseAddress + tailRelOffset);

            // size - 需要遍历计算实际大小
            this.size.set(UnsafeOps.getInt(address + 16));
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // 获取head/tail偏移量（用于恢复）
    public long getHeadOffset() {
        return headOffset;
    }

    public long getTailOffset() {
        return tailOffset;
    }
}
