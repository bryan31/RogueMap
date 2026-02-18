package com.yomahub.roguemap;

import com.yomahub.roguemap.list.ListIndex;
import com.yomahub.roguemap.list.RogueListIterator;
import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.StorageEngine;

import java.util.Iterator;

/**
 * RogueList - 基于内存映射文件的高性能双向链表
 *
 * <p>特点：
 * <ul>
 *   <li>基于内存映射文件，支持大数据量存储</li>
 *   <li>支持持久化（数据在JVM重启后保留）</li>
 *   <li>双向链表实现，支持头部/尾部操作</li>
 *   <li>维护位置索引数组，实现O(1)随机访问</li>
 *   <li>线程安全，支持并发读写</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 临时文件模式
 * RogueList<String> list = RogueList.<String>mmap()
 *     .temporary()
 *     .elementCodec(new StringCodec())
 *     .build();
 *
 * // 持久化模式
 * RogueList<Long> list = RogueList.<Long>mmap()
 *     .persistent("mylist.db")
 *     .elementCodec(PrimitiveCodecs.LONG)
 *     .build();
 * }</pre>
 *
 * @param <E> 元素类型
 */
public class RogueList<E> implements Iterable<E>, AutoCloseable {

    private final ListIndex index;
    private final StorageEngine storage;
    private final Codec<E> elementCodec;
    private final Allocator allocator;
    private final long baseAddress;

    private RogueList(ListIndex index, StorageEngine storage,
                      Codec<E> elementCodec, Allocator allocator, long baseAddress) {
        this.index = index;
        this.storage = storage;
        this.elementCodec = elementCodec;
        this.allocator = allocator;
        this.baseAddress = baseAddress;
    }

    /**
     * 在列表头部插入元素
     *
     * <p><b>时间复杂度: O(n)</b> — 头部插入需要将位置索引数组中所有元素后移一位。
     * 大列表场景建议优先使用 {@link #addLast(Object)}（O(1)）。
     *
     * @param element 要插入的元素
     */
    public void addFirst(E element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }

        // 计算所需大小
        int elementSize = elementCodec.calculateSize(element);
        int totalSize = ListIndex.NODE_HEADER_SIZE + elementSize;

        // 分配内存
        long nodeAddress = allocator.allocate(totalSize);
        if (nodeAddress == 0) {
            throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
        }

        try {
            // 初始化节点
            ListIndex.setNodePrev(nodeAddress, 0);
            ListIndex.setNodeNext(nodeAddress, 0);
            ListIndex.setElementSize(nodeAddress, elementSize);

            // 写入元素数据
            long elementAddr = ListIndex.getElementAddress(nodeAddress);
            elementCodec.encode(elementAddr, element);

            // 更新索引
            index.addToHead(nodeAddress);
        } catch (Exception e) {
            allocator.free(nodeAddress, totalSize);
            throw e;
        }
    }

    /**
     * 在列表尾部插入元素
     *
     * @param element 要插入的元素
     */
    public void addLast(E element) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }

        // 计算所需大小
        int elementSize = elementCodec.calculateSize(element);
        int totalSize = ListIndex.NODE_HEADER_SIZE + elementSize;

        // 分配内存
        long nodeAddress = allocator.allocate(totalSize);
        if (nodeAddress == 0) {
            throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
        }

        try {
            // 初始化节点
            ListIndex.setNodePrev(nodeAddress, 0);
            ListIndex.setNodeNext(nodeAddress, 0);
            ListIndex.setElementSize(nodeAddress, elementSize);

            // 写入元素数据
            long elementAddr = ListIndex.getElementAddress(nodeAddress);
            elementCodec.encode(elementAddr, element);

            // 更新索引
            index.addToTail(nodeAddress);
        } catch (Exception e) {
            allocator.free(nodeAddress, totalSize);
            throw e;
        }
    }

    /**
     * 移除并返回列表头部元素
     *
     * <p><b>时间复杂度: O(n)</b> — 头部移除需要将位置索引数组中所有元素前移一位。
     * 大列表场景建议优先使用 {@link #removeLast()}（O(1)）。
     *
     * @return 头部元素，如果列表为空返回null
     */
    public E removeFirst() {
        long nodeOffset = index.removeHead();
        if (nodeOffset == 0) {
            return null;
        }

        try {
            // 读取元素
            long elementAddr = ListIndex.getElementAddress(nodeOffset);
            return elementCodec.decode(elementAddr);
        } finally {
            // 释放内存
            int elementSize = ListIndex.getElementSize(nodeOffset);
            allocator.free(nodeOffset, ListIndex.NODE_HEADER_SIZE + elementSize);
        }
    }

    /**
     * 移除并返回列表尾部元素
     *
     * @return 尾部元素，如果列表为空返回null
     */
    public E removeLast() {
        long nodeOffset = index.removeTail();
        if (nodeOffset == 0) {
            return null;
        }

        try {
            // 读取元素
            long elementAddr = ListIndex.getElementAddress(nodeOffset);
            return elementCodec.decode(elementAddr);
        } finally {
            // 释放内存
            int elementSize = ListIndex.getElementSize(nodeOffset);
            allocator.free(nodeOffset, ListIndex.NODE_HEADER_SIZE + elementSize);
        }
    }

    /**
     * 获取头部元素（不移除）
     *
     * @return 头部元素，如果列表为空返回null
     */
    public E getFirst() {
        long headOffset = index.getHeadOffset();
        if (headOffset == 0) {
            return null;
        }

        long elementAddr = ListIndex.getElementAddress(headOffset);
        return elementCodec.decode(elementAddr);
    }

    /**
     * 获取尾部元素（不移除）
     *
     * @return 尾部元素，如果列表为空返回null
     */
    public E getLast() {
        long tailOffset = index.getTailOffset();
        if (tailOffset == 0) {
            return null;
        }

        long elementAddr = ListIndex.getElementAddress(tailOffset);
        return elementCodec.decode(elementAddr);
    }

    /**
     * 获取指定索引位置的元素（O(1)时间复杂度）
     *
     * @param index 索引位置
     * @return 元素
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    public E get(int index) {
        long nodeOffset = this.index.get(index);
        if (nodeOffset == 0) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }

        long elementAddr = ListIndex.getElementAddress(nodeOffset);
        return elementCodec.decode(elementAddr);
    }

    /**
     * 获取列表中的元素数量
     *
     * @return 元素数量
     */
    public int size() {
        return index.size();
    }

    /**
     * 检查列表是否为空
     *
     * @return 如果为空返回true
     */
    public boolean isEmpty() {
        return index.isEmpty();
    }

    /**
     * 清空列表中的所有元素
     */
    public void clear() {
        // 遍历并释放内存
        int size = index.size();
        for (int i = 0; i < size; i++) {
            long nodeOffset = index.get(i);
            if (nodeOffset != 0) {
                int elementSize = ListIndex.getElementSize(nodeOffset);
                allocator.free(nodeOffset, ListIndex.NODE_HEADER_SIZE + elementSize);
            }
        }

        index.clear();
    }

    /**
     * 返回元素的迭代器
     *
     * @return 元素迭代器
     */
    @Override
    public Iterator<E> iterator() {
        return new RogueListIterator<>(index, elementCodec, baseAddress);
    }

    /**
     * 返回列表迭代器（支持双向遍历）
     *
     * @return 列表迭代器
     */
    public java.util.ListIterator<E> listIterator() {
        return new RogueListIterator<>(index, elementCodec, baseAddress);
    }

    /**
     * 返回从指定位置开始的列表迭代器
     *
     * @param index 起始位置
     * @return 列表迭代器
     */
    public java.util.ListIterator<E> listIterator(int index) {
        return new RogueListIterator<>(this.index, elementCodec, baseAddress, index);
    }

    /**
     * 刷新所有待处理的更改（用于持久化存储）
     */
    public void flush() {
        storage.flush();
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

        // 2. 清空 index（不涉及 IO，无资源泄漏风险）
        try {
            index.clear();
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        // 3. 关闭 storage（MmapStorage.close() 内部已关闭 allocator，不再单独调用）
        try {
            storage.close();
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        if (primaryException != null) {
            throw new RuntimeException("关闭 RogueList 时发生错误", primaryException);
        }
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
        long indexAddress = baseAddress + indexOffset;

        // 序列化索引（使用相对偏移量）
        index.serialize(indexAddress, baseAddress);

        // 更新头部
        MmapFileHeader header = new MmapFileHeader();
        header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(MmapFileHeader.VERSION);
        header.setDataType(MmapFileHeader.DATA_TYPE_LIST);
        header.setIndexType(0);
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
        private int initialCapacity = 1024;

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
         * 构建 RogueList 实例
         *
         * @return 新的 RogueList
         */
        public RogueList<E> build() {
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
            long baseAddress = mmapAllocator.getBaseAddress();

            ListIndex index = new ListIndex();

            // 持久化模式：检查是否是已存在的文件
            if (!isTemporary && mmapAllocator.isExistingFile()) {
                // 恢复模式
                MmapFileHeader header = mmapAllocator.readHeader();

                // 验证数据类型
                if (header.getDataType() != MmapFileHeader.DATA_TYPE_LIST) {
                    throw new IllegalStateException("文件类型不匹配：期望 LIST，实际 " + header.getDataType());
                }

                // 恢复 allocator 的 offset
                mmapAllocator.restoreOffset(header.getCurrentOffset());

                // 恢复索引
                if (header.getIndexSize() > 0) {
                    long indexAddress = baseAddress + header.getIndexOffset();
                    index.deserialize(indexAddress, (int) header.getIndexSize(), baseAddress);
                }
            }

            return new RogueList<>(index, storage, elementCodec, allocator, baseAddress);
        }
    }
}
