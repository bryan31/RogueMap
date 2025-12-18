package com.yomahub.roguemap.index;

import com.yomahub.roguemap.func.EntryConsumer;

/**
 * 索引接口，用于键值查找
 *
 * @param <K> 键类型
 */
public interface Index<K> {

    /**
     * 将键值对放入索引
     *
     * @param key 键
     * @param address 值存储的内存地址
     * @param size 值的字节大小
     * @return 如果键已存在则返回之前的地址，否则返回 0
     */
    long put(K key, long address, int size);

    /**
     * 获取键对应的内存地址
     *
     * @param key 键
     * @return 内存地址，如果未找到则返回 0
     */
    long get(K key);

    /**
     * 获取键对应值的大小
     *
     * @param key 键
     * @return 字节大小，如果未找到则返回 -1
     */
    int getSize(K key);

    /**
     * 从索引中移除键
     *
     * @param key 键
     * @return 被移除值的地址，如果未找到则返回 0
     */
    long remove(K key);

    /**
     * 原子性地更新索引并返回旧值信息
     * <p>
     * 此方法在单个锁保护下完成以下操作：
     * 1. 获取旧值的地址和大小
     * 2. 更新索引为新值
     * 3. 返回旧值信息
     * </p>
     * <p>
     * 这避免了在 get() 和 put() 之间的竞态条件，确保多线程同时更新同一个 key 时的安全性。
     * </p>
     *
     * @param key        键
     * @param newAddress 新值的内存地址
     * @param newSize    新值的大小
     * @return 更新结果，包含旧值的地址和大小信息
     */
    IndexUpdateResult putAndGetOld(K key, long newAddress, int newSize);

    /**
     * 原子性地删除键并返回被删除值的信息
     * <p>
     * 此方法在单个锁保护下完成以下操作：
     * 1. 获取值的地址和大小
     * 2. 从索引中删除键
     * 3. 返回被删除值的信息
     * </p>
     * <p>
     * 这避免了在 get() 和 remove() 之间的竞态条件。
     * </p>
     *
     * @param key 键
     * @return 删除结果，包含被删除值的地址和大小信息
     */
    IndexRemoveResult removeAndGet(K key);

    /**
     * 遍历所有索引条目
     * <p>
     * 用于在 clear() 操作前释放所有内存地址
     * </p>
     *
     * @param consumer 消费每个条目的回调函数
     */
    void forEach(IndexEntryConsumer consumer);

    /**
     * 检查键是否存在于索引中
     *
     * @param key 键
     * @return 如果存在返回 true，否则返回 false
     */
    boolean containsKey(K key);

    /**
     * 获取索引中的条目数量
     *
     * @return 条目数量
     */
    int size();

    /**
     * 从索引中移除所有条目
     */
    void clear();

    /**
     * 从索引中移除所有条目，并对每个移除的条目执行操作
     *
     * @param action 对每个被移除条目执行的操作 (address, size)
     */
    void clear(EntryConsumer action);

    /**
     * 关闭索引并释放资源
     */
    void close();

    /**
     * 序列化索引到内存地址（用于持久化）
     *
     * @param address 目标地址
     * @return 写入的字节数
     */
    int serialize(long address);

    /**
     * 从内存地址反序列化索引（用于恢复）
     *
     * @param address 源地址
     * @param size 数据大小
     */
    void deserialize(long address, int size);

    /**
     * 计算序列化后的大小
     *
     * @return 字节数
     */
    int serializedSize();

    /**
     * 序列化索引到内存地址（使用相对偏移量，用于 MMAP 持久化）
     *
     * @param address 目标地址
     * @param baseAddress 基础地址，用于计算相对偏移量
     * @return 写入的字节数
     */
    int serializeWithOffsets(long address, long baseAddress);

    /**
     * 从内存地址反序列化索引（使用相对偏移量，用于 MMAP 恢复）
     *
     * @param address 源地址
     * @param size 数据大小
     * @param baseAddress 基础地址，用于重新计算内存地址
     */
    void deserializeWithOffsets(long address, int size, long baseAddress);

    /**
     * 遍历索引中的所有条目
     *
     * @param action 对每个条目执行的操作 (address, size)
     */
    void forEach(EntryConsumer action);
}
