package com.yomahub.roguemap.index;

/**
 * 索引更新操作的结果
 * <p>
 * 用于原子性地返回更新操作前的旧值信息
 * </p>
 */
public class IndexUpdateResult {
    /**
     * 旧值的内存地址（0 表示之前不存在）
     */
    public final long oldAddress;

    /**
     * 旧值的大小（字节）
     */
    public final int oldSize;

    /**
     * 是否存在旧值
     */
    public final boolean wasPresent;

    /**
     * 构造函数
     *
     * @param oldAddress 旧值的内存地址
     * @param oldSize    旧值的大小
     * @param wasPresent 是否存在旧值
     */
    public IndexUpdateResult(long oldAddress, int oldSize, boolean wasPresent) {
        this.oldAddress = oldAddress;
        this.oldSize = oldSize;
        this.wasPresent = wasPresent;
    }

    /**
     * 创建一个表示"不存在旧值"的结果
     *
     * @return 不存在旧值的结果
     */
    public static IndexUpdateResult noOldValue() {
        return new IndexUpdateResult(0, 0, false);
    }

    /**
     * 创建一个表示"存在旧值"的结果
     *
     * @param oldAddress 旧值的内存地址
     * @param oldSize    旧值的大小
     * @return 存在旧值的结果
     */
    public static IndexUpdateResult withOldValue(long oldAddress, int oldSize) {
        return new IndexUpdateResult(oldAddress, oldSize, true);
    }
}
