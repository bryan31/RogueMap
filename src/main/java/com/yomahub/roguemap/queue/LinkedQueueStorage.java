package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.storage.MmapFileHeader;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
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

    // ===== 空闲节点 TreeMap（替代原 mmap 内链表，Fix 1 优化）=====
    // key = allocatedTotal（节点大小），value = 该大小的节点地址栈
    // 使用 TreeMap 的 ceilingEntry() 实现 O(log k) 查找（k 为不同大小桶的数量）。
    // free list 从未持久化到快照，从 mmap 链表迁移至 Java 堆不影响崩溃恢复语义。
    private final TreeMap<Integer, ArrayDeque<Long>> freeListBySize = new TreeMap<>();
    private int freeListTotalCount = 0;  // 所有桶中节点总数，替代原 freeListSize

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
            // 重置空闲 TreeMap
            freeListBySize.clear();
            freeListTotalCount = 0;
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

    // ========== 空闲节点 TreeMap 操作（Fix 1 优化，必须在 writeLock 内调用）==========

    /**
     * 尝试从 TreeMap 分配节点。
     * 使用 ceilingEntry(requiredTotal) 找到最小的足够大的节点，O(log k)。
     * 必须在 writeLock 内调用。
     *
     * @param requiredTotal 所需总字节数（NODE_HEADER_SIZE + elementSize）
     * @return 节点地址，0 表示无合适节点
     */
    private long tryAllocateFromFreeList(int requiredTotal) {
        Map.Entry<Integer, ArrayDeque<Long>> entry = freeListBySize.ceilingEntry(requiredTotal);
        if (entry == null) {
            return 0;
        }
        ArrayDeque<Long> deque = entry.getValue();
        long nodeAddress = deque.pop();
        freeListTotalCount--;
        if (deque.isEmpty()) {
            freeListBySize.remove(entry.getKey());
        }
        return nodeAddress;
    }

    /**
     * 将已 poll 的节点加入 TreeMap 以备复用。
     * 不再写入 mmap（节点内存保留原值，下次 offer 时会被覆盖）。
     * 空闲节点总数仍限制在 MAX_FREE_LIST_SIZE 以内，超出时丢弃（可接受的小概率损耗）。
     * 必须在 writeLock 内调用。
     *
     * @param nodeAddress 要回收的节点地址
     * @param elementSize 该节点存储的元素字节数
     */
    private void returnToFreeList(long nodeAddress, int elementSize) {
        if (freeListTotalCount >= MAX_FREE_LIST_SIZE) {
            return;
        }
        int allocatedTotal = NODE_HEADER_SIZE + elementSize;
        freeListBySize.computeIfAbsent(allocatedTotal, k -> new ArrayDeque<>()).push(nodeAddress);
        freeListTotalCount++;
    }

    /**
     * 返回当前 free list 中所有节点占用的字节总数。
     * 这些字节可被 offer() 复用，不计入真正的碎片。
     */
    public long getFreeListBytes() {
        long stamp = lock.readLock();
        try {
            long total = 0;
            for (Map.Entry<Integer, ArrayDeque<Long>> e : freeListBySize.entrySet()) {
                total += (long) e.getKey() * e.getValue().size();
            }
            return total;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    // ========== 崩溃恢复快照（Fix 4，必须在 writeLock 内调用）==========

    /**
     * 将当前队列状态写入 mmap 文件头 Reserved 区的快照位置。
     * 写入 4 个字段：headRelOffset, tailRelOffset, size, allocOffset，以及 snapshotValid 标志。
     *
     * <p><b>原子性保证</b>（invalidate-before-write 协议）：
     * <ol>
     *   <li>先将 snapshotValid 置 0（失效旧快照）</li>
     *   <li>storeFence —— 确保失效对其他线程/崩溃恢复可见</li>
     *   <li>写入 headRel, tailRel, size, allocOffset</li>
     *   <li>storeFence —— 确保所有数据字段先于 valid 标记落盘</li>
     *   <li>将 snapshotValid 置 1（新快照生效）</li>
     * </ol>
     * 若进程在步骤 1-4 之间崩溃，snapshotValid=0，恢复时忽略此快照。
     *
     * 必须在 writeLock 内调用。
     */
    private void writeSnapshot() {
        if (snapshotAddress == 0) return; // 临时文件模式，不写快照

        long headRel = toStoredOffset(headOffset);
        long tailRel = toStoredOffset(tailOffset);

        // 1. 失效旧快照
        UnsafeOps.putInt(snapshotAddress + 20, 0);                             // offset 84: invalid
        UnsafeOps.storeFence();

        // 2. 写入数据字段
        UnsafeOps.putLong(snapshotAddress, headRel);                           // offset 64
        UnsafeOps.putLong(snapshotAddress + 8, tailRel);                       // offset 72
        UnsafeOps.putInt(snapshotAddress + 16, size.get());                    // offset 80
        UnsafeOps.putLong(snapshotAddress + 24, allocator.usedMemory());       // offset 88
        UnsafeOps.storeFence();

        // 3. 生效新快照
        UnsafeOps.putInt(snapshotAddress + 20, 1);                             // offset 84: valid
    }

    /**
     * 从快照恢复队列状态（崩溃恢复路径）。
     * 仅在 MmapFileHeader.getSnapshotValid() == 1 时调用。
     * 对快照中的地址做合法性校验，损坏时抛出 IllegalStateException。
     */
    public void deserializeFromSnapshot() {
        long stamp = lock.writeLock();
        try {
            long headRel  = UnsafeOps.getLong(snapshotAddress);          // offset 64
            long tailRel  = UnsafeOps.getLong(snapshotAddress + 8);      // offset 72
            int  snapSize = UnsafeOps.getInt(snapshotAddress + 16);      // offset 80
            long allocOff = UnsafeOps.getLong(snapshotAddress + 24);     // offset 88

            // 校验 allocOffset：必须不小于 HEADER_SIZE
            if (allocOff < MmapFileHeader.HEADER_SIZE) {
                throw new IllegalStateException(
                    "快照数据损坏：allocOffset=" + allocOff
                    + " 小于 HEADER_SIZE=" + MmapFileHeader.HEADER_SIZE);
            }
            // 校验 size 非负
            if (snapSize < 0) {
                throw new IllegalStateException("快照数据损坏：size=" + snapSize + " 为负数");
            }
            // 校验 head/tail 与 size 的一致性
            boolean headNull = (headRel == 0);
            boolean tailNull = (tailRel == 0);
            if (snapSize == 0 && (!headNull || !tailNull)) {
                throw new IllegalStateException(
                    "快照数据损坏：size=0 但 headRel=" + headRel + ", tailRel=" + tailRel);
            }
            if (snapSize > 0 && (headNull || tailNull)) {
                throw new IllegalStateException(
                    "快照数据损坏：size=" + snapSize + " 但指针为空，headRel=" + headRel + ", tailRel=" + tailRel);
            }
            // 校验地址范围：[HEADER_SIZE, allocOff - NODE_HEADER_SIZE]
            long minValid = MmapFileHeader.HEADER_SIZE;
            long maxValid = allocOff - NODE_HEADER_SIZE;
            if (!headNull && (headRel < minValid || headRel > maxValid)) {
                throw new IllegalStateException(
                    "快照数据损坏：headRel=" + headRel + " 超出有效范围 [" + minValid + ", " + maxValid + "]");
            }
            if (!tailNull && (tailRel < minValid || tailRel > maxValid)) {
                throw new IllegalStateException(
                    "快照数据损坏：tailRel=" + tailRel + " 超出有效范围 [" + minValid + ", " + maxValid + "]");
            }

            headOffset = headNull ? 0 : toAddress(headRel);
            tailOffset = tailNull ? 0 : toAddress(tailRel);
            this.size.set(snapSize);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ========== 节点操作方法 ==========

    private void setNodeNext(long nodeBaseAddress, long nextOffset) {
        UnsafeOps.putLong(nodeBaseAddress + NEXT_OFFSET_POS, toStoredOffset(nextOffset));
    }

    private long getNodeNext(long nodeBaseAddress) {
        return toAddress(UnsafeOps.getLong(nodeBaseAddress + NEXT_OFFSET_POS));
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
            // headOffset（文件偏移）
            long headRelOffset = toStoredOffset(headOffset);
            UnsafeOps.putLong(address, headRelOffset);

            // tailOffset（文件偏移）
            long tailRelOffset = toStoredOffset(tailOffset);
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
            headOffset = headRelOffset == 0 ? 0 : toAddress(headRelOffset);

            // tailOffset
            long tailRelOffset = UnsafeOps.getLong(address + 8);
            tailOffset = tailRelOffset == 0 ? 0 : toAddress(tailRelOffset);

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

    /**
     * 供 compact() 顺序遍历活跃节点使用。
     */
    public long getNextOffset(long nodeAddress) {
        return getNodeNext(nodeAddress);
    }

    private long toStoredOffset(long address) {
        if (address == 0) {
            return 0;
        }
        if (allocator instanceof MmapAllocator) {
            return ((MmapAllocator) allocator).getFileOffsetForAddress(address);
        }
        return address - baseAddress;
    }

    private long toAddress(long storedOffset) {
        if (storedOffset == 0) {
            return 0;
        }
        if (allocator instanceof MmapAllocator) {
            return ((MmapAllocator) allocator).getAddressForOffset(storedOffset);
        }
        return baseAddress + storedOffset;
    }
}
