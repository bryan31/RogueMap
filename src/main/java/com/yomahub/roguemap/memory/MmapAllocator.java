package com.yomahub.roguemap.memory;

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

    /**
     * 创建 MmapAllocator
     *
     * @param filePath 文件路径
     * @param fileSize 预分配文件大小（字节）
     */
    public MmapAllocator(String filePath, long fileSize) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("文件大小必须为正数");
        }

        this.file = new File(filePath);
        this.fileSize = fileSize;
        this.currentOffset = new AtomicLong(0);
        this.segments = new ArrayList<>();
        this.segmentBaseAddresses = new ArrayList<>();

        // 计算需要的分段数
        this.segmentCount = (int) ((fileSize + MAX_SEGMENT_SIZE - 1) / MAX_SEGMENT_SIZE);
        this.segmentSize = MAX_SEGMENT_SIZE;

        try {
            // 确保父目录存在
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // 打开文件
            this.raf = new RandomAccessFile(file, "rw");
            this.channel = raf.getChannel();

            // 预分配文件大小
            raf.setLength(fileSize);

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

        } catch (Exception e) {
            close();
            throw new RuntimeException("创建内存映射文件失败: " + filePath, e);
        }
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
            // 强制刷新所有分段到磁盘
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
        return sb.toString();
    }
}
