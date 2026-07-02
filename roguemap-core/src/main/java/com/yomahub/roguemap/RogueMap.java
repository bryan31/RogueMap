package com.yomahub.roguemap;

import com.yomahub.roguemap.index.BatchEntry;
import com.yomahub.roguemap.index.HashIndex;
import com.yomahub.roguemap.index.Index;
import com.yomahub.roguemap.index.IndexRemoveResult;
import com.yomahub.roguemap.index.IndexUpdateResult;
import com.yomahub.roguemap.index.IntPrimitiveIndex;
import com.yomahub.roguemap.index.LowHeapOptions;
import com.yomahub.roguemap.index.LowHeapStringIndex;
import com.yomahub.roguemap.index.LongPrimitiveIndex;
import com.yomahub.roguemap.index.SegmentedHashIndex;
import com.yomahub.roguemap.map.RogueMapIterator;
import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.StorageEngine;
import com.yomahub.roguemap.util.TTLUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * RogueMap - 高性能堆外键值存储
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class RogueMap<K, V> implements Iterable<Map.Entry<K, V>>, AutoCloseable {

    private final Index<K> index;
    private final StorageEngine storage;
    private final Codec<K> keyCodec;
    private final Codec<V> valueCodec;
    private final Allocator allocator;
    private final AutoCheckpointManager autoCheckpointManager;
    private final long defaultTTLMillis;  // 默认 TTL（毫秒），0 表示永不过期

    private RogueMap(Index<K> index, StorageEngine storage,
            Codec<K> keyCodec, Codec<V> valueCodec,
            Allocator allocator, long autoCheckpointInterval, int autoCheckpointOperations,
            long defaultTTLMillis) {
        this.index = index;
        this.storage = storage;
        this.keyCodec = keyCodec;
        this.valueCodec = valueCodec;
        this.allocator = allocator;
        this.defaultTTLMillis = defaultTTLMillis;

        // 创建并启动自动 checkpoint（仅持久化模式）
        if (autoCheckpointInterval > 0 || autoCheckpointOperations > 0) {
            this.autoCheckpointManager = new AutoCheckpointManager(
                    this::checkpoint,
                    autoCheckpointInterval,
                    autoCheckpointOperations);
            this.autoCheckpointManager.start();
        } else {
            this.autoCheckpointManager = null;
        }
    }

    /**
     * 将键值对放入 map（使用默认 TTL）
     *
     * @param key   键
     * @param value 值
     * @return 之前的值，如果没有则返回 null
     */
    public V put(K key, V value) {
        return put(key, value, defaultTTLMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 将键值对放入 map（指定 TTL）
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时间（0 表示永不过期）
     * @param unit  时间单位
     * @return 之前的值，如果没有则返回 null
     */
    public V put(K key, V value, long ttl, TimeUnit unit) {
        if (key == null) {
            throw new IllegalArgumentException("键不能为 null");
        }

        long ttlMillis = (ttl > 0 && unit != null) ? unit.toMillis(ttl) : 0;

        // 计算所需大小（数据大小 + TTL header）
        int valueSize = valueCodec.calculateSize(value);
        if (valueSize < 0) {
            throw new IllegalStateException("无法确定值的大小");
        }
        int totalSize = TTLUtils.totalSize(valueSize);

        // 为值分配内存（包含 TTL header）
        long newAddress = allocator.allocate(totalSize);
        if (newAddress == 0) {
            throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
        }

        try {
            // 写入过期时间到 TTL header
            long expireTime = TTLUtils.calculateExpireTime(ttlMillis);
            TTLUtils.writeExpireTime(newAddress, expireTime);

            // 将值编码到 TTL header 之后
            long dataAddress = TTLUtils.getDataAddress(newAddress);
            int actualSize = valueCodec.encode(dataAddress, value);
            int actualTotalSize = TTLUtils.totalSize(actualSize);

            // 原子性地更新索引并获取旧值信息
            // 这确保了在多线程环境下，获取旧地址和更新索引是原子操作
            IndexUpdateResult result = index.putAndGetOld(key, newAddress, actualTotalSize);

            // 处理旧值
            V oldValue = null;
            if (result.wasPresent) {
                // 检查旧值是否过期
                if (TTLUtils.isDataExpired(result.oldAddress)) {
                    // 旧值已过期，不解码直接释放
                    allocator.free(result.oldAddress, result.oldSize);
                } else {
                    // 先解码旧值（此时旧地址还未被释放，是安全的）
                    oldValue = valueCodec.decode(TTLUtils.getDataAddress(result.oldAddress));

                    // 解码完成后才释放旧内存
                    allocator.free(result.oldAddress, result.oldSize);
                }
            }

            // 触发自动 checkpoint（操作计数模式）
            if (autoCheckpointManager != null) {
                autoCheckpointManager.onWriteOperation();
            }

            return oldValue;
        } catch (Exception e) {
            // 异常处理：释放新分配的内存
            allocator.free(newAddress, totalSize);
            throw e;
        }
    }

    /**
     * 批量放入键值对（使用默认 TTL）。
     *
     * <p>语义与 {@link java.util.Map#putAll} 一致：<b>不保证跨键原子性</b>。
     * 单个键的更新各自原子，并发读写可与本操作交错；抛出异常时部分条目可能
     * 已写入。需要原子多键写入时请使用 {@link #beginTransaction()}。
     *
     * <p><b>吞吐说明（据实测）</b>：分段索引模式下按段分组、每段仅加一次写锁，
     * 但在 10 万条短字符串基准下，单线程加速比约 0.5–1.0x、6 线程并发约
     * 0.65–0.8x——即与循环 {@code put} 基本持平或略低。原因是 putAll 路径额外
     * 产生 {@link com.yomahub.roguemap.index.BatchEntry} 装箱、entries 列表与
     * results 数组开销，而段锁本身（64 段 StampedLock）竞争很低，锁摊薄收益
     * 不足以抵消上述开销；瓶颈更多在 allocator CAS 与值编码上。putAll 的实际
     * 价值在于 API 便利性、以及一次大批次只触发一次自动 checkpoint 计数
     * （见 {@link AutoCheckpointManager#onWriteOperations(int)}）。其他索引模式
     * 下退化为逐条更新（功能等价）。
     *
     * @param m 键值对集合
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        putAll(m, defaultTTLMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 批量放入键值对（指定 TTL，覆盖默认 TTL）。
     *
     * <p>整批条目使用同一过期时间戳。其余语义见 {@link #putAll(Map)}。
     *
     * @param m    键值对集合
     * @param ttl  过期时间（0 表示永不过期）
     * @param unit 时间单位
     */
    public void putAll(Map<? extends K, ? extends V> m, long ttl, TimeUnit unit) {
        if (m == null) {
            throw new IllegalArgumentException("批量集合不能为 null");
        }
        if (m.isEmpty()) {
            return;
        }
        // 校验阶段：null 键整批拒绝（早于任何内存分配，无副作用）
        for (K key : m.keySet()) {
            if (key == null) {
                throw new IllegalArgumentException("键不能为 null");
            }
        }

        long ttlMillis = (ttl > 0 && unit != null) ? unit.toMillis(ttl) : 0;
        long expireTime = TTLUtils.calculateExpireTime(ttlMillis);

        // 编码分配阶段（锁外）：失败则释放本批次已分配内存后抛出，索引未动
        List<BatchEntry<K>> entries = new ArrayList<>(m.size());
        try {
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                int valueSize = valueCodec.calculateSize(e.getValue());
                if (valueSize < 0) {
                    throw new IllegalStateException("无法确定值的大小");
                }
                int totalSize = TTLUtils.totalSize(valueSize);
                long newAddress = allocator.allocate(totalSize);
                if (newAddress == 0) {
                    throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
                }
                try {
                    TTLUtils.writeExpireTime(newAddress, expireTime);
                    int actualSize = valueCodec.encode(TTLUtils.getDataAddress(newAddress), e.getValue());
                    entries.add(BatchEntry.put(e.getKey(), newAddress, TTLUtils.totalSize(actualSize)));
                } catch (RuntimeException | Error encodeErr) {
                    // 当前条目已分配但尚未记入 entries，单独释放
                    allocator.free(newAddress, totalSize);
                    throw encodeErr;
                }
            }
        } catch (RuntimeException | Error err) {
            for (BatchEntry<K> entry : entries) {
                allocator.free(entry.newAddress, entry.newSize);
            }
            throw err;
        }

        // 索引更新阶段：分段索引按段批量提交；异常时已提交的段保持生效（非原子语义）。
        // 若 putBatch 抛异常（如泛型 K 的 hashCode() 在分组阶段抛出，此时尚无段被提交；
        // 或极罕见的加锁阶段 OOM），防御性地释放本批次已分配的全部内存。
        // 说明：在 append-only + allocator.free() no-op 语义下，对已提交段对应的 entry
        // 调用 free 仅累加 dead bytes 指标、不回收真实内存，因此即便个别段已提交，
        // 整批 free 也只会让这些段的索引项"提前成为死字节"，不破坏内存安全性，
        // 非原子语义可接受。
        IndexUpdateResult[] results;
        try {
            results = index.putBatch(entries);
        } catch (RuntimeException | Error e) {
            for (BatchEntry<K> en : entries) {
                allocator.free(en.newAddress, en.newSize);
            }
            throw e;
        }

        // 旧值释放阶段（锁外，不解码旧值）
        for (IndexUpdateResult result : results) {
            if (result.wasPresent) {
                allocator.free(result.oldAddress, result.oldSize);
            }
        }

        if (autoCheckpointManager != null) {
            autoCheckpointManager.onWriteOperations(entries.size());
        }
    }

    /**
     * 根据键获取值
     *
     * <p>如果值已过期，会自动删除并返回 null。
     *
     * @param key 键
     * @return 值，如果未找到或已过期则返回 null
     */
    public V get(K key) {
        if (key == null) {
            return null;
        }

        long address = index.get(key);
        if (address == 0) {
            return null;
        }

        // 检查是否有过期时间
        long expireTime = TTLUtils.readExpireTime(address);
        if (expireTime > 0 && TTLUtils.isExpired(expireTime)) {
            // 惰性删除：过期则删除并返回 null
            remove(key);
            return null;
        }

        // 数据没有过期时间（expireTime == 0），直接从地址读取数据
        return valueCodec.decode(TTLUtils.getDataAddress(address));
    }

    /**
     * 批量获取。
     *
     * <p>返回的 Map 中不包含未找到或已过期的键（过期条目与 {@link #get}
     * 一致做惰性删除）；集合中的 null 元素被跳过。
     *
     * <p>说明：分段索引的读路径是乐观读，本方法不做段级批量优化，
     * 价值在于 API 便利性。
     *
     * @param keys 键集合
     * @return 命中的键值对（可修改的 HashMap，无序）
     */
    public Map<K, V> getAll(Collection<? extends K> keys) {
        if (keys == null) {
            throw new IllegalArgumentException("键集合不能为 null");
        }
        Map<K, V> result = new HashMap<>(Math.max(16, keys.size() * 2));
        for (K key : keys) {
            if (key == null) {
                continue;
            }
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 删除键值对
     *
     * @param key 键
     * @return 之前的值，如果没有则返回 null
     */
    public V remove(K key) {
        if (key == null) {
            return null;
        }

        // 原子性地删除并获取值信息
        IndexRemoveResult result = index.removeAndGet(key);
        if (!result.wasPresent) {
            return null;
        }

        // 先解码值（从 TTL header 之后读取）
        V oldValue = valueCodec.decode(TTLUtils.getDataAddress(result.address));

        // 释放内存
        allocator.free(result.address, result.size);

        // 触发自动 checkpoint（操作计数模式）
        if (autoCheckpointManager != null) {
            autoCheckpointManager.onWriteOperation();
        }

        return oldValue;
    }

    /**
     * 检查键是否存在
     *
     * <p>如果值已过期，此方法会返回 false（不会自动删除，     *
     * @param key 键
     * @return 如果存在（且未过期）返回 true，否则返回 false
     */
    public boolean containsKey(K key) {
        if (key == null) {
            return false;
        }

        long address = index.get(key);
        if (address == 0) {
            return false;
        }

        // 检查是否过期
        if (TTLUtils.isDataExpired(address)) {
            return false;
        }

        return true;
    }

    /**
     * 获取条目数量
     *
     * @return 条目数量
     */
    public int size() {
        return index.size();
    }

    /**
     * 检查 map 是否为空
     *
     * @return 如果为空返回 true
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 遍历所有键值对
     *
     * <p>对 map 中的每个键值对执行给定的操作。遍历期间不应该修改 map
     * （添加、更新或删除条目），否则可能导致不确定的行为。
     *
     * <p>注意：遍历过程中会对每个值进行反序列化，对于大量数据可能较慢。
     * 已过期的条目也会被遍历（但不会自动删除）。
     *
     * <p>示例：
     * <pre>{@code
     * map.forEach((key, value) -> {
     *     System.out.println(key + " = " + value);
     * });
     * }</pre>
     *
     * @param action 对每个键值对执行的操作，不能为 null
     */
    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<K, V> action) {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为 null");
        }

        index.forEach((key, address, size) -> {
            // 检查是否过期
            if (TTLUtils.isDataExpired(address)) {
                return; // 跳过已过期的条目
            }
            V value = valueCodec.decode(TTLUtils.getDataAddress(address));
            action.accept((K) key, value);
        });
    }

    /**
     * 返回遍历所有键值对的迭代器，使 RogueMap 支持增强 for 循环。
     *
     * <p>构造迭代器时会对当前索引做一次快照（仅快照键与值地址，值本身惰性解码），
     * 因此遍历期间不应修改 map（添加 / 更新 / 删除），否则结果不确定。
     * 已过期的条目会被跳过，语义与 {@link #forEach} 一致。
     *
     * <p>示例：
     * <pre>{@code
     * for (Map.Entry<String, User> entry : map) {
     *     System.out.println(entry.getKey() + " = " + entry.getValue());
     * }
     * }</pre>
     *
     * @return 键值对迭代器
     */
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
        return new RogueMapIterator<>(index, valueCodec);
    }

    /**
     * 返回可遍历所有键的 {@link Iterable}，便于用 for 循环只遍历键。
     *
     * <pre>{@code
     * for (String key : map.keys()) { ... }
     * }</pre>
     *
     * @return 键的可迭代视图
     */
    public Iterable<K> keys() {
        return () -> {
            Iterator<Map.Entry<K, V>> it = iterator();
            return new Iterator<K>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public K next() {
                    return it.next().getKey();
                }
            };
        };
    }

    /**
     * 返回可遍历所有值的 {@link Iterable}，便于用 for 循环只遍历值。
     *
     * <pre>{@code
     * for (User value : map.values()) { ... }
     * }</pre>
     *
     * @return 值的可迭代视图
     */
    public Iterable<V> values() {
        return () -> {
            Iterator<Map.Entry<K, V>> it = iterator();
            return new Iterator<V>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public V next() {
                    return it.next().getValue();
                }
            };
        };
    }

    /**
     * 移除所有条目
     */
    public void clear() {
        // 遍历所有条目并释放内存
        index.forEach((key, address, size) -> {
            allocator.free(address, size);
        });

        // 清空索引
        index.clear();
    }

    /**
     * 刷新所有待处理的更改（用于持久化存储）
     */
    public void flush() {
        storage.flush();
    }

    /**
     * 开启一个新事务。
     *
     * <p>事务提供多键操作的原子性（要么全部提交，要么全部回滚）。
     * 支持 try-with-resources：未调用 {@link RogueMapTransaction#commit()} 时，
     * {@code close()} 会自动执行 rollback。
     *
     * <p><b>仅支持 SegmentedHashIndex（默认索引）</b>。
     * 使用 basicIndex() 或 primitiveIndex() 构建的 RogueMap 不支持事务，调用此方法会抛出异常。
     *
     * <p>示例：
     * <pre>{@code
     * try (RogueMap.Transaction<K,V> txn = map.beginTransaction()) {
     *     txn.put("a", 1L);
     *     txn.remove("b");
     *     txn.commit();
     * }
     * }</pre>
     *
     * @return 新的事务实例
     * @throws UnsupportedOperationException 若当前索引类型不支持事务
     */
    public RogueMapTransaction<K, V> beginTransaction() {
        if (!(index instanceof com.yomahub.roguemap.index.SegmentedHashIndex)) {
            throw new UnsupportedOperationException(
                    "事务仅支持 SegmentedHashIndex（segmentedIndex 或默认索引）。" +
                    "basicIndex()、primitiveIndex() 和 lowHeapIndex() 不支持事务。");
        }
        @SuppressWarnings("unchecked")
        com.yomahub.roguemap.index.SegmentedHashIndex<K> segIndex =
                (com.yomahub.roguemap.index.SegmentedHashIndex<K>) index;
        return new RogueMapTransaction<>(this, segIndex, allocator, valueCodec);
    }

    /**
     * 将当前索引持久化到磁盘（检查点）
     *
     * <p>调用此方法后，即使进程崩溃（不调用 close()），下次打开文件时也能恢复到
     * 最近一次 checkpoint 时的状态。checkpoint 之后写入的数据在崩溃后会丢失。
     *
     * <p>建议在写入一定量数据后定期调用（例如每 1000 次 put 或每 5 秒），
     * 以控制崩溃时的数据丢失窗口。
     *
     * <p>注意：每次 checkpoint 会消耗一定的文件空间用于存储索引快照，
     * 可通过 compact() 回收。仅 persistent 模式有效。
     */
    public void checkpoint() {
        if (!(storage instanceof MmapStorage)) {
            return;
        }
        MmapStorage mmapStorage = (MmapStorage) storage;
        MmapAllocator mmapAllocator = mmapStorage.getAllocator();
        if (mmapAllocator.isTemporary()) {
            return;
        }

        // 序列化索引到当前数据尾部（复用已有的 saveMmapIndex 逻辑）
        // saveMmapIndex() 内部使用 allocate() 分配索引空间，allocator 偏移已推进到索引之后
        saveMmapIndex();

        // 强制刷盘确保持久化
        mmapAllocator.flush();
    }

    /**
     * 压缩存储文件，回收已删除/更新数据占用的空间
     *
     * <p>创建一个仅包含活跃数据的新文件，关闭当前实例，替换原文件后重新打开。
     * 返回新的 RogueMap 实例（原实例已关闭，不可再使用）。
     *
     * <p>使用方式：{@code map = map.compact(newAllocateSize);}
     *
     * @param newAllocateSize 新文件的预分配大小（字节）
     * @return 压缩后的新 RogueMap 实例
     */
    public RogueMap<K, V> compact(long newAllocateSize) {
        if (!(storage instanceof MmapStorage)) {
            throw new UnsupportedOperationException("仅 MMAP 持久化模式支持 compact()");
        }
        MmapAllocator oldAllocator = ((MmapStorage) storage).getAllocator();
        if (oldAllocator.isTemporary()) {
            throw new UnsupportedOperationException("临时文件不支持 compact()");
        }

        String filePath = oldAllocator.getFilePath();
        String tempPath = filePath + ".compact";
        int indexType = getIndexType(index);

        // 1. 创建新文件的 allocator
        MmapAllocator newAllocator = new MmapAllocator(tempPath, newAllocateSize, false);
        try {
            // 2. 创建同类型的新索引
            Index<K> newIndex = createIndexByType(indexType, keyCodec, newAllocator, LowHeapOptions.defaults());

            // 3. 复制所有活跃数据到新文件
            index.forEach((key, address, size) -> {
                long newAddr = newAllocator.allocate(size);
                if (newAddr == 0) {
                    throw new RuntimeException("compact 空间不足：新文件大小 " + newAllocateSize + " 不够容纳所有活跃数据");
                }
                UnsafeOps.copyMemory(address, newAddr, size);
                @SuppressWarnings("unchecked")
                K typedKey = (K) key;
                newIndex.putAndGetOld(typedKey, newAddr, size);
            });

            // 4. 序列化新索引到新文件
            long currentDataOffset = newAllocator.usedMemory();
            int newIndexSize = newIndex.serializedSize();
            long indexAddress = newAllocator.getAddressForOffset(currentDataOffset);
            // 使用文件偏移量序列化（newAllocator 即 AddressTranslator），多段安全
            newIndex.serializeWithOffsets(indexAddress, newAllocator);

            MmapFileHeader header = new MmapFileHeader();
            header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
            header.setVersion(MmapFileHeader.VERSION);
            header.setDataType(MmapFileHeader.DATA_TYPE_MAP);
            header.setIndexType(indexType);
            header.setEntryCount(newIndex.size());
            header.setCurrentOffset(currentDataOffset);
            header.setIndexOffset(currentDataOffset);
            header.setIndexSize(newIndexSize);
            newAllocator.writeHeader(header);

            // 5. 关闭新文件（flush 到磁盘）
            newIndex.close();
            newAllocator.close();
        } catch (Exception e) {
            newAllocator.close();
            new File(tempPath).delete();
            throw new RuntimeException("compact 失败", e);
        }

        // 6. 关闭旧文件
        index.close();
        storage.close();

        // 7. 交换文件
        File oldFile = new File(filePath);
        File newFile = new File(tempPath);
        oldFile.delete();
        if (!newFile.renameTo(oldFile)) {
            throw new RuntimeException("compact 文件重命名失败: " + tempPath + " -> " + filePath);
        }

        // 8. 重新打开压缩后的文件
        return RogueMap.<K, V>mmap()
                .persistent(filePath)
                .allocateSize(newAllocateSize)
                .keyCodec(keyCodec)
                .valueCodec(valueCodec)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <K> Index<K> createIndexByType(int indexType, Codec<K> keyCodec,
            MmapAllocator mmapAllocator, LowHeapOptions lowHeapOptions) {
        if (indexType == 0) {
            return new HashIndex<>(keyCodec, 16);
        } else if (indexType == 1) {
            return new SegmentedHashIndex<>(keyCodec, 64, 16);
        } else if (indexType == 2) {
            return (Index<K>) new LongPrimitiveIndex(16);
        } else if (indexType == 3) {
            return (Index<K>) new IntPrimitiveIndex(16);
        } else if (indexType == 4) {
            if (mmapAllocator == null) {
                throw new IllegalStateException("LowHeapStringIndex 需要 MmapAllocator");
            }
            if (keyCodec != StringCodec.INSTANCE) {
                throw new IllegalStateException("LowHeapStringIndex 仅支持 StringCodec.INSTANCE");
            }
            return (Index<K>) new LowHeapStringIndex(mmapAllocator,
                    lowHeapOptions != null ? lowHeapOptions : LowHeapOptions.defaults(), 16);
        }
        return new SegmentedHashIndex<>(keyCodec, 64, 16);
    }

    /**
     * 获取存储引擎（用于测试）
     */
    public StorageEngine getStorage() {
        return storage;
    }

    /**
     * 获取运维指标
     *
     * @return 存储指标（文件大小、碎片率等）
     */
    public StorageMetrics getMetrics() {
        if (!(storage instanceof MmapStorage)) {
            throw new UnsupportedOperationException("仅 MMAP 模式支持 getMetrics()");
        }
        MmapAllocator mmapAllocator = ((MmapStorage) storage).getAllocator();

        long totalFileSize = mmapAllocator.getFileSize();
        long usedBytes = mmapAllocator.usedMemory();
        long availableBytes = mmapAllocator.availableMemory();
        int entryCount = index.size();

        final long[] liveBytes = {0};
        index.forEach((key, address, size) -> liveBytes[0] += size);

        long dataRegion = usedBytes - MmapFileHeader.HEADER_SIZE;
        long deadBytes = dataRegion > 0 ? dataRegion - liveBytes[0] : 0;
        double fragmentationRatio = dataRegion > 0 ? (double) deadBytes / dataRegion : 0.0;

        long indexHeapBytesEstimate = 0L;
        long indexMmapBytes = 0L;
        double avgKeyBytes = 0.0;
        if (index instanceof LowHeapStringIndex) {
            LowHeapStringIndex lowHeapIndex = (LowHeapStringIndex) index;
            indexHeapBytesEstimate = lowHeapIndex.estimateHeapBytes();
            indexMmapBytes = lowHeapIndex.getIndexMmapBytes();
            avgKeyBytes = lowHeapIndex.getAverageKeyBytes();
        }

        return new StorageMetrics(totalFileSize, usedBytes, availableBytes,
                entryCount, liveBytes[0], deadBytes, 0L, fragmentationRatio,
                mmapAllocator.isTemporary(), mmapAllocator.getFilePath(),
                indexHeapBytesEstimate, indexMmapBytes, avgKeyBytes);
    }

    @Override
    public void close() {
        Throwable primaryException = null;

        // 1. 持久化模式：先保存索引（此时 storage 和 allocator 仍可用）
        try {
            if (storage instanceof MmapStorage) {
                MmapStorage mmapStorage = (MmapStorage) storage;
                MmapAllocator mmapAllocator = mmapStorage.getAllocator();
                if (!mmapAllocator.isTemporary()) {
                    saveMmapIndex();
                }
            }
        } catch (Exception e) {
            primaryException = e;
        }

        // 1.5 停止自动 checkpoint
        try {
            if (autoCheckpointManager != null) {
                autoCheckpointManager.stop();
            }
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        // 2. 关闭 index（不涉及 IO，无资源泄漏风险）
        try {
            index.close();
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        // 3. 关闭 storage（MmapStorage.close() 内部已关闭 allocator，不再单独调用）
        try {
            storage.close();
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        // 注意：不再单独调用 allocator.close()，MmapStorage.close() 已处理

        if (primaryException != null) {
            throw new RuntimeException("关闭 RogueMap 时发生错误", primaryException);
        }
    }

    /**
     * 保存 MMAP 索引到文件
     */
    private void saveMmapIndex() {
        MmapStorage mmapStorage = (MmapStorage) storage;
        MmapAllocator mmapAllocator = mmapStorage.getAllocator();

        // 获取当前数据使用的偏移量（索引写完后，reload 从此处恢复数据）
        long currentDataOffset = allocator.usedMemory();

        // 计算索引大小
        int indexSize = index.serializedSize();

        // 使用 allocate() 分配索引空间：正确处理多段边界（扩容场景），
        // 同时在必要时自动触发 expand（autoExpand=true 时）
        long indexAddress = allocator.allocate(indexSize);
        if (indexAddress == 0) {
            throw new OutOfMemoryError("无法分配 " + indexSize + " 字节保存索引");
        }
        // 计算索引在文件中的逻辑偏移（用于 reload 时定位索引）
        long indexOffset = mmapAllocator.getFileOffsetForAddress(indexAddress);

        // 序列化索引：存储「文件偏移量」而非「相对第0段基址的偏移」。
        // mmapAllocator 实现了 AddressTranslator，可正确处理自动扩容产生的多分段布局，
        // 保证跨会话重新打开时换算回正确的物理地址。
        index.serializeWithOffsets(indexAddress, mmapAllocator);

        // 更新头部
        com.yomahub.roguemap.storage.MmapFileHeader header =
            new com.yomahub.roguemap.storage.MmapFileHeader();
        header.setMagicNumber(com.yomahub.roguemap.storage.MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(com.yomahub.roguemap.storage.MmapFileHeader.VERSION);
        header.setDataType(com.yomahub.roguemap.storage.MmapFileHeader.DATA_TYPE_MAP);
        header.setIndexType(getIndexType(index));
        header.setEntryCount(index.size());
        header.setCurrentOffset(currentDataOffset);
        header.setIndexOffset(indexOffset);
        header.setIndexSize(indexSize);

        mmapAllocator.writeHeader(header);
    }

    /**
     * 获取索引类型
     */
    private int getIndexType(Index<K> index) {
        if (index instanceof HashIndex) {
            return 0;
        } else if (index instanceof SegmentedHashIndex) {
            return 1;
        } else if (index instanceof LongPrimitiveIndex) {
            return 2;
        } else if (index instanceof IntPrimitiveIndex) {
            return 3;
        } else if (index instanceof LowHeapStringIndex) {
            return 4;
        }
        // 未知索引类型，返回默认值
        return 0;
    }

    /**
     * 创建内存映射文件模式的构建器
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return MMAP 构建器
     */
    public static <K, V> MmapBuilder<K, V> mmap() {
        return new MmapBuilder<>();
    }

    /**
     * RogueMap 的抽象构建器基类
     * 包含 MMAP 和 OffHeap 模式的共同配置
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param <B> 具体的构建器类型（用于链式调用）
     */
    @SuppressWarnings("unchecked")
    public abstract static class BaseBuilder<K, V, B extends BaseBuilder<K, V, B>> {
        protected Codec<K> keyCodec;
        protected Codec<V> valueCodec;
        protected boolean useSegmentedIndex = true;
        protected boolean usePrimitiveIndex = false;
        protected boolean useLowHeapIndex = false;
        protected int segmentCount = 64;
        protected int initialCapacity = 16;
        protected LowHeapOptions lowHeapOptions = LowHeapOptions.defaults();

        protected BaseBuilder() {
        }

        /**
         * 设置键编解码器
         *
         * @param keyCodec 键编解码器
         * @return 此构建器
         */
        public B keyCodec(Codec<K> keyCodec) {
            this.keyCodec = keyCodec;
            return (B) this;
        }

        /**
         * 设置值编解码器
         *
         * @param valueCodec 值编解码器
         * @return 此构建器
         */
        public B valueCodec(Codec<V> valueCodec) {
            this.valueCodec = valueCodec;
            return (B) this;
        }

        /**
         * 设置初始容量（用于索引）
         *
         * @param initialCapacity 初始容量
         * @return 此构建器
         */
        public B initialCapacity(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("initialCapacity 必须为正数");
            }
            this.initialCapacity = initialCapacity;
            return (B) this;
        }

        /**
         * 使用基础哈希索引（非分段）
         *
         * @return 此构建器
         */
        public B basicIndex() {
            this.useSegmentedIndex = false;
            this.usePrimitiveIndex = false;
            this.useLowHeapIndex = false;
            return (B) this;
        }

        /**
         * 使用分段哈希索引以提高并发性能
         *
         * @param segmentCount 段数（必须是 2 的幂次方）
         * @return 此构建器
         */
        public B segmentedIndex(int segmentCount) {
            this.useSegmentedIndex = true;
            this.usePrimitiveIndex = false;
            this.useLowHeapIndex = false;
            this.segmentCount = segmentCount;
            return (B) this;
        }

        /**
         * 使用原始类型数组索引（仅支持Long/Integer键）
         * 内存占用比HashMap减少80%以上
         *
         * @return 此构建器
         */
        public B primitiveIndex() {
            this.usePrimitiveIndex = true;
            this.useSegmentedIndex = false;
            this.useLowHeapIndex = false;
            return (B) this;
        }

        /**
         * 使用低堆 String 索引模式（仅支持 StringCodec）
         */
        public B lowHeapIndex() {
            this.useLowHeapIndex = true;
            this.usePrimitiveIndex = false;
            this.useSegmentedIndex = false;
            return (B) this;
        }

        /**
         * 设置低堆模式参数
         */
        public B lowHeapOptions(LowHeapOptions options) {
            if (options == null) {
                throw new IllegalArgumentException("lowHeapOptions 不能为 null");
            }
            this.lowHeapOptions = options;
            return (B) this;
        }

        /**
         * 根据索引类型创建索引（用于恢复）
         *
         * @param indexType 索引类型（0=HashIndex, 1=SegmentedHashIndex, 2=LongPrimitiveIndex, 3=IntPrimitiveIndex, 4=LowHeapStringIndex）
         * @param keyCodec 键编解码器
         * @return 索引实例
         */
        @SuppressWarnings("unchecked")
        protected Index<K> createIndexFromType(int indexType, Codec<K> keyCodec, MmapAllocator mmapAllocator) {
            if (indexType == 0) {
                return new HashIndex<>(keyCodec, initialCapacity);
            } else if (indexType == 1) {
                return new SegmentedHashIndex<>(keyCodec, segmentCount, initialCapacity);
            } else if (indexType == 2) {
                return (Index<K>) new LongPrimitiveIndex(initialCapacity);
            } else if (indexType == 3) {
                return (Index<K>) new IntPrimitiveIndex(initialCapacity);
            } else if (indexType == 4) {
                if (mmapAllocator == null) {
                    throw new IllegalStateException("LowHeapStringIndex 需要 MmapAllocator");
                }
                if (keyCodec != StringCodec.INSTANCE) {
                    throw new IllegalStateException("LowHeapStringIndex 仅支持 StringCodec.INSTANCE");
                }
                return (Index<K>) new LowHeapStringIndex(mmapAllocator, lowHeapOptions, initialCapacity);
            }
            throw new IllegalStateException("未知的索引类型: " + indexType);
        }

        /**
         * 创建新索引
         *
         * @param keyCodec 键编解码器
         * @return 索引实例
         */
        @SuppressWarnings("unchecked")
        protected Index<K> createNewIndex(Codec<K> keyCodec, MmapAllocator mmapAllocator) {
            if (useLowHeapIndex) {
                if (mmapAllocator == null) {
                    throw new IllegalStateException("LowHeapStringIndex 需要 MmapAllocator");
                }
                if (keyCodec != StringCodec.INSTANCE) {
                    throw new IllegalStateException("lowHeapIndex() 仅支持 StringCodec.INSTANCE");
                }
                return (Index<K>) new LowHeapStringIndex(mmapAllocator, lowHeapOptions, initialCapacity);
            } else if (usePrimitiveIndex) {
                // 使用原始类型索引（仅支持Long/Integer键）
                if (keyCodec == PrimitiveCodecs.LONG) {
                    return (Index<K>) new LongPrimitiveIndex(initialCapacity);
                } else if (keyCodec == PrimitiveCodecs.INTEGER) {
                    return (Index<K>) new IntPrimitiveIndex(initialCapacity);
                } else {
                    throw new IllegalStateException(
                            "原始类型索引仅支持 Long 或 Integer 键，请使用 PrimitiveCodecs.LONG 或 PrimitiveCodecs.INTEGER");
                }
            } else if (useSegmentedIndex) {
                return new SegmentedHashIndex<>(keyCodec, segmentCount, initialCapacity);
            } else {
                return new HashIndex<>(keyCodec, initialCapacity);
            }
        }

        /**
         * 构建 RogueMap 实例
         *
         * @return 新的 RogueMap
         */
        public abstract RogueMap<K, V> build();
    }

    /**
     * 内存映射文件模式的构建器
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    public static class MmapBuilder<K, V> extends BaseBuilder<K, V, MmapBuilder<K, V>> {
        private String persistentFilePath;
        private long allocateSize = 256L * 1024 * 1024; // 默认 256MB
        private boolean isTemporary = false;
        private boolean autoExpand = false;
        private double expandFactor = 2.0;
        private long maxFileSize = 0L;
        private long autoCheckpointInterval = -1;  // 毫秒，-1 表示未配置
        private int autoCheckpointOperations = -1; // -1 表示未配置
        private long defaultTTLMillis = 0;  // 默认 TTL（毫秒），0 表示永不过期

        private MmapBuilder() {
        }

        /**
         * 设置持久化文件路径
         *
         * @param filePath 文件路径
         * @return 此构建器
         */
        public MmapBuilder<K, V> persistent(String filePath) {
            if (filePath == null || filePath.isEmpty()) {
                throw new IllegalArgumentException("文件路径不能为空");
            }
            this.persistentFilePath = filePath;
            this.isTemporary = false;
            return this;
        }

        /**
         * 使用临时文件模式
         * 临时文件会在 JVM 关闭后自动删除，适用于临时缓存场景
         *
         * @return 此构建器
         */
        public MmapBuilder<K, V> temporary() {
            this.isTemporary = true;
            this.persistentFilePath = null;
            return this;
        }

        /**
         * 设置预分配文件大小
         *
         * @param size 预分配大小（字节）
         * @return 此构建器
         */
        public MmapBuilder<K, V> allocateSize(long size) {
            if (size <= 0) {
                throw new IllegalArgumentException("分配大小必须为正数");
            }
            this.allocateSize = size;
            return this;
        }

        /**
         * 开启自动扩容（默认关闭）
         *
         * <p>开启后，当文件空间不足时会自动按 expandFactor 倍数扩大文件，无需重新创建实例。
         * 扩容时仅对新增区域创建映射，已有数据地址完全不变，扩容对正在运行的读写操作透明。
         *
         * <p>注意：扩容后 getMetrics().getTotalFileSize() 会随之增大。
         *
         * @param autoExpand true 开启自动扩容
         * @return 此构建器
         */
        public MmapBuilder<K, V> autoExpand(boolean autoExpand) {
            this.autoExpand = autoExpand;
            return this;
        }

        /**
         * 设置每次扩容的倍数（默认 2.0）
         *
         * @param expandFactor 扩容倍数，必须 >= 1.1
         * @return 此构建器
         */
        public MmapBuilder<K, V> expandFactor(double expandFactor) {
            if (expandFactor < 1.1) {
                throw new IllegalArgumentException("expandFactor 必须 >= 1.1");
            }
            this.expandFactor = expandFactor;
            return this;
        }

        /**
         * 设置文件大小上限（字节），0 表示无上限（默认）
         *
         * @param maxFileSize 最大文件字节数
         * @return 此构建器
         */
        public MmapBuilder<K, V> maxFileSize(long maxFileSize) {
            if (maxFileSize < 0) {
                throw new IllegalArgumentException("maxFileSize 不能为负数");
            }
            this.maxFileSize = maxFileSize;
            return this;
        }

        /**
         * 设置自动 checkpoint 的时间间隔
         *
         * <p>启用后，每隔指定时间自动调用 checkpoint()，确保数据持久化。
         * 仅对持久化模式（persistent）有效，临时模式（temporary）忽略此配置。
         *
         * <p>可与 {@link #autoCheckpoint(int)} 同时配置，任一条件满足即触发 checkpoint。
         *
         * @param interval 时间间隔
         * @param unit     时间单位
         * @return 此构建器
         */
        public MmapBuilder<K, V> autoCheckpoint(long interval, TimeUnit unit) {
            if (interval <= 0) {
                throw new IllegalArgumentException("interval 必须为正数");
            }
            if (unit == null) {
                throw new IllegalArgumentException("unit 不能为 null");
            }
            this.autoCheckpointInterval = unit.toMillis(interval);
            return this;
        }

        /**
         * 设置自动 checkpoint 的操作次数阈值
         *
         * <p>启用后，每 N 次写操作（put/remove）自动调用 checkpoint()，确保数据持久化。
         * 仅对持久化模式（persistent）有效，临时模式（temporary）忽略此配置。
         *
         * <p>可与 {@link #autoCheckpoint(long, TimeUnit)} 同时配置，任一条件满足即触发 checkpoint。
         *
         * @param operationCount 操作次数阈值
         * @return 此构建器
         */
        public MmapBuilder<K, V> autoCheckpoint(int operationCount) {
            if (operationCount <= 0) {
                throw new IllegalArgumentException("operationCount 必须为正数");
            }
            this.autoCheckpointOperations = operationCount;
            return this;
        }

        /**
         * 设置默认 TTL（Time-To-Live）
         *
         * <p>设置后，所有未指定 TTL 的 put 操作将使用此默认值。
         * TTL=0 表示永不过期。
         *
         * @param ttl  过期时间
         * @param unit 时间单位
         * @return 此构建器
         */
        public MmapBuilder<K, V> defaultTTL(long ttl, TimeUnit unit) {
            if (ttl < 0) {
                throw new IllegalArgumentException("ttl 不能为负数");
            }
            if (unit == null) {
                throw new IllegalArgumentException("unit 不能为 null");
            }
            this.defaultTTLMillis = unit.toMillis(ttl);
            return this;
        }

        @Override
        public RogueMap<K, V> build() {
            if (keyCodec == null) {
                throw new IllegalStateException("必须设置键编解码器");
            }
            if (valueCodec == null) {
                throw new IllegalStateException("必须设置值编解码器");
            }

            // 临时文件模式不需要指定路径
            if (!isTemporary && (persistentFilePath == null || persistentFilePath.isEmpty())) {
                throw new IllegalStateException("MMAP 模式必须设置文件路径，请使用 persistent(filePath) 或 temporary()");
            }

            // 创建 MmapAllocator（临时模式会自动生成文件路径）
            MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize, isTemporary,
                    autoExpand, expandFactor, maxFileSize);
            Allocator allocator = mmapAllocator;
            StorageEngine storage = new MmapStorage(mmapAllocator);

            Index<K> index;

            // 临时文件模式：总是创建新索引（不恢复）
            if (isTemporary) {
                index = createNewIndex(keyCodec, mmapAllocator);
            } else {
                // 持久化模式：检查是否是已存在的文件
                if (mmapAllocator.isExistingFile()) {
                    // 恢复模式
                    com.yomahub.roguemap.storage.MmapFileHeader header = mmapAllocator.readHeader();

                    long recoveryOffset = header.getCurrentOffset();
                    long indexSnapshotEnd = header.getIndexOffset() + header.getIndexSize();
                    boolean needsLowHeapAllocation = (header.getIndexType() == 4) || useLowHeapIndex;

                    // 低堆索引在构造时会分配槽位内存，先把 offset 放到索引快照之后，避免覆盖快照。
                    if (needsLowHeapAllocation) {
                        mmapAllocator.restoreOffset(Math.max(recoveryOffset, indexSnapshotEnd));
                    } else {
                        mmapAllocator.restoreOffset(recoveryOffset);
                    }

                    // 创建索引并恢复数据
                    if (useLowHeapIndex && header.getIndexType() != 4) {
                        throw new IllegalStateException(
                                "lowHeapIndex() 不兼容旧索引类型（indexType=" + header.getIndexType() +
                                "）。请新建文件或先导出后重建。");
                    }
                    index = createIndexFromType(header.getIndexType(), keyCodec, mmapAllocator);
                    if (header.getIndexSize() > 0) {
                        long indexAddress = mmapAllocator.getAddressForOffset(header.getIndexOffset());
                        // 反序列化：以文件偏移量恢复（mmapAllocator 即 AddressTranslator），多段安全
                        index.deserializeWithOffsets(indexAddress, (int) header.getIndexSize(), mmapAllocator);
                    }

                    // 非低堆索引恢复后，继续沿用 header.currentOffset 作为数据写入起点。
                    if (!needsLowHeapAllocation) {
                        mmapAllocator.restoreOffset(recoveryOffset);
                    }

                    // 标记文件为"已打开"（崩溃检测）
                    mmapAllocator.markOpen();
                } else {
                    // 新文件模式
                    index = createNewIndex(keyCodec, mmapAllocator);
                }
            }

            // 仅持久化模式启用自动 checkpoint
            long checkpointInterval = isTemporary ? -1 : autoCheckpointInterval;
            int checkpointOperations = isTemporary ? -1 : autoCheckpointOperations;

            return new RogueMap<>(index, storage, keyCodec, valueCodec, allocator, checkpointInterval, checkpointOperations, defaultTTLMillis);
        }
    }
}
