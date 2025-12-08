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

    @Override
    public void close() {
        index.close();
        storage.close();
        allocator.close();
    }

    /**
     * 为 RogueMap 创建一个新的构建器
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 新的构建器
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * RogueMap 的构建器
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    public static class Builder<K, V> {
        private long maxMemory = 1024L * 1024 * 1024; // 默认 1GB
        private Codec<K> keyCodec;
        private Codec<V> valueCodec;
        private boolean useSegmentedIndex = true;
        private boolean usePrimitiveIndex = false;
        private int segmentCount = 64;
        private int initialCapacity = 16;

        // MMAP 相关配置
        private boolean useMmap = false;
        private String persistentFilePath = null;
        private long allocateSize = 10L * 1024 * 1024 * 1024; // 默认 10GB

        private Builder() {
        }

        /**
         * 设置最大内存大小
         *
         * @param maxMemory 最大内存（字节）
         * @return 此构建器
         */
        public Builder<K, V> maxMemory(long maxMemory) {
            if (maxMemory <= 0) {
                throw new IllegalArgumentException("maxMemory 必须为正数");
            }
            this.maxMemory = maxMemory;
            return this;
        }

        /**
         * 设置键编解码器
         *
         * @param keyCodec 键编解码器
         * @return 此构建器
         */
        public Builder<K, V> keyCodec(Codec<K> keyCodec) {
            this.keyCodec = keyCodec;
            return this;
        }

        /**
         * 设置值编解码器
         *
         * @param valueCodec 值编解码器
         * @return 此构建器
         */
        public Builder<K, V> valueCodec(Codec<V> valueCodec) {
            this.valueCodec = valueCodec;
            return this;
        }

        /**
         * 设置初始容量（用于原始类型索引）
         *
         * @param initialCapacity 初始容量
         * @return 此构建器
         */
        public Builder<K, V> initialCapacity(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("initialCapacity 必须为正数");
            }
            this.initialCapacity = initialCapacity;
            return this;
        }

        /**
         * 使用基础哈希索引（非分段）
         *
         * @return 此构建器
         */
        public Builder<K, V> basicIndex() {
            this.useSegmentedIndex = false;
            this.usePrimitiveIndex = false;
            return this;
        }

        /**
         * 使用分段哈希索引以提高并发性能
         *
         * @param segmentCount 段数（必须是 2 的幂次方）
         * @return 此构建器
         */
        public Builder<K, V> segmentedIndex(int segmentCount) {
            this.useSegmentedIndex = true;
            this.usePrimitiveIndex = false;
            this.segmentCount = segmentCount;
            return this;
        }

        /**
         * 使用原始类型数组索引（仅支持Long/Integer键）
         * 内存占用比HashMap减少80%以上
         *
         * @return 此构建器
         */
        public Builder<K, V> primitiveIndex() {
            this.usePrimitiveIndex = true;
            this.useSegmentedIndex = false;
            return this;
        }

        /**
         * 启用堆外存储模式
         *
         * @return 此构建器
         */
        public Builder<K, V> offHeap() {
            this.useMmap = false;
            return this;
        }

        /**
         * 启用内存映射文件模式
         *
         * @return 此构建器
         */
        public Builder<K, V> mmap() {
            this.useMmap = true;
            return this;
        }

        /**
         * 设置持久化文件路径（自动启用 MMAP 模式）
         *
         * @param filePath 文件路径
         * @return 此构建器
         */
        public Builder<K, V> persistent(String filePath) {
            if (filePath == null || filePath.isEmpty()) {
                throw new IllegalArgumentException("文件路径不能为空");
            }
            this.persistentFilePath = filePath;
            this.useMmap = true;
            return this;
        }

        /**
         * 设置预分配文件大小（仅用于 MMAP 模式）
         *
         * @param size 预分配大小（字节）
         * @return 此构建器
         */
        public Builder<K, V> allocateSize(long size) {
            if (size <= 0) {
                throw new IllegalArgumentException("分配大小必须为正数");
            }
            this.allocateSize = size;
            return this;
        }

        /**
         * 构建 RogueMap 实例
         *
         * @return 新的 RogueMap
         */
        @SuppressWarnings("unchecked")
        public RogueMap<K, V> build() {
            if (keyCodec == null) {
                throw new IllegalStateException("必须设置键编解码器");
            }
            if (valueCodec == null) {
                throw new IllegalStateException("必须设置值编解码器");
            }

            // 创建分配器和存储引擎
            Allocator allocator;
            StorageEngine storage;

            if (useMmap) {
                // MMAP 模式
                if (persistentFilePath == null || persistentFilePath.isEmpty()) {
                    throw new IllegalStateException("MMAP 模式必须设置文件路径，请使用 persistent(filePath)");
                }
                MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize);
                allocator = mmapAllocator;
                storage = new MmapStorage(mmapAllocator);
            } else {
                // 堆外内存模式
                allocator = new SlabAllocator(maxMemory);
                storage = new OffHeapStorage(allocator);
            }

            // 创建索引
            Index<K> index;
            if (usePrimitiveIndex) {
                // 使用原始类型索引（仅支持Long/Integer键）
                if (keyCodec == PrimitiveCodecs.LONG) {
                    index = (Index<K>) new LongPrimitiveIndex(initialCapacity);
                } else if (keyCodec == PrimitiveCodecs.INTEGER) {
                    index = (Index<K>) new IntPrimitiveIndex(initialCapacity);
                } else {
                    throw new IllegalStateException(
                            "原始类型索引仅支持 Long 或 Integer 键，请使用 PrimitiveCodecs.LONG 或 PrimitiveCodecs.INTEGER");
                }
            } else if (useSegmentedIndex) {
                index = new SegmentedHashIndex<>(segmentCount, 16);
            } else {
                index = new HashIndex<>();
            }

            return new RogueMap<>(index, storage, keyCodec, valueCodec, allocator);
        }
    }
}
