package com.yomahub.roguemap.index;

/**
 * 事务批量操作的单条记录。
 *
 * <p>用于 {@link SegmentedHashIndex#applyBatch} 方法，描述一次 PUT 或 REMOVE 操作。
 *
 * @param <K> 键类型
 */
public class BatchEntry<K> {

    public enum OpType {
        PUT,
        REMOVE
    }

    /** 操作类型 */
    public final OpType opType;

    /** 键 */
    public final K key;

    /**
     * PUT 操作：新值的内存地址（已预先编码并分配好）。
     * REMOVE 操作：忽略，填 0 即可。
     */
    public final long newAddress;

    /**
     * PUT 操作：新值的字节大小。
     * REMOVE 操作：忽略，填 0 即可。
     */
    public final int newSize;

    private BatchEntry(OpType opType, K key, long newAddress, int newSize) {
        this.opType = opType;
        this.key = key;
        this.newAddress = newAddress;
        this.newSize = newSize;
    }

    /**
     * 构造一个 PUT 操作条目。
     *
     * @param key        键
     * @param newAddress 已分配并编码好的新值地址
     * @param newSize    新值字节大小
     */
    public static <K> BatchEntry<K> put(K key, long newAddress, int newSize) {
        return new BatchEntry<>(OpType.PUT, key, newAddress, newSize);
    }

    /**
     * 构造一个 REMOVE 操作条目。
     *
     * @param key 键
     */
    public static <K> BatchEntry<K> remove(K key) {
        return new BatchEntry<>(OpType.REMOVE, key, 0, 0);
    }
}
