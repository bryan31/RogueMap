package com.yomahub.roguemap;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.queue.CircularQueueStorage;
import com.yomahub.roguemap.queue.LinkedQueueStorage;
import com.yomahub.roguemap.queue.QueueStorage;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.StorageEngine;

/**
 * RogueQueue - 基于内存映射文件的高性能队列
 *
 * <p>特点：
 * <ul>
 *   <li>支持两种模式：链表模式（无界）和环形缓冲区模式（有界）</li>
 *   <li>基于内存映射文件，支持大数据量存储</li>
 *   <li>支持持久化（数据在JVM重启后保留）</li>
 *   <li>线程安全，支持并发读写</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 链表模式（无界）
 * RogueQueue<String> queue = RogueQueue.<String>mmap()
 *     .temporary()
 *     .linked()
 *     .elementCodec(new StringCodec())
 *     .build();
 *
 * // 环形缓冲区模式（有界）
 * RogueQueue<Long> queue = RogueQueue.<Long>mmap()
 *     .persistent("myqueue.db")
 *     .circular(1024, 64)  // 容量1024，最大元素64字节
 *     .elementCodec(PrimitiveCodecs.LONG)
 *     .build();
 * }</pre>
 *
 * @param <E> 元素类型
 */
public class RogueQueue<E> implements AutoCloseable {

    private final QueueStorage<E> storage;
    private final Allocator allocator;
    private final MmapAllocator mmapAllocator;
    private final Codec<E> elementCodec;

    private RogueQueue(QueueStorage<E> storage, Allocator allocator,
                       MmapAllocator mmapAllocator, Codec<E> elementCodec) {
        this.storage = storage;
        this.allocator = allocator;
        this.mmapAllocator = mmapAllocator;
        this.elementCodec = elementCodec;
    }

    /**
     * 入队操作
     *
     * @param element 要入队的元素
     * @return 如果成功返回true，如果队列已满（仅环形队列）返回false
     */
    public boolean offer(E element) {
        return storage.offer(element);
    }

    /**
     * 出队操作
     *
     * @return 队首元素，如果队列为空返回null
     */
    public E poll() {
        return storage.poll();
    }

    /**
     * 查看队首元素（不移除）
     *
     * @return 队首元素，如果队列为空返回null
     */
    public E peek() {
        return storage.peek();
    }

    /**
     * 获取队列大小
     *
     * @return 元素数量
     */
    public int size() {
        return storage.size();
    }

    /**
     * 检查队列是否为空
     *
     * @return 如果为空返回true
     */
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    /**
     * 检查队列是否已满（仅环形队列有意义）
     *
     * @return 如果已满返回true
     */
    public boolean isFull() {
        return storage.isFull();
    }

    /**
     * 清空队列
     */
    public void clear() {
        storage.clear();
    }

    /**
     * 刷新所有待处理的更改（用于持久化存储）
     */
    public void flush() {
        if (allocator instanceof MmapAllocator) {
            ((MmapAllocator) allocator).flush();
        }
    }

    @Override
    public void close() {
        // 如果是持久化模式，保存元数据
        if (mmapAllocator != null && !mmapAllocator.isTemporary()) {
            saveMmapMetadata();
        }

        storage.close();
        allocator.close();
    }

    private void saveMmapMetadata() {
        long currentDataOffset = allocator.usedMemory();
        int metadataSize = storage.serializedSize();
        long metadataOffset = currentDataOffset;
        long baseAddress = mmapAllocator.getBaseAddress();
        long metadataAddress = baseAddress + metadataOffset;

        // 序列化元数据
        storage.serialize(metadataAddress, baseAddress);

        // 更新header
        MmapFileHeader header = new MmapFileHeader();
        header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(MmapFileHeader.VERSION);
        header.setDataType(storage.getStorageType());
        header.setIndexType(0);
        header.setEntryCount(storage.size());
        header.setCurrentOffset(currentDataOffset + metadataSize);
        header.setIndexOffset(metadataOffset);
        header.setIndexSize(metadataSize);

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

        // 队列类型
        private QueueType queueType = QueueType.LINKED;
        private int circularCapacity = 1024;
        private int maxElementSize = 256;

        private MmapBuilder() {
        }

        /**
         * 设置元素编解码器
         */
        public MmapBuilder<E> elementCodec(Codec<E> elementCodec) {
            this.elementCodec = elementCodec;
            return this;
        }

        /**
         * 设置持久化文件路径
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
         */
        public MmapBuilder<E> temporary() {
            this.isTemporary = true;
            this.persistentFilePath = null;
            return this;
        }

        /**
         * 设置预分配文件大小
         */
        public MmapBuilder<E> allocateSize(long size) {
            if (size <= 0) {
                throw new IllegalArgumentException("分配大小必须为正数");
            }
            this.allocateSize = size;
            return this;
        }

        /**
         * 使用链表模式（无界队列）
         */
        public MmapBuilder<E> linked() {
            this.queueType = QueueType.LINKED;
            return this;
        }

        /**
         * 使用环形缓冲区模式（有界队列）
         *
         * @param capacity       队列容量
         * @param maxElementSize 最大元素大小（字节）
         */
        public MmapBuilder<E> circular(int capacity, int maxElementSize) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("容量必须为正数");
            }
            if (maxElementSize <= 0) {
                throw new IllegalArgumentException("最大元素大小必须为正数");
            }
            this.queueType = QueueType.CIRCULAR;
            this.circularCapacity = capacity;
            this.maxElementSize = maxElementSize;
            return this;
        }

        /**
         * 构建 RogueQueue 实例
         */
        public RogueQueue<E> build() {
            if (elementCodec == null) {
                throw new IllegalStateException("必须设置元素编解码器");
            }

            if (!isTemporary && (persistentFilePath == null || persistentFilePath.isEmpty())) {
                throw new IllegalStateException("MMAP 模式必须设置文件路径，请使用 persistent(filePath) 或 temporary()");
            }

            // 创建 MmapAllocator
            MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize, isTemporary);
            Allocator allocator = mmapAllocator;
            long baseAddress = mmapAllocator.getBaseAddress();

            QueueStorage<E> storage;

            // 持久化模式：检查是否是已存在的文件
            if (!isTemporary && mmapAllocator.isExistingFile()) {
                MmapFileHeader header = mmapAllocator.readHeader();

                // 恢复allocator的offset
                mmapAllocator.restoreOffset(header.getCurrentOffset());

                // 根据数据类型恢复
                if (header.getDataType() == MmapFileHeader.DATA_TYPE_QUEUE_LINKED) {
                    storage = restoreLinkedQueue(allocator, header, baseAddress);
                } else if (header.getDataType() == MmapFileHeader.DATA_TYPE_QUEUE_CIRCULAR) {
                    storage = restoreCircularQueue(allocator, header, baseAddress);
                } else {
                    throw new IllegalStateException("文件类型不匹配：期望 QUEUE，实际 " + header.getDataType());
                }
            } else {
                // 创建新队列
                if (queueType == QueueType.LINKED) {
                    storage = new LinkedQueueStorage<>(allocator, elementCodec);
                } else {
                    storage = new CircularQueueStorage<>(allocator, elementCodec, circularCapacity, maxElementSize);
                }
            }

            return new RogueQueue<>(storage, allocator, mmapAllocator, elementCodec);
        }

        @SuppressWarnings("unchecked")
        private QueueStorage<E> restoreLinkedQueue(Allocator allocator, MmapFileHeader header, long baseAddress) {
            // 恢复链表队列
            LinkedQueueStorage<E> storage = new LinkedQueueStorage<>(allocator, elementCodec);

            if (header.getIndexSize() > 0) {
                long metadataAddress = baseAddress + header.getIndexOffset();
                storage.deserialize(metadataAddress, (int) header.getIndexSize(), baseAddress);
            }

            return storage;
        }

        @SuppressWarnings("unchecked")
        private QueueStorage<E> restoreCircularQueue(Allocator allocator, MmapFileHeader header, long baseAddress) {
            // 从元数据恢复环形队列
            long metadataAddress = baseAddress + header.getIndexOffset();

            // 读取元数据
            long bufferRelOffset = com.yomahub.roguemap.memory.UnsafeOps.getLong(metadataAddress);
            int capacity = com.yomahub.roguemap.memory.UnsafeOps.getInt(metadataAddress + 8);
            int maxElementSize = com.yomahub.roguemap.memory.UnsafeOps.getInt(metadataAddress + 12);

            long bufferAddress = baseAddress + bufferRelOffset;

            return new CircularQueueStorage<>(allocator, elementCodec, bufferAddress, capacity, maxElementSize);
        }
    }

    private enum QueueType {
        LINKED,
        CIRCULAR
    }
}
