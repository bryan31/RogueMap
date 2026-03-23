package com.yomahub.roguemap.index;

/**
 * 索引删除操作的结果
 * <p>
 * 用于原子性地返回删除操作时的值信息
 * </p>
 */
public class IndexRemoveResult {
    /**
     * 被删除值的内存地址（0 表示不存在）
     */
    public final long address;

    /**
     * 被删除值的大小（字节）
     */
    public final int size;

    /**
     * 是否存在被删除的值
     */
    public final boolean wasPresent;

    /**
     * 构造函数
     *
     * @param address    被删除值的内存地址
     * @param size       被删除值的大小
     * @param wasPresent 是否存在被删除的值
     */
    public IndexRemoveResult(long address, int size, boolean wasPresent) {
        this.address = address;
        this.size = size;
        this.wasPresent = wasPresent;
    }

    /**
     * 创建一个表示"不存在"的结果
     *
     * @return 不存在的结果
     */
    public static IndexRemoveResult notPresent() {
        return new IndexRemoveResult(0, 0, false);
    }

    /**
     * 创建一个表示"存在并已删除"的结果
     *
     * @param address 被删除值的内存地址
     * @param size    被删除值的大小
     * @return 存在并已删除的结果
     */
    public static IndexRemoveResult removed(long address, int size) {
        return new IndexRemoveResult(address, size, true);
    }
}
