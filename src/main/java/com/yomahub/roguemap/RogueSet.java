package com.yomahub.roguemap;

import com.yomahub.roguemap.index.LowHeapOptions;
import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.serialization.StringCodec;
import com.yomahub.roguemap.set.LowHeapStringSetIndex;
import com.yomahub.roguemap.set.SetAddResult;
import com.yomahub.roguemap.set.SetIndex;
import com.yomahub.roguemap.set.SetIndexStore;
import com.yomahub.roguemap.set.SetIterator;
import com.yomahub.roguemap.set.SetRemoveResult;
import com.yomahub.roguemap.storage.MmapFileHeader;
import com.yomahub.roguemap.storage.MmapStorage;
import com.yomahub.roguemap.storage.StorageEngine;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

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

    private final SetIndexStore<E> index;
    private final StorageEngine storage;
    private final Codec<E> elementCodec;
    private final Allocator allocator;
    private final LowHeapOptions lowHeapOptions;
    private final AutoCheckpointManager autoCheckpointManager;

    private RogueSet(SetIndexStore<E> index, StorageEngine storage,
                     Codec<E> elementCodec, Allocator allocator, LowHeapOptions lowHeapOptions,
                     long autoCheckpointInterval, int autoCheckpointOperations) {
        this.index = index;
        this.storage = storage;
        this.elementCodec = elementCodec;
        this.allocator = allocator;
        this.lowHeapOptions = lowHeapOptions;

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

            // 触发自动 checkpoint
            if (autoCheckpointManager != null) {
                autoCheckpointManager.onWriteOperation();
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

        // 触发自动 checkpoint
        if (autoCheckpointManager != null) {
            autoCheckpointManager.onWriteOperation();
        }

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
        index.forEach((element, address, size) -> liveBytes[0] += size);

        long dataRegion = usedBytes - MmapFileHeader.HEADER_SIZE;
        long deadBytes = dataRegion > 0 ? dataRegion - liveBytes[0] : 0;
        double fragmentationRatio = dataRegion > 0 ? (double) deadBytes / dataRegion : 0.0;

        long indexHeapBytesEstimate = 0L;
        long indexMmapBytes = 0L;
        double avgKeyBytes = 0.0;
        if (index instanceof LowHeapStringSetIndex) {
            LowHeapStringSetIndex lowHeapSetIndex = (LowHeapStringSetIndex) index;
            indexHeapBytesEstimate = lowHeapSetIndex.estimateHeapBytes();
            indexMmapBytes = lowHeapSetIndex.getIndexMmapBytes();
            avgKeyBytes = lowHeapSetIndex.getAverageElementBytes();
        }

        return new StorageMetrics(totalFileSize, usedBytes, availableBytes,
                entryCount, liveBytes[0], deadBytes, 0L, fragmentationRatio,
                mmapAllocator.isTemporary(), mmapAllocator.getFilePath(),
                indexHeapBytesEstimate, indexMmapBytes, avgKeyBytes);
    }

    /**
     * 将当前索引持久化到磁盘（检查点）
     *
     * <p>调用此方法后，即使进程崩溃，下次打开文件时也能恢复到最近一次 checkpoint 时的状态。
     * 仅 persistent 模式有效。
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

        saveMmapIndex();
        mmapAllocator.flush();
    }

    /**
     * 压缩存储文件，回收已删除数据占用的空间
     *
     * <p>创建一个仅包含活跃数据的新文件，关闭当前实例，替换原文件后重新打开。
     * 返回新的 RogueSet 实例（原实例已关闭，不可再使用）。
     *
     * <p>使用方式：{@code set = set.compact(newAllocateSize);}
     *
     * @param newAllocateSize 新文件的预分配大小（字节）
     * @return 压缩后的新 RogueSet 实例
     */
    public RogueSet<E> compact(long newAllocateSize) {
        if (!(storage instanceof MmapStorage)) {
            throw new UnsupportedOperationException("仅 MMAP 持久化模式支持 compact()");
        }
        MmapAllocator oldAllocator = ((MmapStorage) storage).getAllocator();
        if (oldAllocator.isTemporary()) {
            throw new UnsupportedOperationException("临时文件不支持 compact()");
        }

        String filePath = oldAllocator.getFilePath();
        String tempPath = filePath + ".compact";

        // 1. 创建新文件的 allocator
        MmapAllocator newAllocator = new MmapAllocator(tempPath, newAllocateSize, false);
        try {
            long newBaseAddress = newAllocator.getBaseAddress();
            int indexType = getIndexType(index);
            SetIndexStore<E> newIndex = createIndexByType(indexType, elementCodec, newAllocator,
                    64, 16, lowHeapOptions);

            // 2. 复制所有活跃元素到新文件
            index.forEach((element, address, size) -> {
                long newAddr = newAllocator.allocate(size);
                if (newAddr == 0) {
                    throw new RuntimeException("compact 空间不足：新文件大小 " + newAllocateSize + " 不够容纳所有活跃数据");
                }
                UnsafeOps.copyMemory(address, newAddr, size);
                newIndex.add(element, newAddr, size);
            });

            // 3. 序列化新索引到新文件
            long currentDataOffset = newAllocator.usedMemory();
            int newIndexSize = newIndex.serializedSize();
            long indexAddress = newAllocator.allocate(newIndexSize);
            if (indexAddress == 0) {
                throw new RuntimeException("compact 空间不足：无法分配索引区域");
            }
            long indexOffset = newAllocator.getFileOffsetForAddress(indexAddress);
            newIndex.serializeWithFileOffsets(indexAddress, newAllocator);

            MmapFileHeader header = new MmapFileHeader();
            header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
            header.setVersion(MmapFileHeader.VERSION);
            header.setDataType(MmapFileHeader.DATA_TYPE_SET);
            header.setIndexType(indexType);
            header.setEntryCount(newIndex.size());
            header.setCurrentOffset(currentDataOffset);
            header.setIndexOffset(indexOffset);
            header.setIndexSize(newIndexSize);
            newAllocator.writeHeader(header);

            // 4. 关闭新文件
            newIndex.close();
            newAllocator.close();
        } catch (Exception e) {
            newAllocator.close();
            new File(tempPath).delete();
            throw new RuntimeException("compact 失败", e);
        }

        // 5. 关闭旧文件
        index.close();
        storage.close();

        // 6. 交换文件
        File oldFile = new File(filePath);
        File newFile = new File(tempPath);
        oldFile.delete();
        if (!newFile.renameTo(oldFile)) {
            throw new RuntimeException("compact 文件重命名失败: " + tempPath + " -> " + filePath);
        }

        // 7. 重新打开
        MmapBuilder<E> reopenBuilder = RogueSet.<E>mmap()
                .persistent(filePath)
                .allocateSize(newAllocateSize)
                .elementCodec(elementCodec)
                .applyLowHeapModeIf(index instanceof LowHeapStringSetIndex);
        if (lowHeapOptions != null) {
            reopenBuilder.lowHeapOptions(lowHeapOptions);
        }
        return reopenBuilder.build();
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

        if (primaryException != null) {
            throw new RuntimeException("关闭 RogueSet 时发生错误", primaryException);
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
        long indexAddress = allocator.allocate(indexSize);
        if (indexAddress == 0) {
            throw new OutOfMemoryError("无法分配 " + indexSize + " 字节保存 Set 索引");
        }
        long indexOffset = mmapAllocator.getFileOffsetForAddress(indexAddress);

        // 序列化索引（使用文件偏移，支持扩容后的多段映射）
        index.serializeWithFileOffsets(indexAddress, mmapAllocator);

        // 更新头部
        MmapFileHeader header = new MmapFileHeader();
        header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(MmapFileHeader.VERSION);
        header.setDataType(MmapFileHeader.DATA_TYPE_SET);
        header.setIndexType(getIndexType(index));
        header.setEntryCount(index.size());
        header.setCurrentOffset(currentDataOffset);
        header.setIndexOffset(indexOffset);
        header.setIndexSize(indexSize);

        mmapAllocator.writeHeader(header);
    }

    private int getIndexType(SetIndexStore<E> index) {
        if (index instanceof LowHeapStringSetIndex) {
            return 4;
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static <E> SetIndexStore<E> createIndexByType(int indexType, Codec<E> elementCodec,
                                                           MmapAllocator mmapAllocator,
                                                           int segmentCount, int initialCapacity,
                                                           LowHeapOptions lowHeapOptions) {
        if (indexType == 0) {
            return new SetIndex<>(elementCodec, segmentCount, initialCapacity);
        } else if (indexType == 4) {
            if (mmapAllocator == null) {
                throw new IllegalStateException("LowHeapStringSetIndex 需要 MmapAllocator");
            }
            if (elementCodec != StringCodec.INSTANCE) {
                throw new IllegalStateException("LowHeapStringSetIndex 仅支持 StringCodec.INSTANCE");
            }
            return (SetIndexStore<E>) new LowHeapStringSetIndex(
                    mmapAllocator, lowHeapOptions != null ? lowHeapOptions : LowHeapOptions.defaults(), initialCapacity);
        }
        throw new IllegalStateException("未知的 Set 索引类型: " + indexType);
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
        private boolean autoExpand = false;
        private double expandFactor = 2.0;
        private long maxFileSize = 0L;
        private boolean useLowHeapIndex = false;
        private LowHeapOptions lowHeapOptions = LowHeapOptions.defaults();
        private long autoCheckpointInterval = -1;  // 毫秒，-1 表示未配置
        private int autoCheckpointOperations = -1; // -1 表示未配置

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
         * 使用低堆 String 索引模式（仅支持 StringCodec.INSTANCE）。
         */
        public MmapBuilder<E> lowHeapIndex() {
            this.useLowHeapIndex = true;
            return this;
        }

        /**
         * 设置低堆模式参数。
         */
        public MmapBuilder<E> lowHeapOptions(LowHeapOptions options) {
            if (options == null) {
                throw new IllegalArgumentException("lowHeapOptions 不能为 null");
            }
            this.lowHeapOptions = options;
            return this;
        }

        MmapBuilder<E> applyLowHeapModeIf(boolean enabled) {
            if (enabled) {
                this.useLowHeapIndex = true;
            }
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
        public MmapBuilder<E> autoCheckpoint(long interval, TimeUnit unit) {
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
         * <p>启用后，每 N 次写操作（add/remove）自动调用 checkpoint()，确保数据持久化。
         * 仅对持久化模式（persistent）有效，临时模式（temporary）忽略此配置。
         *
         * <p>可与 {@link #autoCheckpoint(long, TimeUnit)} 同时配置，任一条件满足即触发 checkpoint。
         *
         * @param operationCount 操作次数阈值
         * @return 此构建器
         */
        public MmapBuilder<E> autoCheckpoint(int operationCount) {
            if (operationCount <= 0) {
                throw new IllegalArgumentException("operationCount 必须为正数");
            }
            this.autoCheckpointOperations = operationCount;
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
            MmapAllocator mmapAllocator = new MmapAllocator(persistentFilePath, allocateSize, isTemporary,
                    autoExpand, expandFactor, maxFileSize);
            Allocator allocator = mmapAllocator;
            StorageEngine storage = new MmapStorage(mmapAllocator);

            SetIndexStore<E> index;

            // 临时文件模式：总是创建新索引
            if (isTemporary) {
                index = createNewIndex(elementCodec, mmapAllocator);
            } else {
                // 持久化模式：检查是否是已存在的文件
                if (mmapAllocator.isExistingFile()) {
                    // 恢复模式
                    MmapFileHeader header = mmapAllocator.readHeader();

                    // 验证数据类型
                    if (header.getDataType() != MmapFileHeader.DATA_TYPE_SET) {
                        throw new IllegalStateException("文件类型不匹配：期望 SET，实际 " + header.getDataType());
                    }

                    long recoveryOffset = header.getCurrentOffset();
                    long indexSnapshotEnd = header.getIndexOffset() + header.getIndexSize();
                    boolean needsLowHeapAllocation = (header.getIndexType() == 4) || useLowHeapIndex;

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
                    index = createIndexFromType(header.getIndexType(), elementCodec, mmapAllocator);

                    if (header.getIndexSize() > 0) {
                        long indexAddress = mmapAllocator.getAddressForOffset(header.getIndexOffset());
                        index.deserializeWithFileOffsets(indexAddress, (int) header.getIndexSize(), mmapAllocator);
                    }

                    if (!needsLowHeapAllocation) {
                        mmapAllocator.restoreOffset(recoveryOffset);
                    }

                    // 标记文件为"已打开"（崩溃检测）
                    mmapAllocator.markOpen();
                } else {
                    // 新文件模式
                    index = createNewIndex(elementCodec, mmapAllocator);
                }
            }

            // 仅持久化模式启用自动 checkpoint
            long checkpointInterval = isTemporary ? -1 : autoCheckpointInterval;
            int checkpointOperations = isTemporary ? -1 : autoCheckpointOperations;

            return new RogueSet<>(index, storage, elementCodec, allocator,
                    useLowHeapIndex ? lowHeapOptions : null, checkpointInterval, checkpointOperations);
        }

        private SetIndexStore<E> createIndexFromType(int indexType, Codec<E> codec, MmapAllocator allocator) {
            return RogueSet.createIndexByType(indexType, codec, allocator, segmentCount, initialCapacity, lowHeapOptions);
        }

        private SetIndexStore<E> createNewIndex(Codec<E> codec, MmapAllocator allocator) {
            if (useLowHeapIndex) {
                return RogueSet.createIndexByType(4, codec, allocator, segmentCount, initialCapacity, lowHeapOptions);
            }
            return RogueSet.createIndexByType(0, codec, allocator, segmentCount, initialCapacity, lowHeapOptions);
        }
    }
}
