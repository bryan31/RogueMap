package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.util.TempFileManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于内存映射文件的内存分配器
 *
 * 使用 FileChannel.map() 创建 MappedByteBuffer，将文件映射到内存
 * 适合大数据量持久化场景
 *
 * 注意：Java 8 的 MappedByteBuffer 单个分段最大支持 Integer.MAX_VALUE (约2GB)
 * 对于更大的文件，会自动分成多个分段
 */
public class MmapAllocator implements Allocator {

    private static final long MAX_SEGMENT_SIZE = Integer.MAX_VALUE; // 约 2GB

    private final File file;
    private final long fileSize;
    private final List<MappedByteBuffer> segments;
    private final List<Long> segmentBaseAddresses;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final AtomicLong currentOffset;
    private final long segmentSize;
    private final int segmentCount;
    private final boolean isTemporary;

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
     * @param filePath 文件路径（如果为 null 且 isTemporary=true，则自动生成临时文件）
     * @param fileSize 预分配文件大小（字节）
     * @param isTemporary 是否为临时文件模式
     */
    public MmapAllocator(String filePath, long fileSize, boolean isTemporary) {
        if (fileSize <= 0) {
            throw new IllegalArgumentException("文件大小必须为正数");
        }

        this.isTemporary = isTemporary;

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

        // 计算需要的分段数
        this.segmentCount = (int) ((fileSize + MAX_SEGMENT_SIZE - 1) / MAX_SEGMENT_SIZE);
        this.segmentSize = MAX_SEGMENT_SIZE;

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

            if (!fileExists) {
                // 新文件：预分配空间
                raf.setLength(fileSize);
            } else {
                // 现有文件：不要 setLength，保留数据
                long existingSize = file.length();
                if (existingSize < fileSize) {
                    // 只在需要时扩展
                    raf.setLength(fileSize);
                }
            }

            // 创建内存映射分段
            long remainingSize = fileSize;
            long offset = 0;

            for (int i = 0; i < segmentCount; i++) {
                long size = Math.min(remainingSize, segmentSize);
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, offset, size);
                segments.add(buffer);

                // 获取分段的底层地址
                long baseAddress = UnsafeOps.getDirectBufferAddress(buffer);
                segmentBaseAddresses.add(baseAddress);

                offset += size;
                remainingSize -= size;
            }

            // 注意：不在这里初始化头部，而是在第一次 close() 时初始化
            // 这样可以区分新文件和已有数据的文件

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

    @Override
    public long allocate(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("大小必须为正数: " + size);
        }

        // 使用 CAS 操作分配偏移量
        long offset;
        long newOffset;
        do {
            offset = currentOffset.get();
            newOffset = offset + size;

            // 检查是否超出文件大小
            if (newOffset > fileSize) {
                return 0; // 空间不足
            }
        } while (!currentOffset.compareAndSet(offset, newOffset));

        // 计算在哪个分段中
        int segmentIndex = (int) (offset / segmentSize);
        long segmentOffset = offset % segmentSize;

        // 返回实际内存地址
        return segmentBaseAddresses.get(segmentIndex) + segmentOffset;
    }

    @Override
    public void free(long address, int size) {
        // MMAP 模式下不需要单独释放内存
        // 内存由操作系统管理，关闭时统一释放
        // 这里可以考虑实现一个空闲列表来重用已释放的空间
        // 但为了简化实现，暂时不处理
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
            }
        } catch (Exception e) {
            throw new RuntimeException("关闭内存映射文件失败", e);
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
     * 获取文件大小
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
        sb.append("  分段数: ").append(segmentCount).append("\n");
        sb.append("  分段大小: ").append(segmentSize).append(" 字节\n");
        sb.append("  已使用: ").append(usedMemory()).append(" 字节\n");
        sb.append("  可用: ").append(availableMemory()).append(" 字节\n");
        sb.append("  利用率: ").append(String.format("%.2f%%", 100.0 * usedMemory() / fileSize));
        sb.append("\n  临时文件: ").append(isTemporary ? "是" : "否");
        return sb.toString();
    }

    /**
     * 检查是否为临时文件模式
     */
    public boolean isTemporary() {
        return isTemporary;
    }
}
