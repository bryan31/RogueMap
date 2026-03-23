package com.yomahub.roguemap.memory;

/**
 * 堆外内存管理的内存分配器接口
 */
public interface Allocator extends AutoCloseable {

    /**
     * 分配指定大小的内存
     *
     * @param size 字节大小
     * @return 内存地址（分配失败返回 0）
     */
    long allocate(int size);

    /**
     * 释放之前分配的内存
     *
     * @param address 内存地址
     * @param size 字节大小（slab 管理需要）
     */
    void free(long address, int size);

    /**
     * 获取总分配内存字节数
     *
     * @return 总分配字节数
     */
    long totalAllocated();

    /**
     * 获取已使用内存字节数
     *
     * @return 已使用字节数
     */
    long usedMemory();

    /**
     * 获取可用内存字节数
     *
     * @return 可用字节数
     */
    long availableMemory();

    /**
     * 释放所有已分配内存
     */
    @Override
    void close();
}
