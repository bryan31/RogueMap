package com.yomahub.roguemap;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.set.SetAddResult;
import com.yomahub.roguemap.set.SetIndex;
import com.yomahub.roguemap.set.SetIterator;
import com.yomahub.roguemap.set.SetRemoveResult;
import com.yomahub.roguemap.storage.MmapFileHeader;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.StorageEngine;

import java.util.Iterator;

/**
 * RogueSet - 基于内存映射文件的高性能集合
 *
 * <p>特点：
 * <ul>
 *   <li>基于内存映射文件，支持大数据量存储</li>
 *   <li>支持持久化（数据在JVM重启后保留）</li>
 *   <li>64段分段锁设计，高并发支持</li>
 *   <li>使用StampedLock乐观读，读取性能优异</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 临时文件模式
 * RogueSet<String> set = RogueSet.<String>mmap()
 *     .temporary()
 *     .elementCodec(new StringCodec())
 *     .build();
 *
 * // 持久化模式
 * RogueSet<Long> set = RogueSet.<Long>mmap()
 *     .persistent("myset.db")
 *     .elementCodec(PrimitiveCodecs.LONG)
 *     .build();
 * }</pre>
 *
 * @param <E> 元素类型
 */
public class RogueSet<E> implements Iterable<E>, AutoCloseable {

    private final SetIndex<E> index;
    private final StorageEngine storage;
    private final Codec<E> elementCodec;
    private final Allocator allocator;

    private RogueSet(SetIndex<E> index, StorageEngine storage,
                     Codec<E> elementCodec, Allocator allocator) {
        this.index = index;
        this.storage = storage;
        this.elementCodec = elementCodec;
        this.allocator = allocator;
    }

    /**
     * 添加元素到集合
     *
     * @param element 要添加的元素
     * @return 如果元素不存在且成功添加返回true，如果元素已存在返回false
     */
    public boolean add(E element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }

        // 计算所需大小
        int elementSize = elementCodec.calculateSize(element);
        if (elementSize < 0) {
            throw new IllegalStateException("无法确定元素的大小");
        }

        // 分配内存
        long newAddress = allocator.allocate(elementSize);
        if (newAddress == 0) {
            throw new OutOfMemoryError("分配 " + elementSize + " 字节失败");
        }

        try {
            // 编码元素到内存
            int actualSize = elementCodec.encode(newAddress, element);

            // 原子性地更新索引
            SetAddResult result = index.add(element, newAddress, actualSize);

            // 如果元素已存在，释放新分配的内存
            if (result.wasPresent()) {
                allocator.free(newAddress, actualSize);
                return false;
            }

            return true;
        } catch (Exception e) {
            // 异常时释放内存
            allocator.free(newAddress, elementSize);
            throw e;
        }
    }

    /**
     * 检查元素是否存在于集合中
     *
     * @param element 要检查的元素
     * @return 如果存在返回true，否则返回false
     */
    public boolean contains(E element) {
        return element != null && index.contains(element);
    }

    /**
     * 从集合中移除元素
     *
     * @param element 要移除的元素
     * @return 如果元素存在并被移除返回true，否则返回false
     */
    public boolean remove(E element) {
        if (element == null) {
            return false;
        }

        SetRemoveResult result = index.remove(element);
        if (!result.wasPresent()) {
            return false;
        }

        // 释放内存
        allocator.free(result.getAddress(), result.getSize());
        return true;
    }

    /**
     * 获取集合中的元素数量
     *
     * @return 元素数量
     */
    public int size() {
        return index.size();
    }

    /**
     * 检查集合是否为空
     *
     * @return 如果为空返回true
     */
    public boolean isEmpty() {
        return index.isEmpty();
    }

    /**
     * 清空集合中的所有元素
     */
    public void clear() {
        // 遍历并释放内存
        index.forEach((element, address, size) -> {
            allocator.free(address, size);
        });

        index.clear();
    }

    /**
     * 返回元素的迭代器
     *
     * @return 元素迭代器
     */
    @Override
    public Iterator<E> iterator() {
        return new SetIterator<>(index, elementCodec);
    }

    /**
     * 刷新所有待处理的更改（用于持久化存储）
     */
    public void flush() {
        storage.flush();
    }

    @Override
    public void close() {
        // 如果是 MMAP 模式，检查是否需要保存索引
        if (storage instanceof MmapStorage) {
            MmapStorage mmapStorage = (MmapStorage) storage;
            MmapAllocator mmapAllocator = mmapStorage.getAllocator();

            // 临时文件模式：跳过持久化
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

        // 索引数据放在所有数据之后
        long indexOffset = currentDataOffset;
        long baseAddress = mmapAllocator.getBaseAddress();
        long indexAddress = baseAddress + indexOffset;

        // 序列化索引（使用相对偏移量）
        index.serializeWithOffsets(indexAddress, baseAddress);

        // 更新头部
        MmapFileHeader header = new MmapFileHeader();
        header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(MmapFileHeader.VERSION);
        header.setDataType(MmapFileHeader.DATA_TYPE_SET);
        header.setIndexType(0); // Set使用默认类型
        header.setEntryCount(index.size());
        header.setCurrentOffset(currentDataOffset);
        header.setIndexOffset(indexOffset);
        header.setIndexSize(indexSize);

        mmapAllocator.writeHeader(header);
    }

    /**
     * 创建内存映射文件模式的构建器
     *
     * @param <E> 元素类型
     * @return MMAP 构建器
     */
    public static <E> MmapBuilder<E> mmap() {
        return new MmapBuilder<>();
    }

    /**
     * 内存映射文件模式的构建器
     *
     * @param <E> 元素类型
     */
    public static class MmapBuilder<E> {
        private Codec<E> elementCodec;
        private String persistentFilePath;
        private long allocateSize = 256L * 1024 * 1024; // 默认 256MB
        private boolean isTemporary = false;
        private int segmentCount = 64;
        private int initialCapacity = 16;

        private MmapBuilder() {
        }

        /**
         * 设置元素编解码器
         *
         * @param elementCodec 元素编解码器
         * @return 此构建器
         */
        public MmapBuilder<E> elementCodec(Codec<E> elementCodec) {
            this.elementCodec = elementCodec;
            return this;
        }

        /**
         * 设置持久化文件路径
         *
         * @param filePath 文件路径
         * @return 此构建器
         */
        public MmapBuilder<E> persistent(String filePath) {
            if (filePath == null || filePath.isEmpty()) {
                throw new IllegalArgumentException("文件路径不能为空");
            }
            this.persistentFilePath = filePath;
            this.isTemporary = false;
            return this;
        }

        /**
         * 使用临时文件模式
         * 临时文件会在 JVM 关闭后自动删除
         *
         * @return 此构建器
         */
        public MmapBuilder<E> temporary() {
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
        public MmapBuilder<E> allocateSize(long size) {
            if (size <= 0) {
                throw new IllegalArgumentException("分配大小必须为正数");
            }
            this.allocateSize = size;
            return this;
        }

        /**
         * 设置分段数（必须是2的幂次方）
         *
         * @param segmentCount 分段数
         * @return 此构建器
         */
        public MmapBuilder<E> segmentCount(int segmentCount) {
            if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
                throw new IllegalArgumentException("分段数必须是 2 的幂次方");
            }
            this.segmentCount = segmentCount;
            return this;
        }

        /**
         * 设置初始容量
         *
         * @param initialCapacity 初始容量
         * @return 此构建器
         */
        public MmapBuilder<E> initialCapacity(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("初始容量必须为正数");
            }
            this.initialCapacity = initialCapacity;
            return this;
        }

        /**
         * 构建 RogueSet 实例
         *
         * @return 新的 RogueSet
         */
        public RogueSet<E> build() {
            if (elementCodec == null) {
                throw new IllegalStateException("必须设置元素编解码器");
            }

            // 临时文件模式不需要指定路径
            if (!isTemporary && (persistentFilePath == null || persistentFilePath.isEmpty())) {
                throw new IllegalStateException("MMAP 模式必须设置文件路径，请使用 persistent(filePath) 或 temporary()");
            }

            // 创建 MmapAllocator
            MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize, isTemporary);
            Allocator allocator = mmapAllocator;
            StorageEngine storage = new MmapStorage(mmapAllocator);

            SetIndex<E> index;

            // 临时文件模式：总是创建新索引
            if (isTemporary) {
                index = new SetIndex<>(elementCodec, segmentCount, initialCapacity);
            } else {
                // 持久化模式：检查是否是已存在的文件
                if (mmapAllocator.isExistingFile()) {
                    // 恢复模式
                    MmapFileHeader header = mmapAllocator.readHeader();

                    // 验证数据类型
                    if (header.getDataType() != MmapFileHeader.DATA_TYPE_SET) {
                        throw new IllegalStateException("文件类型不匹配：期望 SET，实际 " + header.getDataType());
                    }

                    // 恢复 allocator 的 offset
                    mmapAllocator.restoreOffset(header.getCurrentOffset());

                    // 创建索引并恢复数据
                    index = new SetIndex<>(elementCodec, segmentCount, initialCapacity);

                    if (header.getIndexSize() > 0) {
                        long baseAddress = mmapAllocator.getBaseAddress();
                        long indexAddress = baseAddress + header.getIndexOffset();
                        index.deserializeWithOffsets(indexAddress, (int) header.getIndexSize(), baseAddress);
                    }
                } else {
                    // 新文件模式
                    index = new SetIndex<>(elementCodec, segmentCount, initialCapacity);
                }
            }

            return new RogueSet<>(index, storage, elementCodec, allocator);
        }
    }
}
