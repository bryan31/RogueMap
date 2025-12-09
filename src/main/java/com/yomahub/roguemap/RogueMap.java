package com.yomahub.roguemap;

import com.yomahub.roguemap.index.HashIndex;
import com.yomahub.roguemap.index.Index;
import com.yomahub.roguemap.index.IntPrimitiveIndex;
import com.yomahub.roguemap.index.LongPrimitiveIndex;
import com.yomahub.roguemap.index.SegmentedHashIndex;
import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.SlabAllocator;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.OffHeapStorage;
import com.yomahub.roguemap.storage.StorageEngine;

/**
 * RogueMap - 高性能堆外键值存储
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class RogueMap<K, V> implements AutoCloseable {

    private final Index<K> index;
    private final StorageEngine storage;
    private final Codec<K> keyCodec;
    private final Codec<V> valueCodec;
    private final Allocator allocator;

    private RogueMap(Index<K> index, StorageEngine storage,
            Codec<K> keyCodec, Codec<V> valueCodec,
            Allocator allocator) {
        this.index = index;
        this.storage = storage;
        this.keyCodec = keyCodec;
        this.valueCodec = valueCodec;
        this.allocator = allocator;
    }

    /**
     * 将键值对放入 map
     *
     * @param key   键
     * @param value 值
     * @return 之前的值，如果没有则返回 null
     */
    public V put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("键不能为 null");
        }

        // 计算所需大小
        int valueSize = valueCodec.calculateSize(value);
        if (valueSize < 0) {
            throw new IllegalStateException("无法确定值的大小");
        }

        // 为值分配内存
        long address = allocator.allocate(valueSize);
        if (address == 0) {
            throw new OutOfMemoryError("分配 " + valueSize + " 字节失败");
        }

        try {
            // 将值编码到内存
            int actualSize = valueCodec.encode(address, value);

            // 如果存在旧值则获取
            V oldValue = null;
            long oldAddress = index.get(key);
            if (oldAddress != 0) {
                int oldSize = index.getSize(key);
                oldValue = valueCodec.decode(oldAddress);
                // 释放旧内存
                allocator.free(oldAddress, oldSize);
            }

            // 更新索引
            index.put(key, address, actualSize);

            return oldValue;
        } catch (Exception e) {
            // 出错时释放已分配的内存
            allocator.free(address, valueSize);
            throw e;
        }
    }

    /**
     * 根据键获取值
     *
     * @param key 键
     * @return 值，如果未找到则返回 null
     */
    public V get(K key) {
        if (key == null) {
            return null;
        }

        long address = index.get(key);
        if (address == 0) {
            return null;
        }

        return valueCodec.decode(address);
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

        long address = index.remove(key);
        if (address == 0) {
            return null;
        }

        int size = index.getSize(key);
        V oldValue = valueCodec.decode(address);

        // 释放内存
        allocator.free(address, size);

        return oldValue;
    }

    /**
     * 检查键是否存在
     *
     * @param key 键
     * @return 如果存在返回 true，否则返回 false
     */
    public boolean containsKey(K key) {
        return key != null && index.containsKey(key);
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
     * 移除所有条目
     */
    public void clear() {
        // TODO: 清除前释放索引中的所有内存地址
        index.clear();
    }

    /**
     * 刷新所有待处理的更改（用于持久化存储）
     */
    public void flush() {
        storage.flush();
    }

    /**
     * 获取存储引擎（用于测试）
     */
    public StorageEngine getStorage() {
        return storage;
    }

    @Override
    public void close() {
        // 如果是 MMAP 模式，检查是否需要保存索引
        if (storage instanceof MmapStorage) {
            MmapStorage mmapStorage = (MmapStorage) storage;
            MmapAllocator mmapAllocator = mmapStorage.getAllocator();

            // 临时文件模式：跳过持久化
            // 持久化模式：保存索引
            if (!mmapAllocator.isTemporary()) {
                saveMmapIndex();
            }
        }

        index.close();
        storage.close();
        allocator.close();
    }

    /**
     * 保存 MMAP 索引到文件
     */
    private void saveMmapIndex() {
        MmapStorage mmapStorage = (MmapStorage) storage;
        MmapAllocator mmapAllocator = mmapStorage.getAllocator();

        // 获取当前数据使用的偏移量
        long currentDataOffset = allocator.usedMemory();

        // 计算索引大小
        int indexSize = index.serializedSize();

        // 索引数据放在所有数据之后（避免覆盖数据）
        long indexOffset = currentDataOffset;
        long baseAddress = mmapAllocator.getBaseAddress();
        long indexAddress = baseAddress + indexOffset;

        // 序列化索引（使用相对偏移量）
        index.serializeWithOffsets(indexAddress, baseAddress);

        // 更新头部
        com.yomahub.roguemap.storage.MmapFileHeader header =
            new com.yomahub.roguemap.storage.MmapFileHeader();
        header.setMagicNumber(com.yomahub.roguemap.storage.MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(com.yomahub.roguemap.storage.MmapFileHeader.VERSION);
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
        }
        // 未知索引类型，返回默认值
        return 0;
    }

    /**
     * 创建堆外内存模式的构建器
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return OffHeap 构建器
     */
    public static <K, V> OffHeapBuilder<K, V> offHeap() {
        return new OffHeapBuilder<>();
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
        protected int segmentCount = 64;
        protected int initialCapacity = 16;

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
            return (B) this;
        }

        /**
         * 根据索引类型创建索引（用于恢复）
         *
         * @param indexType 索引类型（0=HashIndex, 1=SegmentedHashIndex, 2=LongPrimitiveIndex, 3=IntPrimitiveIndex）
         * @param keyCodec 键编解码器
         * @return 索引实例
         */
        @SuppressWarnings("unchecked")
        protected Index<K> createIndexFromType(int indexType, Codec<K> keyCodec) {
            if (indexType == 0) {
                return new HashIndex<>(keyCodec, initialCapacity);
            } else if (indexType == 1) {
                return new SegmentedHashIndex<>(keyCodec, segmentCount, initialCapacity);
            } else if (indexType == 2) {
                return (Index<K>) new LongPrimitiveIndex(initialCapacity);
            } else if (indexType == 3) {
                return (Index<K>) new IntPrimitiveIndex(initialCapacity);
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
        protected Index<K> createNewIndex(Codec<K> keyCodec) {
            if (usePrimitiveIndex) {
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
        private long allocateSize = 10L * 1024 * 1024 * 1024; // 默认 10GB
        private boolean isTemporary = false;

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
            MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize, isTemporary);
            Allocator allocator = mmapAllocator;
            StorageEngine storage = new MmapStorage(mmapAllocator);

            Index<K> index;

            // 临时文件模式：总是创建新索引（不恢复）
            if (isTemporary) {
                index = createNewIndex(keyCodec);
            } else {
                // 持久化模式：检查是否是已存在的文件
                if (mmapAllocator.isExistingFile()) {
                    // 恢复模式
                    com.yomahub.roguemap.storage.MmapFileHeader header = mmapAllocator.readHeader();

                    // 恢复 allocator 的 offset
                    mmapAllocator.restoreOffset(header.getCurrentOffset());

                    // 创建索引并恢复数据
                    index = createIndexFromType(header.getIndexType(), keyCodec);

                    if (header.getIndexSize() > 0) {
                        long baseAddress = mmapAllocator.getBaseAddress();
                        long indexAddress = baseAddress + header.getIndexOffset();
                        index.deserializeWithOffsets(indexAddress, (int) header.getIndexSize(), baseAddress);
                    }
                } else {
                    // 新文件模式
                    index = createNewIndex(keyCodec);
                }
            }

            return new RogueMap<>(index, storage, keyCodec, valueCodec, allocator);
        }
    }

    /**
     * 堆外内存模式的构建器
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    public static class OffHeapBuilder<K, V> extends BaseBuilder<K, V, OffHeapBuilder<K, V>> {
        private long maxMemory = 1024L * 1024 * 1024; // 默认 1GB

        private OffHeapBuilder() {
        }

        /**
         * 设置最大内存大小
         *
         * @param maxMemory 最大内存（字节）
         * @return 此构建器
         */
        public OffHeapBuilder<K, V> maxMemory(long maxMemory) {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("maxMemory 必须为正数");
            }
            this.maxMemory = maxMemory;
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

            // 堆外内存模式
            Allocator allocator = new SlabAllocator(maxMemory);
            StorageEngine storage = new OffHeapStorage(allocator);
            Index<K> index = createNewIndex(keyCodec);

            return new RogueMap<>(index, storage, keyCodec, valueCodec, allocator);
        }
    }
}
