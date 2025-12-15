package com.yomahub.roguemap.serialization;

/**
 * 编解码器接口，用于将值编码到堆外内存或从堆外内存解码
 *
 * @param <T> 要编码/解码的类型
 */
public interface Codec<T> {

    /**
     * 将值编码到内存地址
     *
     * @param address 要写入的内存地址
     * @param value 要编码的值
     * @return 写入的字节数
     */
    int encode(long address, T value);

    /**
     * 从内存地址解码值
     *
     * @param address 要读取的内存地址
     * @return 解码后的值
     */
    T decode(long address);

    /**
     * 计算值编码后的大小
     * 如果大小可变，返回 -1（将在编码时计算）
     *
     * @param value 要计算大小的值
     * @return 字节大小，如果可变则返回 -1
     */
    int calculateSize(T value);

    /**
     * 该编解码器是否对所有值使用固定大小
     *
     * @return 如果是固定大小则返回 true，否则返回 false
     */
    default boolean isFixedSize() {
        return false;
    }

    /**
     * 获取固定大小（仅在 isFixedSize() 返回 true 时有效）
     *
     * @return 固定的字节大小
     */
    default int getFixedSize() {
        throw new UnsupportedOperationException("Codec is not fixed size");
    }
}
