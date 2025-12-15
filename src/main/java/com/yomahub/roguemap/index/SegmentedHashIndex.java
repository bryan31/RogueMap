package com.yomahub.roguemap.index;

import com.yomahub.roguemap.func.EntryConsumer;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;

import java.util.concurrent.locks.StampedLock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用乐观锁实现的分段哈希索引，支持高并发访问
 *
 * 使用 StampedLock 提供比 ReentrantLock 更好的读性能。
 * 分段设计减少了多线程场景下的锁竞争。
 */
public class SegmentedHashIndex<K> implements Index<K> {

    private static final int DEFAULT_SEGMENT_COUNT = 64;
    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    private final Segment<K>[] segments;
    private final int segmentMask;
    private final AtomicInteger size;
    private final Codec<K> keyCodec;  // 用于序列化键

    @SuppressWarnings("unchecked")
    public SegmentedHashIndex() {
        this(null, DEFAULT_SEGMENT_COUNT, DEFAULT_INITIAL_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public SegmentedHashIndex(int segmentCount, int initialCapacityPerSegment) {
        this(null, segmentCount, initialCapacityPerSegment);
    }

    @SuppressWarnings("unchecked")
    public SegmentedHashIndex(Codec<K> keyCodec) {
        this(keyCodec, DEFAULT_SEGMENT_COUNT, DEFAULT_INITIAL_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public SegmentedHashIndex(Codec<K> keyCodec, int segmentCount, int initialCapacityPerSegment) {
        if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
            throw new IllegalArgumentException("Segment count must be a power of 2");
        }

        this.segments = new Segment[segmentCount];
        this.segmentMask = segmentCount - 1;
        this.size = new AtomicInteger(0);
        this.keyCodec = keyCodec;

        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new Segment<>(initialCapacityPerSegment);
        }
    }

    @Override
    public long put(K key, long address, int valueSize) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (address == 0) {
            throw new IllegalArgumentException("Invalid address: 0");
        }

        Segment<K> segment = getSegment(key);
        long oldAddress = segment.put(key, address, valueSize);

        if (oldAddress == 0) {
            size.incrementAndGet();
        }

        return oldAddress;
    }

    @Override
    public long get(K key) {
        if (key == null) {
            return 0;
        }

        Segment<K> segment = getSegment(key);
        return segment.get(key);
    }

    @Override
    public int getSize(K key) {
        if (key == null) {
            return -1;
        }

        Segment<K> segment = getSegment(key);
        return segment.getSize(key);
    }

    @Override
    public long remove(K key) {
        if (key == null) {
            return 0;
        }

        Segment<K> segment = getSegment(key);
        long address = segment.remove(key);

        if (address != 0) {
            size.decrementAndGet();
        }

        return address;
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) {
            return false;
        }

        Segment<K> segment = getSegment(key);
        return segment.containsKey(key);
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public void clear() {
        clear(null);
    }

    @Override
    public void clear(EntryConsumer action) {
        for (Segment<K> segment : segments) {
            long stamp = segment.lock.writeLock();
            try {
                if (action != null) {
                    for (Entry entry : segment.map.values()) {
                        action.accept(entry.address, entry.size);
                    }
                }
                int count = segment.map.size();
                segment.map.clear();
                size.addAndGet(-count);
            } finally {
                segment.lock.unlockWrite(stamp);
            }
        }
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public int serializedSize() {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Serialization not supported: keyCodec is null");
        }

        int totalSize = 8;  // segment count (4 bytes) + total entry count (4 bytes)

        for (Segment<K> segment : segments) {
            totalSize += segment.serializedSize(keyCodec);
        }

        return totalSize;
    }

    @Override
    public int serialize(long address) {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Serialization not supported: keyCodec is null");
        }

        long currentAddr = address;

        // 写入 segment count
        UnsafeOps.putInt(currentAddr, segments.length);
        currentAddr += 4;

        // 写入 total entry count
        UnsafeOps.putInt(currentAddr, size.get());
        currentAddr += 4;

        // 写入每个 segment
        for (Segment<K> segment : segments) {
            int written = segment.serialize(currentAddr, keyCodec);
            currentAddr += written;
        }

        return (int) (currentAddr - address);
    }

    @Override
    public void deserialize(long address, int totalSize) {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Deserialization not supported: keyCodec is null");
        }

        long currentAddr = address;

        // 读取 segment count
        int segmentCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 读取 total entry count
        int totalEntryCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 验证 segment count
        if (segmentCount != segments.length) {
            throw new IllegalStateException(
                "Segment number mismatch: expected " + segments.length + ", actual " + segmentCount);
        }

        // 清空所有 segment
        for (Segment<K> segment : segments) {
            segment.clear();
        }

        // 反序列化每个 segment
        for (Segment<K> segment : segments) {
            int read = segment.deserialize(currentAddr, keyCodec);
            currentAddr += read;
        }

        this.size.set(totalEntryCount);
    }

    private Segment<K> getSegment(K key) {
        int hash = key.hashCode();
        int index = hash & segmentMask;
        return segments[index];
    }

    /**
     * 每个段都有自己的锁，用于减少锁竞争
     */
    private static class Segment<K> {
        private final StampedLock lock;
        private final Map<K, Entry> map;

        Segment(int initialCapacity) {
            this.lock = new StampedLock();
            this.map = new HashMap<>(initialCapacity);
        }

        int serializedSize(Codec<K> keyCodec) {
            long stamp = lock.readLock();
            try {
                int size = 4;  // segment entry count

                for (Map.Entry<K, Entry> entry : map.entrySet()) {
                    K key = entry.getKey();
                    int keySize = keyCodec.calculateSize(key);
                    if (keySize < 0) {
                        throw new IllegalStateException("Key size cannot be negative");
                    }
                    size += 4 + keySize + 8 + 4;
                }

                return size;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        int serialize(long address, Codec<K> keyCodec) {
            long stamp = lock.readLock();
            try {
                long currentAddr = address;

                // 写入 segment entry count
                UnsafeOps.putInt(currentAddr, map.size());
                currentAddr += 4;

                // 写入每个 entry
                for (Map.Entry<K, Entry> entry : map.entrySet()) {
                    K key = entry.getKey();

                    int keySize = keyCodec.calculateSize(key);
                    if (keySize < 0) {
                        throw new IllegalStateException("Key size cannot be negative");
                    }

                    // key size
                    UnsafeOps.putInt(currentAddr, keySize);
                    currentAddr += 4;

                    // key bytes
                    int actualKeySize = keyCodec.encode(currentAddr, key);
                    currentAddr += actualKeySize;

                    // address
                    UnsafeOps.putLong(currentAddr, entry.getValue().address);
                    currentAddr += 8;

                    // size
                    UnsafeOps.putInt(currentAddr, entry.getValue().size);
                    currentAddr += 4;
                }

                return (int) (currentAddr - address);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        int deserialize(long address, Codec<K> keyCodec) {
            long stamp = lock.writeLock();
            try {
                map.clear();
                long currentAddr = address;

                // 读取 segment entry count
                int entryCount = UnsafeOps.getInt(currentAddr);
                currentAddr += 4;

                // 读取每个 entry
                for (int i = 0; i < entryCount; i++) {
                    // key size
                    int keySize = UnsafeOps.getInt(currentAddr);
                    currentAddr += 4;

                    // key
                    K key = keyCodec.decode(currentAddr);
                    currentAddr += keySize;

                    // address
                    long addr = UnsafeOps.getLong(currentAddr);
                    currentAddr += 8;

                    // size
                    int sz = UnsafeOps.getInt(currentAddr);
                    currentAddr += 4;

                    map.put(key, new Entry(addr, sz));
                }

                return (int) (currentAddr - address);
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        int serializeWithOffsets(long address, Codec<K> keyCodec, long baseAddress) {
            long stamp = lock.readLock();
            try {
                long currentAddr = address;

                // 写入 segment entry count
                UnsafeOps.putInt(currentAddr, map.size());
                currentAddr += 4;

                // 写入每个 entry
                for (Map.Entry<K, Entry> entry : map.entrySet()) {
                    K key = entry.getKey();

                    int keySize = keyCodec.calculateSize(key);
                    if (keySize < 0) {
                        throw new IllegalStateException("Key size cannot be negative");
                    }

                    // key size
                    UnsafeOps.putInt(currentAddr, keySize);
                    currentAddr += 4;

                    // key bytes
                    int actualKeySize = keyCodec.encode(currentAddr, key);
                    currentAddr += actualKeySize;

                    // 相对偏移量（而不是绝对地址）
                    long offset = entry.getValue().address - baseAddress;
                    UnsafeOps.putLong(currentAddr, offset);
                    currentAddr += 8;

                    // size
                    UnsafeOps.putInt(currentAddr, entry.getValue().size);
                    currentAddr += 4;
                }

                return (int) (currentAddr - address);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        int deserializeWithOffsets(long address, Codec<K> keyCodec, long baseAddress) {
            long stamp = lock.writeLock();
            try {
                map.clear();
                long currentAddr = address;

                // 读取 segment entry count
                int entryCount = UnsafeOps.getInt(currentAddr);
                currentAddr += 4;

                // 读取每个 entry
                for (int i = 0; i < entryCount; i++) {
                    // key size
                    int keySize = UnsafeOps.getInt(currentAddr);
                    currentAddr += 4;

                    // key
                    K key = keyCodec.decode(currentAddr);
                    currentAddr += keySize;

                    // 相对偏移量
                    long offset = UnsafeOps.getLong(currentAddr);
                    currentAddr += 8;

                    // 重新计算绝对内存地址
                    long addr = baseAddress + offset;

                    // size
                    int sz = UnsafeOps.getInt(currentAddr);
                    currentAddr += 4;

                    map.put(key, new Entry(addr, sz));
                }

                return (int) (currentAddr - address);
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        long put(K key, long address, int size) {
            long stamp = lock.writeLock();
            try {
                Entry oldEntry = map.put(key, new Entry(address, size));
                return oldEntry != null ? oldEntry.address : 0;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        // 用于反序列化时强制放入数据（不需要锁，因为此时没有其他线程访问）
        void forcePut(K key, Entry entry) {
            map.put(key, entry);
        }

        long get(K key) {
            // 首先尝试乐观读（无锁）
            long stamp = lock.tryOptimisticRead();
            Entry entry = map.get(key);

            if (!lock.validate(stamp)) {
                // 如果乐观读失败，退回到读锁
                stamp = lock.readLock();
                try {
                    entry = map.get(key);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return entry != null ? entry.address : 0;
        }

        int getSize(K key) {
            // 首先尝试乐观读
            long stamp = lock.tryOptimisticRead();
            Entry entry = map.get(key);

            if (!lock.validate(stamp)) {
                // 如果乐观读失败，退回到读锁
                stamp = lock.readLock();
                try {
                    entry = map.get(key);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return entry != null ? entry.size : -1;
        }

        long remove(K key) {
            long stamp = lock.writeLock();
            try {
                Entry entry = map.remove(key);
                return entry != null ? entry.address : 0;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        boolean containsKey(K key) {
            // 首先尝试乐观读
            long stamp = lock.tryOptimisticRead();
            boolean contains = map.containsKey(key);

            if (!lock.validate(stamp)) {
                // 如果乐观读失败，退回到读锁
                stamp = lock.readLock();
                try {
                    contains = map.containsKey(key);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return contains;
        }

        void clear() {
            long stamp = lock.writeLock();
            try {
                map.clear();
            } finally {
                lock.unlockWrite(stamp);
            }
        }
    }

    /**
     * Entry 保存值的内存地址和大小
     */
    private static class Entry {
        final long address;
        final int size;

        Entry(long address, int size) {
            this.address = address;
            this.size = size;
        }
    }

    @Override
    public int serializeWithOffsets(long address, long baseAddress) {
        long currentAddr = address;

        // 写入 segment count
        UnsafeOps.putInt(currentAddr, segments.length);
        currentAddr += 4;

        // 收集所有 entries 并连续存储
        int totalEntries = 0;
        long startAddr = currentAddr + 4; // 跳过 total entries 字段

        // 先写入占位的 total entries
        UnsafeOps.putInt(currentAddr, 0);
        currentAddr += 4;

        // 遍历所有 segments，收集所有 entries
        for (Segment<K> segment : segments) {
            long stamp = segment.lock.readLock();
            try {
                for (Map.Entry<K, Entry> entry : segment.map.entrySet()) {
                    K key = entry.getKey();
                    Entry value = entry.getValue();

                    // 写入 key size
                    int keySize = keyCodec.calculateSize(key);
                    UnsafeOps.putInt(currentAddr, keySize);
                    currentAddr += 4;

                    // 写入 key bytes
                    int actualKeySize = keyCodec.encode(currentAddr, key);
                    currentAddr += actualKeySize;

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
        UnsafeOps.putInt(startAddr - 4, totalEntries);

        return (int) (currentAddr - address);
    }

    @Override
    public void deserializeWithOffsets(long address, int totalSize, long baseAddress) {
        long currentAddr = address;

        // 读取 segment count
        int segmentCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 验证 segment count
        if (segmentCount != segments.length) {
            throw new IllegalStateException("Segment number mismatch: expected " + segments.length + ", actual " + segmentCount);
        }

        // 清空所有 segment
        for (Segment<K> segment : segments) {
            segment.clear();
        }

        // 读取所有数据并重新分配到正确的段
        int totalEntries = UnsafeOps.getInt(currentAddr); // 读取总条目数
        currentAddr += 4;

        for (int i = 0; i < totalEntries; i++) {
            // 读取 key size
            int keySize = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 读取 key
            K key = keyCodec.decode(currentAddr);
            currentAddr += keySize;

            // 读取相对偏移量
            long offset = UnsafeOps.getLong(currentAddr);
            currentAddr += 8;

            // 重新计算绝对内存地址
            long addr = baseAddress + offset;

            // 读取 size
            int sz = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 重新计算 key 应该属于哪个段
            int targetSegmentIndex = getSegmentIndex(key);
            Segment<K> targetSegment = segments[targetSegmentIndex];

            // 将数据放入正确的段中
            targetSegment.forcePut(key, new Entry(addr, sz));
        }

        // 重新计算总大小
        int actualTotalSize = 0;
        for (Segment<K> segment : segments) {
            actualTotalSize += segment.map.size();
        }
        this.size.set(actualTotalSize);
    }

    @Override
    public void forEach(EntryConsumer action) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }
        for (Segment<K> segment : segments) {
            long stamp = segment.lock.readLock();
            try {
                for (Entry entry : segment.map.values()) {
                    action.accept(entry.address, entry.size);
                }
            } finally {
                segment.lock.unlockRead(stamp);
            }
        }
    }

    /**
     * 根据键计算应该属于哪个段
     */
    private int getSegmentIndex(K key) {
        int hash = key.hashCode();
        return hash & segmentMask;
    }
}
