package com.yomahub.roguemap.set;

import com.yomahub.roguemap.index.IndexEntryConsumer;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * 基于分段哈希的集合索引，支持高并发访问
 *
 * 元素本身作为key，值部分存储元素数据的内存地址和大小
 * 使用 StampedLock 提供乐观读，64段设计减少锁竞争
 */
public class SetIndex<E> {

    private static final int DEFAULT_SEGMENT_COUNT = 64;
    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    private final Segment<E>[] segments;
    private final int segmentMask;
    private final AtomicInteger size;
    private final Codec<E> elementCodec;
    private volatile int modCount = 0;

    @SuppressWarnings("unchecked")
    public SetIndex(Codec<E> elementCodec) {
        this(elementCodec, DEFAULT_SEGMENT_COUNT, DEFAULT_INITIAL_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public SetIndex(Codec<E> elementCodec, int segmentCount, int initialCapacityPerSegment) {
        if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
            throw new IllegalArgumentException("段数必须是 2 的幂次方");
        }

        this.segments = new Segment[segmentCount];
        this.segmentMask = segmentCount - 1;
        this.size = new AtomicInteger(0);
        this.elementCodec = elementCodec;

        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new Segment<>(initialCapacityPerSegment);
        }
    }

    /**
     * 添加元素到索引
     *
     * @return SetAddResult 表示是否新增还是已存在
     */
    public SetAddResult add(E element, long address, int elementSize) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }
        if (address == 0) {
            throw new IllegalArgumentException("无效的地址: 0");
        }

        Segment<E> segment = getSegment(element);
        SetAddResult result = segment.add(element, address, elementSize);

        if (result.isNewlyAdded()) {
            size.incrementAndGet();
            modCount++;
        }

        return result;
    }

    /**
     * 检查元素是否存在
     */
    public boolean contains(E element) {
        if (element == null) {
            return false;
        }

        Segment<E> segment = getSegment(element);
        return segment.contains(element);
    }

    /**
     * 获取元素的地址
     */
    public long get(E element) {
        if (element == null) {
            return 0;
        }

        Segment<E> segment = getSegment(element);
        return segment.get(element);
    }

    /**
     * 获取元素的大小
     */
    public int getSize(E element) {
        if (element == null) {
            return -1;
        }

        Segment<E> segment = getSegment(element);
        return segment.getSize(element);
    }

    /**
     * 移除元素
     *
     * @return SetRemoveResult 包含是否移除成功和旧值信息
     */
    public SetRemoveResult remove(E element) {
        if (element == null) {
            return SetRemoveResult.notPresent();
        }

        Segment<E> segment = getSegment(element);
        SetRemoveResult result = segment.remove(element);

        if (result.wasPresent()) {
            size.decrementAndGet();
            modCount++;
        }

        return result;
    }

    /**
     * 遍历所有元素
     */
    public void forEach(SetEntryConsumer<E> consumer) {
        if (consumer == null) {
            return;
        }

        for (Segment<E> segment : segments) {
            segment.forEach(consumer);
        }
    }

    /**
     * 遍历指定分段的元素（用于分段懒加载迭代器）
     *
     * @param segmentIdx 分段索引
     * @param consumer   消费者
     */
    public void forSegment(int segmentIdx, SetEntryConsumer<E> consumer) {
        segments[segmentIdx].forEach(consumer);
    }

    /**
     * 获取分段数
     */
    public int getSegmentCount() {
        return segments.length;
    }

    /**
     * 获取修改计数（用于迭代器 fail-fast 检测）
     */
    public int getModCount() {
        return modCount;
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
        for (Segment<E> segment : segments) {
            segment.clear();
        }
        size.set(0);
        modCount++;
    }

    /**
     * 关闭索引
     */
    public void close() {
        clear();
    }

    /**
     * 计算序列化后的大小
     */
    public int serializedSize() {
        if (elementCodec == null) {
            throw new UnsupportedOperationException("无法序列化：elementCodec 为 null");
        }

        int totalSize = 8; // segment count (4) + total entry count (4)

        for (Segment<E> segment : segments) {
            totalSize += segment.serializedSize(elementCodec);
        }

        return totalSize;
    }

    /**
     * 序列化到内存地址（使用相对偏移量）
     */
    public int serializeWithOffsets(long address, long baseAddress) {
        long currentAddr = address;

        // 写入 segment count
        UnsafeOps.putInt(currentAddr, segments.length);
        currentAddr += 4;

        // 先写入占位的 total entries
        UnsafeOps.putInt(currentAddr, 0);
        currentAddr += 4;

        // 收集所有 entries
        int totalEntries = 0;
        for (Segment<E> segment : segments) {
            long stamp = segment.lock.readLock();
            try {
                for (Map.Entry<E, Entry> entry : segment.map.entrySet()) {
                    E element = entry.getKey();
                    Entry value = entry.getValue();

                    // 写入 element size
                    int elementSize = elementCodec.calculateSize(element);
                    UnsafeOps.putInt(currentAddr, elementSize);
                    currentAddr += 4;

                    // 写入 element bytes
                    int actualSize = elementCodec.encode(currentAddr, element);
                    currentAddr += actualSize;

                    // 写入相对偏移量
                    long offset = value.address - baseAddress;
                    UnsafeOps.putLong(currentAddr, offset);
                    currentAddr += 8;

                    // 写入 size
                    UnsafeOps.putInt(currentAddr, value.size);
                    currentAddr += 4;

                    totalEntries++;
                }
            } finally {
                segment.lock.unlockRead(stamp);
            }
        }

        // 回去写入正确的 total entries
        UnsafeOps.putInt(address + 4, totalEntries);

        return (int) (currentAddr - address);
    }

    /**
     * 序列化到内存地址（使用文件偏移，支持多段映射文件）
     */
    public int serializeWithFileOffsets(long address, MmapAllocator mmapAllocator) {
        long currentAddr = address;

        UnsafeOps.putInt(currentAddr, segments.length);
        currentAddr += 4;

        UnsafeOps.putInt(currentAddr, 0);
        currentAddr += 4;

        int totalEntries = 0;
        for (Segment<E> segment : segments) {
            long stamp = segment.lock.readLock();
            try {
                for (Map.Entry<E, Entry> entry : segment.map.entrySet()) {
                    E element = entry.getKey();
                    Entry value = entry.getValue();

                    int elementSize = elementCodec.calculateSize(element);
                    UnsafeOps.putInt(currentAddr, elementSize);
                    currentAddr += 4;

                    int actualSize = elementCodec.encode(currentAddr, element);
                    currentAddr += actualSize;

                    long fileOffset = mmapAllocator.getFileOffsetForAddress(value.address);
                    UnsafeOps.putLong(currentAddr, fileOffset);
                    currentAddr += 8;

                    UnsafeOps.putInt(currentAddr, value.size);
                    currentAddr += 4;

                    totalEntries++;
                }
            } finally {
                segment.lock.unlockRead(stamp);
            }
        }

        UnsafeOps.putInt(address + 4, totalEntries);

        return (int) (currentAddr - address);
    }

    /**
     * 从内存地址反序列化（使用相对偏移量）
     */
    public void deserializeWithOffsets(long address, int totalSize, long baseAddress) {
        long currentAddr = address;

        // 读取 segment count
        int segmentCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 验证 segment count
        if (segmentCount != segments.length) {
            throw new IllegalStateException("段数不匹配: 期望 " + segments.length + ", 实际 " + segmentCount);
        }

        // 清空所有 segment
        for (Segment<E> segment : segments) {
            segment.clear();
        }

        // 读取总条目数
        int totalEntries = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 边界检查：每条 entry 至少占 4(elementSize)+1(element)+8(offset)+4(size)=17B
        int maxPossibleEntries = (totalSize - 8) / 17;
        if (totalEntries < 0 || totalEntries > maxPossibleEntries) {
            throw new IllegalStateException("反序列化数据损坏：totalEntries=" + totalEntries
                    + " 超出合法范围 [0, " + maxPossibleEntries + "]");
        }

        // 读取所有数据
        for (int i = 0; i < totalEntries; i++) {
            // 读取 element size
            int elementSize = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 边界检查：elementSize 不能为负或超过剩余空间
            if (elementSize < 0 || elementSize > totalSize) {
                throw new IllegalStateException("反序列化数据损坏：第 " + i
                        + " 条 entry 的 elementSize=" + elementSize + " 非法");
            }

            // 读取 element
            E element = elementCodec.decode(currentAddr);
            currentAddr += elementSize;

            // 读取相对偏移量
            long offset = UnsafeOps.getLong(currentAddr);
            currentAddr += 8;

            // 计算绝对地址
            long addr = baseAddress + offset;

            // 读取 size
            int sz = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 放入正确的段
            int targetSegmentIndex = getSegmentIndex(element);
            segments[targetSegmentIndex].forcePut(element, new Entry(addr, sz));
        }

        this.size.set(totalEntries);
    }

    /**
     * 从内存地址反序列化（读取文件偏移并转换为物理地址，支持多段映射文件）
     */
    public void deserializeWithFileOffsets(long address, int totalSize, MmapAllocator mmapAllocator) {
        long currentAddr = address;

        int segmentCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        if (segmentCount != segments.length) {
            throw new IllegalStateException("段数不匹配: 期望 " + segments.length + ", 实际 " + segmentCount);
        }

        for (Segment<E> segment : segments) {
            segment.clear();
        }

        int totalEntries = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        int maxPossibleEntries = (totalSize - 8) / 17;
        if (totalEntries < 0 || totalEntries > maxPossibleEntries) {
            throw new IllegalStateException("反序列化数据损坏：totalEntries=" + totalEntries
                    + " 超出合法范围 [0, " + maxPossibleEntries + "]");
        }

        for (int i = 0; i < totalEntries; i++) {
            int elementSize = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            if (elementSize < 0 || elementSize > totalSize) {
                throw new IllegalStateException("反序列化数据损坏：第 " + i
                        + " 条 entry 的 elementSize=" + elementSize + " 非法");
            }

            E element = elementCodec.decode(currentAddr);
            currentAddr += elementSize;

            long fileOffset = UnsafeOps.getLong(currentAddr);
            currentAddr += 8;
            long addr = mmapAllocator.getAddressForOffset(fileOffset);

            int sz = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            int targetSegmentIndex = getSegmentIndex(element);
            segments[targetSegmentIndex].forcePut(element, new Entry(addr, sz));
        }

        this.size.set(totalEntries);
    }

    private Segment<E> getSegment(E element) {
        int hash = element.hashCode();
        int index = hash & segmentMask;
        return segments[index];
    }

    private int getSegmentIndex(E element) {
        int hash = element.hashCode();
        return hash & segmentMask;
    }

    /**
     * 分段实现
     */
    private static class Segment<E> {
        private final StampedLock lock;
        private final Map<E, Entry> map;

        Segment(int initialCapacity) {
            this.lock = new StampedLock();
            this.map = new HashMap<>(initialCapacity);
        }

        int serializedSize(Codec<E> elementCodec) {
            long stamp = lock.readLock();
            try {
                int size = 4; // segment entry count

                for (Map.Entry<E, Entry> entry : map.entrySet()) {
                    E element = entry.getKey();
                    int elementSize = elementCodec.calculateSize(element);
                    size += 4 + elementSize + 8 + 4;
                }

                return size;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        SetAddResult add(E element, long address, int size) {
            long stamp = lock.writeLock();
            try {
                Entry oldEntry = map.put(element, new Entry(address, size));
                if (oldEntry != null) {
                    return SetAddResult.alreadyExists(oldEntry.address, oldEntry.size);
                } else {
                    return SetAddResult.newlyAdded();
                }
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        boolean contains(E element) {
            long stamp = lock.tryOptimisticRead();
            boolean contains = map.containsKey(element);

            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    contains = map.containsKey(element);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return contains;
        }

        long get(E element) {
            long stamp = lock.tryOptimisticRead();
            Entry entry = map.get(element);

            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    entry = map.get(element);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return entry != null ? entry.address : 0;
        }

        int getSize(E element) {
            long stamp = lock.tryOptimisticRead();
            Entry entry = map.get(element);

            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    entry = map.get(element);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return entry != null ? entry.size : -1;
        }

        SetRemoveResult remove(E element) {
            long stamp = lock.writeLock();
            try {
                Entry entry = map.remove(element);
                if (entry != null) {
                    return SetRemoveResult.removed(entry.address, entry.size);
                } else {
                    return SetRemoveResult.notPresent();
                }
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        void forEach(SetEntryConsumer<E> consumer) {
            long stamp = lock.readLock();
            try {
                for (Map.Entry<E, Entry> entry : map.entrySet()) {
                    consumer.accept(entry.getKey(), entry.getValue().address, entry.getValue().size);
                }
            } finally {
                lock.unlockRead(stamp);
            }
        }

        void clear() {
            long stamp = lock.writeLock();
            try {
                map.clear();
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        void forcePut(E element, Entry entry) {
            map.put(element, entry);
        }
    }

    /**
     * Entry 保存元素数据的内存地址和大小
     */
    private static class Entry {
        final long address;
        final int size;

        Entry(long address, int size) {
            this.address = address;
            this.size = size;
        }
    }
}
