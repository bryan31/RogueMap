package com.yomahub.roguemap.index;

/**
 * 低堆索引配置项。
 */
public class LowHeapOptions {

    public static final int DEFAULT_SEGMENT_COUNT = 64;
    public static final double DEFAULT_LOAD_FACTOR = 0.80;
    public static final int DEFAULT_MAX_PROBE = 16;

    private final int segmentCount;
    private final double loadFactor;
    private final int maxProbe;

    public LowHeapOptions() {
        this(DEFAULT_SEGMENT_COUNT, DEFAULT_LOAD_FACTOR, DEFAULT_MAX_PROBE);
    }

    public LowHeapOptions(int segmentCount, double loadFactor, int maxProbe) {
        if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
            throw new IllegalArgumentException("segmentCount 必须是 2 的幂次方");
        }
        if (loadFactor <= 0.1 || loadFactor >= 0.95) {
            throw new IllegalArgumentException("loadFactor 必须在 (0.1, 0.95) 区间内");
        }
        if (maxProbe <= 0) {
            throw new IllegalArgumentException("maxProbe 必须为正数");
        }
        this.segmentCount = segmentCount;
        this.loadFactor = loadFactor;
        this.maxProbe = maxProbe;
    }

    public static LowHeapOptions defaults() {
        return new LowHeapOptions();
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public double getLoadFactor() {
        return loadFactor;
    }

    public int getMaxProbe() {
        return maxProbe;
    }
}
