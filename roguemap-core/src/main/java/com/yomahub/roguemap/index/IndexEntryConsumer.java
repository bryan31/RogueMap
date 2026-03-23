package com.yomahub.roguemap.index;

/**
 * 索引条目消费者函数式接口
 * <p>
 * 用于遍历索引中的所有条目，例如在 clear() 操作前释放内存
 * </p>
 */
@FunctionalInterface
public interface IndexEntryConsumer {
    /**
     * 处理一个索引条目
     *
     * @param key     键
     * @param address 值的内存地址
     * @param size    值的大小（字节）
     */
    void accept(Object key, long address, int size);
}
