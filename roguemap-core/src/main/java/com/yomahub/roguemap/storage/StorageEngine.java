package com.yomahub.roguemap.storage;

/**
 * 存储引擎接口，用于管理键值数据
 */
public interface StorageEngine extends AutoCloseable {

    /**
     * 在给定地址存储数据
     *
     * @param address 内存地址
     * @param data 数据字节数组
     * @param offset 数组中的偏移量
     * @param length 要写入的数据长度
     */
    void put(long address, byte[] data, int offset, int length);

    /**
     * 从给定地址检索数据
     *
     * @param address 内存地址
     * @param length 要读取的数据长度
     * @return 数据字节数组
     */
    byte[] get(long address, int length);

    /**
     * 删除给定地址的数据
     *
     * @param address 内存地址
     * @param length 要删除的数据长度
     */
    void delete(long address, int length);

    /**
     * 获取总容量（字节数）
     *
     * @return 总容量
     */
    long capacity();

    /**
     * 获取已使用的存储空间（字节数）
     *
     * @return 已使用字节数
     */
    long used();

    /**
     * 将所有待处理的更改刷新到持久化存储（如果适用）
     */
    void flush();

    /**
     * 关闭存储引擎并释放所有资源
     */
    @Override
    void close();
}
