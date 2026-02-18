package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.storage.MmapFileHeader;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * 基于链表的无界队列存储实现
 *
 * 节点结构（使用中）：
 *   [nextOffset(8B)][elementSize(4B)][element data...]
 *
 * 节点结构（空闲链表中，field 被复用）：
 *   [nextFreeOffset(8B)][allocatedTotal(4B)][... 保留 ...]
 *
 * === 内存回收：空闲节点链表（Fix 1）===
 * poll() 掉的节点不还给 MmapAllocator（其 free() 为 no-op），
 * 而是挂入 LinkedQueueStorage 内部的空闲链表。
 * offer() 时优先从空闲链表取节点复用，找不到合适节点时才从 allocator 新分配。
 *
 * === 崩溃恢复快照（Fix 4）===
 * 每次 offer/poll 成功后，在 writeLock 内将 headOffset/tailOffset/size/allocOffset
 * 写入 mmap 文件头的 Reserved 区域（bytes 64-95）。
 * 重启时若检测到上次未正常关闭（dirtyFlag=1），优先从快照恢复状态。
 */
public class LinkedQueueStorage<E> implements QueueStorage<E> {

    // 节点头大小
    public static final int NODE_HEADER_SIZE = 12;

    // 字段位置
    public static final int NEXT_OFFSET_POS = 0;
    public static final int ELEMENT_SIZE_POS = 8;

    // 存储类型标识
    public static final int STORAGE_TYPE = 3; // DATA_TYPE_QUEUE_LINKED

    // 空闲链表最大长度（防止遍历耗时过长）
    private static final int MAX_FREE_LIST_SIZE = 4096;

    private final Allocator allocator;
    private final Codec<E> elementCodec;

    // 队列元数据
    private volatile long headOffset;   // 队首节点偏移量
    private volatile long tailOffset;   // 队尾节点偏移量
    private final AtomicInteger size;   // 元素数量

    // 并发控制
    private final StampedLock lock;

    // ===== 空闲节点链表（Fix 1）=====
    private long freeListHead;   // 空闲链表头（mmap 绝对地址，0 表示空）
    private int freeListSize;    // 空闲链表中的节点数

    // ===== 崩溃恢复快照（Fix 4）=====
    private final long snapshotAddress; // 快照写入地址（mmap 头部 Reserved 区），0 表示不支持
    private final long baseAddress;     // mmap 基地址（用于计算相对偏移量）

    /**
     * 创建 LinkedQueueStorage（无快照支持，适用于临时文件模式）
     */
    public LinkedQueueStorage(Allocator allocator, Codec<E> elementCodec) {
        this(allocator, elementCodec, 0, 0);
    }

    /**
     * 创建 LinkedQueueStorage（带快照支持，适用于持久化文件模式）
     *
     * @param snapshotAddress 快照写入地址（baseAddress + MmapFileHeader.SNAPSHOT_HEAD_POS），0 表示禁用
     * @param baseAddress     mmap 基地址（用于计算相对偏移量）
     */
    public LinkedQueueStorage(Allocator allocator, Codec<E> elementCodec,
                              long snapshotAddress, long baseAddress) {
        this.allocator = allocator;
        this.elementCodec = elementCodec;
        this.headOffset = 0;
        this.tailOffset = 0;
        this.size = new AtomicInteger(0);
        this.lock = new StampedLock();
        this.snapshotAddress = snapshotAddress;
        this.baseAddress = baseAddress;
        this.freeListHead = 0;
        this.freeListSize = 0;
    }

    @Override
    public boolean offer(E element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }

        int elementSize = elementCodec.calculateSize(element);
        int totalSize = NODE_HEADER_SIZE + elementSize;

        long stamp = lock.writeLock();
        try {
            // 1. 优先从空闲链表取节点复用（Fix 1）
            long nodeAddress = tryAllocateFromFreeList(totalSize);
            if (nodeAddress == 0) {
                // 空闲链表无合适节点，从 allocator 新分配
                nodeAddress = allocator.allocate(totalSize);
                if (nodeAddress == 0) {
                    throw new OutOfMemoryError("分配 " + totalSize + " 字节失败，文件空间不足，"
                            + "请增大 allocateSize 或减少数据量");
                }
            }

            // 2. 初始化节点
            setNodeNext(nodeAddress, 0);
            setElementSize(nodeAddress, elementSize);

            // 3. 写入元素数据
            long elementAddr = nodeAddress + NODE_HEADER_SIZE;
            elementCodec.encode(elementAddr, element);

            // 4. 更新队列指针
            if (tailOffset == 0) {
                headOffset = nodeAddress;
                tailOffset = nodeAddress;
            } else {
                setNodeNext(tailOffset, nodeAddress);
                tailOffset = nodeAddress;
            }
            size.incrementAndGet();

            // 5. 写入崩溃恢复快照（Fix 4）
            writeSnapshot();

            return true;
        } finally {
            lock.unlockWrite(stamp);
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

            // 更新 head 指针
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

            // 将节点加入空闲链表（Fix 1，替代 allocator.free()）
            returnToFreeList(oldHead, elementSize);

            // 写入崩溃恢复快照（Fix 4）
            writeSnapshot();

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
        return false; // 链表队列永不满
    }

    @Override
    public void clear() {
        long stamp = lock.writeLock();
        try {
            headOffset = 0;
            tailOffset = 0;
            size.set(0);
            // 重置空闲链表（节点内存已在 mmap 中，由 allocator 统一管理）
            freeListHead = 0;
            freeListSize = 0;
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

    // ========== 空闲链表操作（Fix 1，必须在 writeLock 内调用）==========

    /**
     * 尝试从空闲链表分配节点。
     * 遍历链表找第一个足够大的节点，O(n) 但受 MAX_FREE_LIST_SIZE 限制。
     * 必须在 writeLock 内调用。
     *
     * @param requiredTotal 所需总字节数（NODE_HEADER_SIZE + elementSize）
     * @return 节点地址，0 表示链表中无合适节点
     */
    private long tryAllocateFromFreeList(int requiredTotal) {
        long prev = 0;
        long cur = freeListHead;
        while (cur != 0) {
            // ELEMENT_SIZE_POS 在空闲节点中存储 allocatedTotal
            int storedTotal = UnsafeOps.getInt(cur + ELEMENT_SIZE_POS);
            long next = UnsafeOps.getLong(cur + NEXT_OFFSET_POS);
            if (storedTotal >= requiredTotal) {
                // 从链表中摘出该节点
                if (prev == 0) {
                    freeListHead = next;
                } else {
                    UnsafeOps.putLong(prev + NEXT_OFFSET_POS, next);
                }
                freeListSize--;
                return cur;
            }
            prev = cur;
            cur = next;
        }
        return 0;
    }

    /**
     * 将已 poll 的节点加入空闲链表。
     * 复用 NEXT_OFFSET_POS 存储链表指针，ELEMENT_SIZE_POS 存储分配总大小。
     * 必须在 writeLock 内调用。
     *
     * @param nodeAddress 要回收的节点地址
     * @param elementSize 该节点存储的元素字节数
     */
    private void returnToFreeList(long nodeAddress, int elementSize) {
        if (freeListSize >= MAX_FREE_LIST_SIZE) {
            // 空闲链表已满，丢弃（该节点内存无法回收，属于可接受的小概率损耗）
            return;
        }
        int allocatedTotal = NODE_HEADER_SIZE + elementSize;
        // 复用 ELEMENT_SIZE_POS 存储分配总大小
        UnsafeOps.putInt(nodeAddress + ELEMENT_SIZE_POS, allocatedTotal);
        // 复用 NEXT_OFFSET_POS 链接空闲链表
        UnsafeOps.putLong(nodeAddress + NEXT_OFFSET_POS, freeListHead);
        freeListHead = nodeAddress;
        freeListSize++;
    }

    // ========== 崩溃恢复快照（Fix 4，必须在 writeLock 内调用）==========

    /**
     * 将当前队列状态写入 mmap 文件头 Reserved 区的快照位置。
     * 写入 4 个字段：headRelOffset, tailRelOffset, size, snapshotValid=1, allocOffset。
     * 纯 mmap 内存写，无 syscall，开销约 20-40ns。
     * 必须在 writeLock 内调用。
     */
    private void writeSnapshot() {
        if (snapshotAddress == 0) return; // 临时文件模式，不写快照

        long headRel = headOffset == 0 ? 0 : (headOffset - baseAddress);
        long tailRel = tailOffset == 0 ? 0 : (tailOffset - baseAddress);

        UnsafeOps.putLong(snapshotAddress, headRel);                           // offset 64
        UnsafeOps.putLong(snapshotAddress + 8, tailRel);                       // offset 72
        UnsafeOps.putInt(snapshotAddress + 16, size.get());                    // offset 80
        UnsafeOps.putInt(snapshotAddress + 20, 1);                             // offset 84: valid
        UnsafeOps.putLong(snapshotAddress + 24, allocator.usedMemory());       // offset 88
    }

    /**
     * 从快照恢复队列状态（崩溃恢复路径）。
     * 仅在 MmapFileHeader.getSnapshotValid() == 1 时调用。
     */
    public void deserializeFromSnapshot() {
        long stamp = lock.writeLock();
        try {
            long headRel = UnsafeOps.getLong(snapshotAddress);
            headOffset = headRel == 0 ? 0 : (baseAddress + headRel);

            long tailRel = UnsafeOps.getLong(snapshotAddress + 8);
            tailOffset = tailRel == 0 ? 0 : (baseAddress + tailRel);

            this.size.set(UnsafeOps.getInt(snapshotAddress + 16));
        } finally {
            lock.unlockWrite(stamp);
        }
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

            // size
            this.size.set(UnsafeOps.getInt(address + 16));
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // 获取 head/tail 偏移量（用于恢复）
    public long getHeadOffset() {
        return headOffset;
    }

    public long getTailOffset() {
        return tailOffset;
    }
}
