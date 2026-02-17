package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.serialization.Codec;

/**
 * 队列存储接口
 *
 * @param <E> 元素类型
 */
public interface QueueStorage<E> {

    /**
     * 入队操作
     *
     * @param element 要入队的元素
     * @return 如果成功返回true，如果队列已满（仅环形队列）返回false
     */
    boolean offer(E element);

    /**
     * 出队操作
     *
     * @return 队首元素，如果队列为空返回null
     */
    E poll();

    /**
     * 查看队首元素（不移除）
     *
     * @return 队首元素，如果队列为空返回null
     */
    E peek();

    /**
     * 获取队列大小
     *
     * @return 元素数量
     */
    int size();

    /**
     * 检查队列是否为空
     *
     * @return 如果为空返回true
     */
    boolean isEmpty();

    /**
     * 检查队列是否已满（仅环形队列有意义）
     *
     * @return 如果已满返回true
     */
    boolean isFull();

    /**
     * 清空队列
     */
    void clear();

    /**
     * 关闭队列
     */
    void close();

    /**
     * 获取存储模式类型
     *
     * @return 存储类型（用于持久化）
     */
    int getStorageType();

    /**
     * 计算序列化大小
     */
    int serializedSize();

    /**
     * 序列化到内存地址
     */
    int serialize(long address, long baseAddress);

    /**
     * 从内存地址反序列化
     */
    void deserialize(long address, int size, long baseAddress);
}
