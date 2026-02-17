package com.yomahub.roguemap.set;

/**
 * Set遍历时的元素消费接口
 */
@FunctionalInterface
public interface SetEntryConsumer<E> {

    /**
     * 接收一个元素及其存储信息
     *
     * @param element 元素
     * @param address 元素数据的内存地址
     * @param size    元素数据的大小
     */
    void accept(E element, long address, int size);
}
