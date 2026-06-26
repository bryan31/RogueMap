package com.yomahub.roguemap.memory;

/**
 * 物理内存地址与文件偏移量之间的双向转换。
 *
 * <p>持久化索引/元数据时，应当存储「文件偏移量」而不是「物理地址 - 第0段基址」的相对偏移。
 * 后者只有在所有 mmap 分段在虚拟地址空间内连续时才等于文件偏移量；一旦发生自动扩容产生多个
 * 分段（尤其是 JDK 25 注册式内存后端会给每个分段分配带间隙的合成基址），相对偏移就不再等于
 * 文件偏移量，跨会话重新打开时会换算出错误地址、读到错位/损坏数据。
 *
 * <p>使用文件偏移量进行持久化，再在恢复时通过 {@link #toAddress(long)} 换算回当前会话的物理
 * 地址，可与具体分段布局完全解耦。
 */
public interface AddressTranslator {

    /**
     * 将物理内存地址转换为文件内偏移量（用于持久化写入）。
     *
     * @param physicalAddress 物理内存地址
     * @return 对应的文件内字节偏移量
     */
    long toFileOffset(long physicalAddress);

    /**
     * 将文件内偏移量转换为当前会话的物理内存地址（用于恢复读取）。
     *
     * @param fileOffset 文件内字节偏移量
     * @return 对应的物理内存地址
     */
    long toAddress(long fileOffset);

    /**
     * 返回旧的「相对第0段基址」语义的转换器，仅用于向后兼容。
     *
     * <p>在单分段文件中 {@code 物理地址 - 基址 == 文件偏移}，因此该转换器对旧的单分段持久化
     * 文件等价于文件偏移量转换。多分段场景下此转换器并不正确，不应用于新代码。
     *
     * @param baseAddress 第0段基址
     * @return 基于固定基址做加减的转换器
     */
    static AddressTranslator relativeTo(final long baseAddress) {
        return new AddressTranslator() {
            @Override
            public long toFileOffset(long physicalAddress) {
                return physicalAddress - baseAddress;
            }

            @Override
            public long toAddress(long fileOffset) {
                return baseAddress + fileOffset;
            }
        };
    }
}
