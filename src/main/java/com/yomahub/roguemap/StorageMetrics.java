package com.yomahub.roguemap;

/**
 * 存储运维指标
 *
 * 提供文件大小、已用空间、活跃条目数、碎片率等运维信息，
 * 帮助用户判断何时需要调用 compact() 进行空间回收。
 */
public class StorageMetrics {

    private final long totalFileSize;
    private final long usedBytes;
    private final long availableBytes;
    private final int entryCount;
    private final long liveDataBytes;
    private final long deadBytes;
    private final long reclaimableBytes;
    private final double fragmentationRatio;
    private final boolean isTemporary;
    private final String filePath;
    private final long indexHeapBytesEstimate;
    private final long indexMmapBytes;
    private final double avgKeyBytes;

    public StorageMetrics(long totalFileSize, long usedBytes, long availableBytes,
                          int entryCount, long liveDataBytes, long deadBytes,
                          long reclaimableBytes,
                          double fragmentationRatio, boolean isTemporary, String filePath) {
        this(totalFileSize, usedBytes, availableBytes, entryCount, liveDataBytes, deadBytes, reclaimableBytes,
                fragmentationRatio, isTemporary, filePath, 0L, 0L, 0.0);
    }

    public StorageMetrics(long totalFileSize, long usedBytes, long availableBytes,
                          int entryCount, long liveDataBytes, long deadBytes,
                          long reclaimableBytes,
                          double fragmentationRatio, boolean isTemporary, String filePath,
                          long indexHeapBytesEstimate, long indexMmapBytes, double avgKeyBytes) {
        this.totalFileSize = totalFileSize;
        this.usedBytes = usedBytes;
        this.availableBytes = availableBytes;
        this.entryCount = entryCount;
        this.liveDataBytes = liveDataBytes;
        this.deadBytes = deadBytes;
        this.reclaimableBytes = reclaimableBytes;
        this.fragmentationRatio = fragmentationRatio;
        this.isTemporary = isTemporary;
        this.filePath = filePath;
        this.indexHeapBytesEstimate = indexHeapBytesEstimate;
        this.indexMmapBytes = indexMmapBytes;
        this.avgKeyBytes = avgKeyBytes;
    }

    /** 预分配文件总大小（字节） */
    public long getTotalFileSize() { return totalFileSize; }

    /** 已使用字节数（含已分配但已删除的死数据） */
    public long getUsedBytes() { return usedBytes; }

    /** 剩余可分配空间（字节） */
    public long getAvailableBytes() { return availableBytes; }

    /** 活跃条目数 */
    public int getEntryCount() { return entryCount; }

    /** 活跃数据字节总和（仅包含有效数据） */
    public long getLiveDataBytes() { return liveDataBytes; }

    /** 死数据字节数（已分配但不再被引用） */
    public long getDeadBytes() { return deadBytes; }

    /**
     * 可复用字节数（free list 中的节点，无需 compact() 即可被后续写入复用）。
     * 仅 LinkedQueue 有效，其他数据结构始终为 0。
     */
    public long getReclaimableBytes() { return reclaimableBytes; }

    /** 碎片率（0.0 ~ 1.0），越高越需要 compact */
    public double getFragmentationRatio() { return fragmentationRatio; }

    /** 是否为临时文件模式 */
    public boolean isTemporary() { return isTemporary; }

    /** 文件路径 */
    public String getFilePath() { return filePath; }

    /** 索引在堆内的估算占用（字节） */
    public long getIndexHeapBytesEstimate() { return indexHeapBytesEstimate; }

    /** 索引在 mmap 区域占用（字节） */
    public long getIndexMmapBytes() { return indexMmapBytes; }

    /** 平均 key 字节长度（含长度头部） */
    public double getAvgKeyBytes() { return avgKeyBytes; }

    /**
     * 判断是否需要压缩
     *
     * @param threshold 碎片率阈值（例如 0.5 表示50%以上碎片时建议压缩）
     * @return 如果碎片率超过阈值返回 true
     */
    public boolean shouldCompact(double threshold) {
        return fragmentationRatio > threshold;
    }

    @Override
    public String toString() {
        return "StorageMetrics{" +
                "totalFileSize=" + totalFileSize +
                ", usedBytes=" + usedBytes +
                ", availableBytes=" + availableBytes +
                ", entryCount=" + entryCount +
                ", liveDataBytes=" + liveDataBytes +
                ", deadBytes=" + deadBytes +
                ", reclaimableBytes=" + reclaimableBytes +
                ", fragmentationRatio=" + String.format("%.4f", fragmentationRatio) +
                ", isTemporary=" + isTemporary +
                ", filePath='" + filePath + '\'' +
                ", indexHeapBytesEstimate=" + indexHeapBytesEstimate +
                ", indexMmapBytes=" + indexMmapBytes +
                ", avgKeyBytes=" + String.format("%.2f", avgKeyBytes) +
                '}';
    }
}
