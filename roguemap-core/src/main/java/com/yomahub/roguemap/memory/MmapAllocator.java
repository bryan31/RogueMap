package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.util.TempFileManager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于内存映射文件的内存分配器
 *
 * 使用 FileChannel.map() 创建 MappedByteBuffer，将文件映射到内存
 * 适合大数据量持久化场景
 *
 * 注意：Java 8 的 MappedByteBuffer 单个分段最大支持 Integer.MAX_VALUE (约2GB)
 * 对于更大的文件，会自动分成多个分段
 *
 * === 自动扩容（autoExpand）===
 * 当 allocate() 检测到空间不足时，若 autoExpand=true，自动将文件扩容（expandFactor 倍）。
 * 扩容时仅对新增区域创建新的 MappedByteBuffer，已有 segment 地址完全不受影响。
 * 地址计算基于 segmentFileOffsets 二分查找，向后兼容非扩容场景。
 * 线程安全：普通 allocate() 持读锁（CAS 无锁），expand() 持写锁（独占）。
 */
public class MmapAllocator implements Allocator {

    private static final long MAX_SEGMENT_SIZE = Integer.MAX_VALUE; // 约 2GB

    private final File file;
    private volatile long fileSize;  // volatile：扩容时更新，其他线程可见
    private final List<MappedByteBuffer> segments;
    private final List<Long> segmentBaseAddresses;
    /**
     * 每个 segment 在文件中的起始偏移量。
     * segments[i] 映射文件区间 [segmentFileOffsets[i], segmentFileOffsets[i+1])，
     * 最后一个 segment 映射到 fileSize。
     * 初始化后 segments 和 segmentFileOffsets 长度始终相等。
     */
    private final List<Long> segmentFileOffsets;
    private volatile long[] segmentFileOffsetsSnapshot;
    private volatile long[] segmentBaseAddressesSnapshot;

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final AtomicLong currentOffset;
    private final boolean isTemporary;

    // 自动扩容配置
    private final boolean autoExpand;
    private final double expandFactor;
    private final long maxFileSize;             // 0 = 无上限
    private final ReentrantReadWriteLock expansionLock = new ReentrantReadWriteLock();

    /**
     * 创建 MmapAllocator（持久化模式）
     *
     * @param filePath 文件路径
     * @param fileSize 预分配文件大小（字节）
     */
    public MmapAllocator(String filePath, long fileSize) {
        this(filePath, fileSize, false);
    }

    /**
     * 创建 MmapAllocator
     *
     * @param filePath    文件路径（如果为 null 且 isTemporary=true，则自动生成临时文件）
     * @param fileSize    预分配文件大小（字节）
     * @param isTemporary 是否为临时文件模式
     */
    public MmapAllocator(String filePath, long fileSize, boolean isTemporary) {
        this(filePath, fileSize, isTemporary, false, 2.0, 0L);
    }

    /**
     * 创建 MmapAllocator（完整构造器）
     *
     * @param filePath     文件路径（如果为 null 且 isTemporary=true，则自动生成临时文件）
     * @param fileSize     预分配文件大小（字节）
     * @param isTemporary  是否为临时文件模式
     * @param autoExpand   是否开启自动扩容
     * @param expandFactor 每次扩容倍数（默认 2.0）
     * @param maxFileSize  扩容上限字节数，0 表示无上限
     */
    public MmapAllocator(String filePath, long fileSize, boolean isTemporary,
                         boolean autoExpand, double expandFactor, long maxFileSize) {
        if (fileSize <= 0) {
            throw new IllegalArgumentException("文件大小必须为正数");
        }
        if (expandFactor < 1.1) {
            throw new IllegalArgumentException("expandFactor 必须 >= 1.1");
        }

        this.isTemporary = isTemporary;
        this.autoExpand = autoExpand;
        this.expandFactor = expandFactor;
        this.maxFileSize = maxFileSize;

        // 如果是临时文件模式且未指定路径，自动生成临时文件
        if (isTemporary && (filePath == null || filePath.isEmpty())) {
            this.file = TempFileManager.createTempFile();
        } else {
            if (filePath == null || filePath.isEmpty()) {
                throw new IllegalArgumentException("文件路径不能为空");
            }
            this.file = new File(filePath);
        }

        this.fileSize = fileSize;
        // 数据从 HEADER_SIZE 之后开始分配
        this.currentOffset = new AtomicLong(com.yomahub.roguemap.storage.MmapFileHeader.HEADER_SIZE);
        this.segments = new ArrayList<>();
        this.segmentBaseAddresses = new ArrayList<>();
        this.segmentFileOffsets = new ArrayList<>();

        // 检查文件是否已存在
        boolean fileExists = file.exists() && file.length() > 0;

        try {
            // 确保父目录存在
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // 打开文件
            this.raf = new RandomAccessFile(file, "rw");
            this.channel = raf.getChannel();

            long effectiveSize = fileSize;
            if (!fileExists) {
                // 新文件：预分配空间
                raf.setLength(fileSize);
            } else {
                // 现有文件：不要 setLength，保留数据
                long existingSize = file.length();
                if (existingSize < fileSize) {
                    // 只在需要时扩展
                    raf.setLength(fileSize);
                } else if (existingSize > fileSize) {
                    // 文件因 autoExpand 比配置值大，映射实际大小以避免 SIGSEGV
                    effectiveSize = existingSize;
                }
            }

            this.fileSize = effectiveSize;
            // 创建初始内存映射分段
            mapFileRegion(0, effectiveSize);

            // 如果是临时文件，注册清理钩子
            if (isTemporary) {
                TempFileManager.registerCleanupHook(file, segments.toArray(new MappedByteBuffer[0]));
            }

        } catch (Exception e) {
            close();
            throw new RuntimeException("创建内存映射文件失败: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 将文件的指定区间映射到内存，追加新的 segment。
     * 必须在 expansionLock.writeLock() 或初始化阶段调用。
     *
     * @param fileOffset 该 segment 在文件中的起始字节偏移
     * @param regionSize 该 segment 映射的字节数
     */
    private void mapFileRegion(long fileOffset, long regionSize) throws IOException {
        long remaining = regionSize;
        long offset = fileOffset;

        while (remaining > 0) {
            long size = Math.min(remaining, MAX_SEGMENT_SIZE);
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, offset, size);
            segments.add(buffer);

            long baseAddress = UnsafeOps.getDirectBufferAddress(buffer);
            segmentBaseAddresses.add(baseAddress);
            segmentFileOffsets.add(offset);

            offset += size;
            remaining -= size;
        }
        refreshSegmentSnapshots();
    }

    private void refreshSegmentSnapshots() {
        long[] offsets = new long[segmentFileOffsets.size()];
        long[] bases = new long[segmentBaseAddresses.size()];
        for (int i = 0; i < segmentFileOffsets.size(); i++) {
            offsets[i] = segmentFileOffsets.get(i);
            bases[i] = segmentBaseAddresses.get(i);
        }
        this.segmentFileOffsetsSnapshot = offsets;
        this.segmentBaseAddressesSnapshot = bases;
    }

    /**
     * 初始化文件头（新文件）
     */
    private void initializeHeader() {
        com.yomahub.roguemap.storage.MmapFileHeader header =
            new com.yomahub.roguemap.storage.MmapFileHeader();
        header.setMagicNumber(com.yomahub.roguemap.storage.MmapFileHeader.MAGIC_NUMBER);
        header.setVersion(com.yomahub.roguemap.storage.MmapFileHeader.VERSION);
        header.setEntryCount(0);
        header.setCurrentOffset(com.yomahub.roguemap.storage.MmapFileHeader.HEADER_SIZE);  // 头部之后开始
        header.setIndexOffset(0);
        header.setIndexSize(0);
        header.setIsTemporary(isTemporary ? 1 : 0);

        long baseAddress = segmentBaseAddresses.get(0);
        header.write(baseAddress);
    }

    /**
     * 检查文件是否已初始化
     */
    public boolean isExistingFile() {
        long baseAddress = segmentBaseAddresses.get(0);
        return com.yomahub.roguemap.storage.MmapFileHeader.isValidHeader(baseAddress);
    }

    /**
     * 读取文件头
     */
    public com.yomahub.roguemap.storage.MmapFileHeader readHeader() {
        long baseAddress = segmentBaseAddresses.get(0);
        return com.yomahub.roguemap.storage.MmapFileHeader.read(baseAddress);
    }

    /**
     * 写入文件头
     */
    public void writeHeader(com.yomahub.roguemap.storage.MmapFileHeader header) {
        long baseAddress = segmentBaseAddresses.get(0);
        header.write(baseAddress);
    }

    /**
     * 恢复分配偏移量（用于数据恢复）
     */
    public void restoreOffset(long offset) {
        this.currentOffset.set(offset);
    }

    /**
     * 获取第一个分段的基地址
     */
    public long getBaseAddress() {
        return segmentBaseAddresses.get(0);
    }

    /**
     * 根据文件内偏移量查找所属 segment 的下标（二分查找）。
     * segmentFileOffsets 有序，在 expansionLock.readLock() 或 writeLock() 内调用均安全。
     */
    private int findSegmentIndex(long offset) {
        // 获取当前 segmentFileOffsets 的不可变快照，避免并发修改问题
        List<Long> offsets = segmentFileOffsets;
        int lo = 0, hi = offsets.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets.get(mid) <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    @Override
    public long allocate(int size) {
        if (size <= 0 || size > 512 * 1024 * 1024) {
            throw new IllegalArgumentException("分配大小非法: " + size + "（合法范围: 1 ~ 536870912 字节）");
        }

        // 使用读锁保护普通分配（允许并发，expand() 独占写锁）
        ReentrantReadWriteLock.ReadLock readLock = expansionLock.readLock();
        readLock.lock();
        try {
            long address = tryAllocate(size);
            if (address != 0) {
                return address;
            }
        } finally {
            readLock.unlock();
        }

        // 空间不足：尝试自动扩容
        if (!autoExpand) {
            return 0;
        }

        // 升级为写锁，执行扩容
        ReentrantReadWriteLock.WriteLock writeLock = expansionLock.writeLock();
        writeLock.lock();
        try {
            // Double-check：可能其他线程已完成扩容
            long address = tryAllocate(size);
            if (address != 0) {
                return address;
            }

            // 确实需要扩容
            expand(size);

            // 扩容后再次分配
            address = tryAllocate(size);
            if (address == 0) {
                throw new OutOfMemoryError("扩容后仍无法分配 " + size + " 字节，已达文件大小上限");
            }
            return address;
        } catch (IOException e) {
            throw new RuntimeException("文件扩容失败", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 尝试 CAS 分配，成功返回地址，空间不足返回 0。
     * 必须在 expansionLock.readLock() 或 writeLock() 内调用。
     *
     * <p>分配区间必须完全落在同一个 segment 内（segment 在虚拟地址上可能不连续）。
     * 若分配会跨越 segment 边界，则通过 CAS 跳过尾部空间，重试从下一 segment 开始分配。
     */
    private long tryAllocate(int size) {
        while (true) {
            long offset = currentOffset.get();
            long newOffset = offset + size;

            if (newOffset > fileSize) {
                return 0;
            }

            // 检查是否跨越 segment 边界
            int segIdx = findSegmentIndex(offset);
            long segEnd = (segIdx + 1 < segmentFileOffsets.size())
                    ? segmentFileOffsets.get(segIdx + 1)
                    : fileSize;

            if (newOffset > segEnd) {
                // 分配会跨越 segment 边界：浪费尾部字节，跳至下一 segment
                // 无论 CAS 成败都重试（成功则跳过了尾部，失败则其他线程已更新 offset）
                currentOffset.compareAndSet(offset, segEnd);
                continue;
            }

            // 分配区间完全在当前 segment 内
            if (!currentOffset.compareAndSet(offset, newOffset)) {
                continue; // CAS 竞争失败，重试
            }

            long segmentFileOffset = segmentFileOffsets.get(segIdx);
            return segmentBaseAddresses.get(segIdx) + (offset - segmentFileOffset);
        }
    }

    /**
     * 扩容：将文件大小增加到 max(当前offset+size, fileSize*expandFactor)。
     * 仅对新增区域创建新的 MappedByteBuffer，已有 segment 地址不变。
     * 必须在 expansionLock.writeLock() 内调用。
     *
     * @param requiredSize 此次分配所需字节数（确保扩容后一定够用）
     */
    private void expand(long requiredSize) throws IOException {
        long oldFileSize = fileSize;
        long needed = currentOffset.get() + requiredSize;
        long newFileSize = (long) (oldFileSize * expandFactor);
        if (newFileSize < needed) {
            newFileSize = needed;
        }
        // 向上对齐到 1MB，减少扩容次数
        newFileSize = ((newFileSize + 1024 * 1024 - 1) / (1024 * 1024)) * (1024 * 1024);

        if (maxFileSize > 0 && newFileSize > maxFileSize) {
            newFileSize = maxFileSize;
            if (newFileSize < needed) {
                throw new OutOfMemoryError("已达文件大小上限 " + maxFileSize
                        + " 字节，无法继续扩容分配 " + requiredSize + " 字节");
            }
        }

        // 扩展文件（填充零字节）
        raf.setLength(newFileSize);

        // 对新增区域创建 MappedByteBuffer
        long additionalRegionStart = oldFileSize;
        long additionalRegionSize = newFileSize - oldFileSize;
        mapFileRegion(additionalRegionStart, additionalRegionSize);

        // 最后更新 fileSize（volatile write，其他线程可见）
        fileSize = newFileSize;
    }

    @Override
    public void free(long address, int size) {
        // MMAP 模式下不需要单独释放内存
        // 内存由操作系统管理，关闭时统一释放
    }

    @Override
    public long totalAllocated() {
        return fileSize;
    }

    @Override
    public long usedMemory() {
        return currentOffset.get();
    }

    @Override
    public long availableMemory() {
        return fileSize - currentOffset.get();
    }

    @Override
    public void close() {
        try {
            // 临时文件模式：跳过持久化，直接清理
            if (isTemporary) {
                // 关闭通道和文件
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
                if (raf != null) {
                    raf.close();
                }

                releaseRegisteredSegmentAddresses();

                // 立即删除临时文件
                TempFileManager.deleteImmediately(file, segments.toArray(new MappedByteBuffer[0]));
            } else {
                // 持久化模式：正常刷新
                for (MappedByteBuffer segment : segments) {
                    if (segment != null) {
                        segment.force();
                    }
                }

                // 关闭通道和文件
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
                if (raf != null) {
                    raf.close();
                }

                releaseRegisteredSegmentAddresses();

                // 强制释放 MappedByteBuffer（Windows 上关闭 FileChannel 不会自动释放文件句柄，
                // 若不显式 unmap，compact()/delete() 时 rename 会失败）
                for (MappedByteBuffer segment : segments) {
                    if (segment != null) {
                        TempFileManager.forceUnmap(segment);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("关闭内存映射文件失败", e);
        }
    }

    private void releaseRegisteredSegmentAddresses() {
        for (Long baseAddress : segmentBaseAddresses) {
            if (baseAddress != null) {
                UnsafeOps.releaseDirectBufferAddress(baseAddress);
            }
        }
    }

    /**
     * 异步刷新数据到磁盘
     */
    public void flush() {
        for (MappedByteBuffer segment : segments) {
            if (segment != null) {
                segment.force();
            }
        }
    }

    /**
     * 获取文件路径
     */
    public String getFilePath() {
        return file.getAbsolutePath();
    }

    /**
     * 获取当前文件大小（可能因扩容而增长）
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * 获取内存使用统计信息
     */
    public String getStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("MmapAllocator 统计信息:\n");
        sb.append("  文件路径: ").append(getFilePath()).append("\n");
        sb.append("  文件大小: ").append(fileSize).append(" 字节\n");
        sb.append("  分段数: ").append(segments.size()).append("\n");
        sb.append("  已使用: ").append(usedMemory()).append(" 字节\n");
        sb.append("  可用: ").append(availableMemory()).append(" 字节\n");
        sb.append("  利用率: ").append(String.format("%.2f%%", 100.0 * usedMemory() / fileSize));
        sb.append("\n  临时文件: ").append(isTemporary ? "是" : "否");
        sb.append("\n  自动扩容: ").append(autoExpand ? "是（倍数=" + expandFactor + "）" : "否");
        return sb.toString();
    }

    /**
     * 根据文件偏移量计算对应的物理内存地址。
     * 适用于保存或加载已知文件偏移量处的数据。
     *
     * @param fileOffset 文件内字节偏移量
     * @return 对应的物理内存地址
     */
    public long getAddressForOffset(long fileOffset) {
        ReentrantReadWriteLock.ReadLock readLock = expansionLock.readLock();
        readLock.lock();
        try {
            int segIdx = findSegmentIndex(fileOffset);
            long segmentFileOffset = segmentFileOffsets.get(segIdx);
            return segmentBaseAddresses.get(segIdx) + (fileOffset - segmentFileOffset);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 快速路径：基于快照数组做无锁偏移转换。
     * 若快照不命中则回退到 getAddressForOffset()。
     */
    public long getAddressForOffsetFast(long fileOffset) {
        long[] offsets = segmentFileOffsetsSnapshot;
        long[] bases = segmentBaseAddressesSnapshot;
        if (offsets == null || bases == null || offsets.length == 0 || bases.length != offsets.length) {
            return getAddressForOffset(fileOffset);
        }

        int segIdx = findSegmentIndex(offsets, fileOffset);
        if (segIdx < 0 || segIdx >= offsets.length) {
            return getAddressForOffset(fileOffset);
        }

        long segStart = offsets[segIdx];
        long segEnd = (segIdx + 1 < offsets.length) ? offsets[segIdx + 1] : fileSize;
        if (fileOffset < segStart || fileOffset >= segEnd) {
            return getAddressForOffset(fileOffset);
        }
        return bases[segIdx] + (fileOffset - segStart);
    }

    /**
     * 根据物理内存地址反向计算文件偏移量。
     * 用于在序列化时将物理地址转换为可持久化的文件偏移量。
     *
     * @param physAddr 物理内存地址
     * @return 对应的文件内字节偏移量
     * @throws IllegalArgumentException 如果地址不属于任何映射段
     */
    public long getFileOffsetForAddress(long physAddr) {
        ReentrantReadWriteLock.ReadLock readLock = expansionLock.readLock();
        readLock.lock();
        try {
            for (int i = 0; i < segmentBaseAddresses.size(); i++) {
                long segBase = segmentBaseAddresses.get(i);
                long segFileOffset = segmentFileOffsets.get(i);
                long segSize = (i + 1 < segmentFileOffsets.size())
                        ? segmentFileOffsets.get(i + 1) - segFileOffset
                        : fileSize - segFileOffset;
                if (physAddr >= segBase && physAddr < segBase + segSize) {
                    return segFileOffset + (physAddr - segBase);
                }
            }
            throw new IllegalArgumentException("物理地址不属于任何映射段: 0x" + Long.toHexString(physAddr));
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 快速路径：基于快照数组做无锁地址反查。
     * 若快照不命中则回退到 getFileOffsetForAddress()。
     */
    public long getFileOffsetForAddressFast(long physAddr) {
        long[] offsets = segmentFileOffsetsSnapshot;
        long[] bases = segmentBaseAddressesSnapshot;
        if (offsets == null || bases == null || offsets.length == 0 || bases.length != offsets.length) {
            return getFileOffsetForAddress(physAddr);
        }

        for (int i = 0; i < bases.length; i++) {
            long segBase = bases[i];
            long segStart = offsets[i];
            long segSize = (i + 1 < offsets.length) ? offsets[i + 1] - segStart : fileSize - segStart;
            if (physAddr >= segBase && physAddr < segBase + segSize) {
                return segStart + (physAddr - segBase);
            }
        }
        return getFileOffsetForAddress(physAddr);
    }

    private int findSegmentIndex(long[] offsets, long offset) {
        int lo = 0;
        int hi = offsets.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * 检查是否为临时文件模式
     */
    public boolean isTemporary() {
        return isTemporary;
    }

    /**
     * 是否开启了自动扩容
     */
    public boolean isAutoExpand() {
        return autoExpand;
    }

    /**
     * 标记文件为"已打开"状态（用于崩溃检测）。
     * 应在打开已存在的持久化文件并恢复状态后立即调用。
     * 正常 close() 时 write() 会将 dirtyFlag 清为 0。
     * 若进程在 close() 前崩溃，dirtyFlag 保持为 1，下次打开时可检测到。
     */
    public void markOpen() {
        if (!isTemporary) {
            long baseAddress = segmentBaseAddresses.get(0);
            com.yomahub.roguemap.storage.MmapFileHeader.markOpen(baseAddress);
        }
    }
}
