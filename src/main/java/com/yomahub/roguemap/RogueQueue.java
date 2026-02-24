package com.yomahub.roguemap;

import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.queue.CircularQueueStorage;
import com.yomahub.roguemap.queue.LinkedQueueStorage;
import com.yomahub.roguemap.queue.QueueStorage;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.storage.MmapFileHeader;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.StorageEngine;

import java.io.File;

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

    /**
     * 获取运维指标
     *
     * @return 存储指标（文件大小、碎片率等）
     */
    public StorageMetrics getMetrics() {
        if (mmapAllocator == null) {
            throw new UnsupportedOperationException("仅 MMAP 模式支持 getMetrics()");
        }

        long totalFileSize = mmapAllocator.getFileSize();
        long usedBytes = mmapAllocator.usedMemory();
        long availableBytes = mmapAllocator.availableMemory();
        int entryCount = storage.size();

        long dataRegion = usedBytes - MmapFileHeader.HEADER_SIZE;
        long reclaimableBytes = 0L;
        long deadBytes = 0L;
        long liveBytes;
        double fragmentationRatio = 0.0;

        if (storage instanceof LinkedQueueStorage) {
            @SuppressWarnings("unchecked")
            LinkedQueueStorage<E> linkedStorage = (LinkedQueueStorage<E>) storage;
            // free list 节点可被后续 offer() 复用，不计入碎片
            reclaimableBytes = linkedStorage.getFreeListBytes();
            liveBytes = Math.max(0, dataRegion - reclaimableBytes);
            // fragmentationRatio 仅计真正无法复用的死数据（正常操作下为 0）
            fragmentationRatio = 0.0;
        } else {
            // CircularQueue：固定槽位分配，无碎片
            liveBytes = Math.max(0, dataRegion);
        }

        return new StorageMetrics(totalFileSize, usedBytes, availableBytes,
                entryCount, liveBytes, deadBytes, reclaimableBytes, fragmentationRatio,
                mmapAllocator.isTemporary(), mmapAllocator.getFilePath());
    }

    /**
     * 压缩存储文件，回收已删除数据占用的空间（仅链表队列支持）
     *
     * <p>创建一个仅包含活跃节点的新文件（丢弃空闲链表），关闭当前实例，替换原文件后重新打开。
     * 返回新的 RogueQueue 实例（原实例已关闭，不可再使用）。
     *
     * <p>使用方式：{@code queue = queue.compact(newAllocateSize);}
     *
     * @param newAllocateSize 新文件的预分配大小（字节）
     * @return 压缩后的新 RogueQueue 实例
     */
    public RogueQueue<E> compact(long newAllocateSize) {
        if (mmapAllocator == null || mmapAllocator.isTemporary()) {
            throw new UnsupportedOperationException("仅 MMAP 持久化模式支持 compact()");
        }
        if (!(storage instanceof LinkedQueueStorage)) {
            throw new UnsupportedOperationException("仅链表队列支持 compact()，环形队列不产生碎片");
        }

        LinkedQueueStorage<E> linkedStorage = (LinkedQueueStorage<E>) storage;
        String filePath = mmapAllocator.getFilePath();
        String tempPath = filePath + ".compact";

        // 1. 创建新文件的 allocator
        MmapAllocator newAllocator = new MmapAllocator(tempPath, newAllocateSize, false);
        try {
            long newBaseAddress = newAllocator.getBaseAddress();
            long snapshotAddr = newBaseAddress + MmapFileHeader.SNAPSHOT_HEAD_POS;
            LinkedQueueStorage<E> newStorage = new LinkedQueueStorage<>(newAllocator, elementCodec, snapshotAddr, newBaseAddress);

            // 2. 从头到尾遍历活跃节点，解码后重新入队
            long currentNode = linkedStorage.getHeadOffset();
            while (currentNode != 0) {
                // 从旧节点解码元素
                long elementAddr = currentNode + LinkedQueueStorage.NODE_HEADER_SIZE;
                E element = elementCodec.decode(elementAddr);

                // 入队到新 storage（自动分配新节点）
                newStorage.offer(element);

                // 获取下一个节点
                currentNode = linkedStorage.getNextOffset(currentNode);
            }

            // 3. 序列化新 storage 元数据
            long currentDataOffset = newAllocator.usedMemory();
            int metadataSize = newStorage.serializedSize();
            long metadataAddress = newBaseAddress + currentDataOffset;
            newStorage.serialize(metadataAddress, newBaseAddress);

            MmapFileHeader header = new MmapFileHeader();
            header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
            header.setVersion(MmapFileHeader.VERSION);
            header.setDataType(MmapFileHeader.DATA_TYPE_QUEUE_LINKED);
            header.setIndexType(0);
            header.setEntryCount(newStorage.size());
            header.setCurrentOffset(currentDataOffset + metadataSize);
            header.setIndexOffset(currentDataOffset);
            header.setIndexSize(metadataSize);
            newAllocator.writeHeader(header);

            // 4. 关闭新文件
            newStorage.close();
            newAllocator.close();
        } catch (Exception e) {
            newAllocator.close();
            new File(tempPath).delete();
            throw new RuntimeException("compact 失败", e);
        }

        // 5. 关闭旧文件
        storage.close();
        allocator.close();

        // 6. 交换文件
        File oldFile = new File(filePath);
        File newFile = new File(tempPath);
        oldFile.delete();
        if (!newFile.renameTo(oldFile)) {
            throw new RuntimeException("compact 文件重命名失败: " + tempPath + " -> " + filePath);
        }

        // 7. 重新打开
        return RogueQueue.<E>mmap()
                .persistent(filePath)
                .allocateSize(newAllocateSize)
                .linked()
                .elementCodec(elementCodec)
                .build();
    }

    @Override
    public void close() {
        Throwable primaryException = null;

        // 1. 持久化模式：先保存元数据（此时 storage 和 allocator 仍可用）
        try {
            if (mmapAllocator != null && !mmapAllocator.isTemporary()) {
                saveMmapMetadata();
            }
        } catch (Exception e) {
            primaryException = e;
        }

        // 2. 关闭 storage（storage.close() 会调用 clear()，释放内部状态）
        try {
            storage.close();
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        // 3. 关闭 allocator（flush + 关闭文件通道）
        // 注意：不再通过 storage 间接关闭 allocator，直接在此关闭，避免重复关闭
        try {
            allocator.close();
        } catch (Exception e) {
            if (primaryException == null) primaryException = e;
        }

        if (primaryException != null) {
            throw new RuntimeException("关闭 RogueQueue 时发生错误", primaryException);
        }
    }

    private void saveMmapMetadata() {
        long currentDataOffset = allocator.usedMemory();
        int metadataSize = storage.serializedSize();
        long metadataAddress = allocator.allocate(metadataSize);
        if (metadataAddress == 0) {
            throw new OutOfMemoryError("无法分配 " + metadataSize + " 字节保存 Queue 元数据");
        }
        long metadataOffset = mmapAllocator.getFileOffsetForAddress(metadataAddress);
        long baseAddress = mmapAllocator.getBaseAddress();

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
        private boolean autoExpand = false;
        private double expandFactor = 2.0;
        private long maxFileSize = 0L;

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
         * 开启自动扩容（默认关闭）
         *
         * @param autoExpand true 开启自动扩容
         * @return 此构建器
         */
        public MmapBuilder<E> autoExpand(boolean autoExpand) {
            this.autoExpand = autoExpand;
            return this;
        }

        /**
         * 设置每次扩容的倍数（默认 2.0）
         *
         * @param expandFactor 扩容倍数，必须 >= 1.1
         * @return 此构建器
         */
        public MmapBuilder<E> expandFactor(double expandFactor) {
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
        public MmapBuilder<E> maxFileSize(long maxFileSize) {
            if (maxFileSize < 0) {
                throw new IllegalArgumentException("maxFileSize 不能为负数");
            }
            this.maxFileSize = maxFileSize;
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
            MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize, isTemporary,
                    autoExpand, expandFactor, maxFileSize);
            Allocator allocator = mmapAllocator;
            long baseAddress = mmapAllocator.getBaseAddress();

            QueueStorage<E> storage;

            // 持久化模式：检查是否是已存在的文件
            if (!isTemporary && mmapAllocator.isExistingFile()) {
                MmapFileHeader header = mmapAllocator.readHeader();

                // 恢复 allocator 的 offset
                mmapAllocator.restoreOffset(header.getCurrentOffset());

                // 标记文件为"已打开"（崩溃检测）
                mmapAllocator.markOpen();

                // 根据数据类型恢复
                if (header.getDataType() == MmapFileHeader.DATA_TYPE_QUEUE_LINKED) {
                    storage = restoreLinkedQueue(allocator, mmapAllocator, header, baseAddress);
                } else if (header.getDataType() == MmapFileHeader.DATA_TYPE_QUEUE_CIRCULAR) {
                    storage = restoreCircularQueue(allocator, mmapAllocator, header, baseAddress);
                } else {
                    throw new IllegalStateException("文件类型不匹配：期望 QUEUE，实际 " + header.getDataType());
                }
            } else {
                // 创建新队列
                if (queueType == QueueType.LINKED) {
                    // 持久化模式：传递快照地址用于崩溃恢复
                    long snapshotAddr = isTemporary ? 0 : (baseAddress + MmapFileHeader.SNAPSHOT_HEAD_POS);
                    long baseAddr = isTemporary ? 0 : baseAddress;
                    storage = new LinkedQueueStorage<>(allocator, elementCodec, snapshotAddr, baseAddr);
                } else {
                    storage = new CircularQueueStorage<>(allocator, elementCodec, circularCapacity, maxElementSize);
                }
            }

            return new RogueQueue<>(storage, allocator, mmapAllocator, elementCodec);
        }

        @SuppressWarnings("unchecked")
        private QueueStorage<E> restoreLinkedQueue(Allocator allocator, MmapAllocator mmapAllocator,
                                                   MmapFileHeader header, long baseAddress) {
            long snapshotAddr = baseAddress + MmapFileHeader.SNAPSHOT_HEAD_POS;
            LinkedQueueStorage<E> storage = new LinkedQueueStorage<>(allocator, elementCodec, snapshotAddr, baseAddress);

            // 检查上次是否正常关闭（dirtyFlag）
            int dirtyFlag = MmapFileHeader.getDirtyFlag(baseAddress);
            if (dirtyFlag == 1) {
                // 上次可能崩溃：尝试从快照恢复（比 close() 写入的 metadata 更新）
                int snapshotValid = MmapFileHeader.getSnapshotValid(baseAddress);
                if (snapshotValid == 1) {
                    storage.deserializeFromSnapshot();
                    // 从快照恢复 allocator offset（比 header.getCurrentOffset() 更新）
                    long snapshotAllocOffset = MmapFileHeader.getSnapshotAllocOffset(baseAddress);
                    if (snapshotAllocOffset > header.getCurrentOffset()) {
                        mmapAllocator.restoreOffset(snapshotAllocOffset);
                    }
                    return storage;
                }
                // 快照无效（首次 close() 前崩溃），降级到普通恢复
            }

            // 正常关闭恢复：从 close() 时写入的 metadata 恢复
            if (header.getIndexSize() > 0) {
                long metadataAddress = mmapAllocator.getAddressForOffset(header.getIndexOffset());
                storage.deserialize(metadataAddress, (int) header.getIndexSize(), baseAddress);
            }

            return storage;
        }

        @SuppressWarnings("unchecked")
        private QueueStorage<E> restoreCircularQueue(Allocator allocator, MmapAllocator mmapAllocator,
                                                     MmapFileHeader header, long baseAddress) {
            // 从元数据恢复环形队列
            long metadataAddress = mmapAllocator.getAddressForOffset(header.getIndexOffset());

            // 读取元数据
            long bufferRelOffset = com.yomahub.roguemap.memory.UnsafeOps.getLong(metadataAddress);
            int capacity = com.yomahub.roguemap.memory.UnsafeOps.getInt(metadataAddress + 8);
            int maxElementSize = com.yomahub.roguemap.memory.UnsafeOps.getInt(metadataAddress + 12);

            long bufferAddress = mmapAllocator.getAddressForOffset(bufferRelOffset);

            return new CircularQueueStorage<>(allocator, elementCodec, bufferAddress, capacity, maxElementSize);
        }
    }

    private enum QueueType {
        LINKED,
        CIRCULAR
    }
}
