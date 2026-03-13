package com.yomahub.roguemap.index;

import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;

import java.util.concurrent.locks.StampedLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
            throw new IllegalArgumentException("段数必须是 2 的幂次方");
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
            throw new IllegalArgumentException("键不能为 null");
        }
        if (address == 0) {
            throw new IllegalArgumentException("无效的地址: 0");
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
    public IndexUpdateResult putAndGetOld(K key, long newAddress, int newSize) {
        if (key == null) {
            throw new IllegalArgumentException("键不能为 null");
        }
        if (newAddress == 0) {
            throw new IllegalArgumentException("无效的地址: 0");
        }

        Segment<K> segment = getSegment(key);
        IndexUpdateResult result = segment.putAndGetOld(key, newAddress, newSize);

        if (!result.wasPresent) {
            size.incrementAndGet();
        }

        return result;
    }

    @Override
    public IndexRemoveResult removeAndGet(K key) {
        if (key == null) {
            return IndexRemoveResult.notPresent();
        }

        Segment<K> segment = getSegment(key);
        IndexRemoveResult result = segment.removeAndGet(key);

        if (result.wasPresent) {
            size.decrementAndGet();
        }

        return result;
    }

    @Override
    public void forEach(IndexEntryConsumer consumer) {
        if (consumer == null) {
            return;
        }

        for (Segment<K> segment : segments) {
            segment.forEach(consumer);
        }
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
        for (Segment<K> segment : segments) {
            segment.clear();
        }
        size.set(0);
    }

    @Override
    public void close() {
        clear();
    }

    /**
     * 将一批操作原子地应用到索引中（事务提交路径）。
     *
     * <p>调用方须保证：
     * <ul>
     *   <li>所有 PUT 操作的 newAddress 已在锁外预先分配并编码</li>
     *   <li>operations 已按 segmentIndex 升序排序（防止死锁）</li>
     * </ul>
     *
     * <p>若中途抛出异常，方法会尽力回滚已应用的操作并将旧值恢复到索引中。
     *
     * @param operations 有序（按 segmentIndex 升序）的操作列表
     * @return 被替换/删除的旧值信息列表，与 operations 一一对应；
     *         若该 key 原本不存在则对应条目的 wasPresent=false
     */
    public List<IndexUpdateResult> applyBatch(List<BatchEntry<K>> operations) {
        if (operations == null || operations.isEmpty()) {
            return new ArrayList<>();
        }

        // 按 segment 分组，并按 segment index 升序加写锁（防止死锁）
        TreeMap<Integer, List<Integer>> segToOps = new TreeMap<>();
        for (int i = 0; i < operations.size(); i++) {
            BatchEntry<K> op = operations.get(i);
            int segIdx = getSegmentIndex(op.key);
            segToOps.computeIfAbsent(segIdx, k -> new ArrayList<>()).add(i);
        }

        // 结果列表（按 operations 顺序）
        IndexUpdateResult[] results = new IndexUpdateResult[operations.size()];

        // 按升序逐段加写锁并应用操作
        List<Integer> acquiredSegments = new ArrayList<>(segToOps.size());
        List<long[]> stamps = new ArrayList<>(segToOps.size()); // [0]=segIdx, [1]=stamp
        int appliedCount = 0; // 已成功应用的操作数，用于补偿回滚

        try {
            for (Map.Entry<Integer, List<Integer>> entry : segToOps.entrySet()) {
                int segIdx = entry.getKey();
                Segment<K> seg = segments[segIdx];
                long stamp = seg.lock.writeLock();
                acquiredSegments.add(segIdx);
                stamps.add(new long[]{segIdx, stamp});

                for (int opIdx : entry.getValue()) {
                    BatchEntry<K> op = operations.get(opIdx);
                    IndexUpdateResult result;
                    if (op.opType == BatchEntry.OpType.PUT) {
                        Entry oldEntry = seg.map.put(op.key, new Entry(op.newAddress, op.newSize));
                        if (oldEntry != null) {
                            result = IndexUpdateResult.withOldValue(oldEntry.address, oldEntry.size);
                        } else {
                            result = IndexUpdateResult.noOldValue();
                            size.incrementAndGet();
                        }
                    } else { // REMOVE
                        Entry oldEntry = seg.map.remove(op.key);
                        if (oldEntry != null) {
                            result = IndexUpdateResult.withOldValue(oldEntry.address, oldEntry.size);
                            size.decrementAndGet();
                        } else {
                            result = IndexUpdateResult.noOldValue();
                        }
                    }
                    results[opIdx] = result;
                    appliedCount++;
                }
            }
        } catch (Exception e) {
            // 补偿：逆序回滚已应用的操作（当前已加锁的 segment 内）
            // 注意：此分支极少触发（HashMap.put 本身不抛异常），此处仅作防御性处理
            rollbackApplied(operations, results, appliedCount);
            throw new RuntimeException("事务提交失败，已回滚", e);
        } finally {
            // 释放所有写锁
            int stampIdx = 0;
            for (Integer segIdx : acquiredSegments) {
                Segment<K> seg = segments[segIdx];
                long stamp = stamps.get(stampIdx)[1];
                seg.lock.unlockWrite(stamp);
                stampIdx++;
            }
        }

        return new ArrayList<>(java.util.Arrays.asList(results));
    }

    /**
     * 补偿回滚：将已应用的 appliedCount 条操作逆向恢复。
     *
     * <p><b>重要</b>：此方法在 applyBatch() 的 catch 块内调用，此时外层仍持有所有已获取的段写锁
     * （锁在 finally 中释放）。由于 {@link StampedLock} 不可重入，此方法<b>不能</b>尝试重新获取锁，
     * 而是直接操作 segment.map —— 这是安全的，因为调用者已持有对应段的写锁。
     */
    private void rollbackApplied(List<BatchEntry<K>> operations, IndexUpdateResult[] results, int appliedCount) {
        for (int i = appliedCount - 1; i >= 0; i--) {
            BatchEntry<K> op = operations.get(i);
            IndexUpdateResult result = results[i];
            if (result == null) continue;
            int segIdx = getSegmentIndex(op.key);
            Segment<K> seg = segments[segIdx];
            // 不获取锁 —— 调用者（applyBatch）已持有该 segment 的写锁
            if (op.opType == BatchEntry.OpType.PUT) {
                if (result.wasPresent) {
                    // 还原旧值
                    seg.map.put(op.key, new Entry(result.oldAddress, result.oldSize));
                } else {
                    // 新增的，撤销
                    seg.map.remove(op.key);
                    size.decrementAndGet();
                }
            } else { // REMOVE
                if (result.wasPresent) {
                    // 还原被删除的值
                    seg.map.put(op.key, new Entry(result.oldAddress, result.oldSize));
                    size.incrementAndGet();
                }
            }
        }
    }

    @Override
    public int serializedSize() {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("无法序列化：keyCodec 为 null");
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
            throw new UnsupportedOperationException("无法序列化：keyCodec 为 null");
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
            throw new UnsupportedOperationException("无法反序列化：keyCodec 为 null");
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
                "段数不匹配：期望 " + segments.length + "，实际 " + segmentCount);
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
                        throw new IllegalStateException("键的大小不能为负数");
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
                        throw new IllegalStateException("键的大小不能为负数");
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
                        throw new IllegalStateException("键的大小不能为负数");
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

        IndexUpdateResult putAndGetOld(K key, long newAddress, int newSize) {
            long stamp = lock.writeLock();
            try {
                Entry oldEntry = map.put(key, new Entry(newAddress, newSize));
                if (oldEntry != null) {
                    return IndexUpdateResult.withOldValue(oldEntry.address, oldEntry.size);
                } else {
                    return IndexUpdateResult.noOldValue();
                }
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        IndexRemoveResult removeAndGet(K key) {
            long stamp = lock.writeLock();
            try {
                Entry entry = map.remove(key);
                if (entry != null) {
                    return IndexRemoveResult.removed(entry.address, entry.size);
                } else {
                    return IndexRemoveResult.notPresent();
                }
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        void forEach(IndexEntryConsumer consumer) {
            long stamp = lock.readLock();
            try {
                for (Map.Entry<K, Entry> entry : map.entrySet()) {
                    consumer.accept(entry.getKey(), entry.getValue().address, entry.getValue().size);
                }
            } finally {
                lock.unlockRead(stamp);
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
            throw new IllegalStateException("段数不匹配: 期望 " + segments.length + ", 实际 " + segmentCount);
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

    /**
     * 根据键计算应该属于哪个段（public 供事务使用，按 segmentIndex 升序排锁）
     */
    public int getSegmentIndex(K key) {
        int hash = key.hashCode();
        return hash & segmentMask;
    }
}
