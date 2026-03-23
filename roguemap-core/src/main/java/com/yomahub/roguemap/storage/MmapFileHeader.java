package com.yomahub.roguemap.storage;

import com.yomahub.roguemap.memory.UnsafeOps;

import java.util.zip.CRC32;

/**
 * MMAP 文件头管理
 *
 * 负责读写文件元数据，支持数据持久化和恢复
 *
 * 文件头布局（4KB）：
 * ===== 数据字段（bytes 0-47）=====
 * - Magic Number (4 bytes): 0x524D4150 "RMAP"
 * - Version (4 bytes): 1
 * - Data Type (4 bytes): 数据结构类型
 * - Index Type (4 bytes): 0=HashIndex, 1=SegmentedHashIndex, 2=LongPrimitiveIndex,
 *   3=IntPrimitiveIndex, 4=LowHeapStringIndex
 * - Entry Count (4 bytes)
 * - Current Offset (8 bytes)
 * - Index Offset (8 bytes)
 * - Index Size (8 bytes)
 * - Is Temporary (4 bytes): 0=persistent, 1=temporary
 *
 * ===== 完整性校验字段（bytes 48-59）=====
 * - CRC32 (4 bytes, offset 48): 数据字段 bytes 0-47 的 CRC32
 * - WriteGen (4 bytes, offset 52): 奇数=写入进行中, 偶数=写入完成, 0=旧格式兼容
 * - DirtyFlag (4 bytes, offset 56): 1=文件已被打开(未正常关闭), 0=正常关闭
 * - Padding (4 bytes, offset 60)
 *
 * ===== LinkedQueue 崩溃恢复快照（bytes 64-95）=====
 * - Snapshot.headRelOffset (8 bytes, offset 64)
 * - Snapshot.tailRelOffset (8 bytes, offset 72)
 * - Snapshot.size (4 bytes, offset 80)
 * - Snapshot.valid (4 bytes, offset 84): 1=快照有效
 * - Snapshot.currentAllocOffset (8 bytes, offset 88)
 *
 * ===== RogueList TTL（bytes 96-111）=====
 * - List ExpireTime (8 bytes, offset 96): RogueList 整体过期时间戳（0=永不过期）
 * - Reserved (8 bytes, offset 104)
 *
 * ===== RogueMemory 扩展字段（bytes 112-127）=====
 * - BM25 IndexOffset (8 bytes, offset 112): BM25 倒排索引在文件中的偏移量（0=尚未写入）
 * - HNSW Generation (8 bytes, offset 120): HNSW 文件的 generation 号，用于一致性校验
 *
 * ===== Reserved（bytes 128-4095）=====
 */
public class MmapFileHeader {

    public static final int MAGIC_NUMBER = 0x524D4150;  // "RMAP"
    public static final int VERSION = 2;  // v2: 支持 TTL
    public static final int HEADER_SIZE = 4096;  // 4KB

    // Data Type 常量
    public static final int DATA_TYPE_MAP = 0;            // RogueMap
    public static final int DATA_TYPE_LIST = 1;           // RogueList
    public static final int DATA_TYPE_SET = 2;            // RogueSet
    public static final int DATA_TYPE_QUEUE_LINKED = 3;   // RogueQueue Linked模式
    public static final int DATA_TYPE_QUEUE_CIRCULAR = 4; // RogueQueue Circular模式
    public static final int DATA_TYPE_MEMORY = 5;          // RogueMemory

    // ===== 完整性校验字段偏移量 =====
    public static final int CRC32_POS = 48;
    public static final int WRITE_GEN_POS = 52;
    public static final int DIRTY_FLAG_POS = 56;

    // ===== LinkedQueue 崩溃恢复快照偏移量 =====
    public static final int SNAPSHOT_HEAD_POS = 64;
    public static final int SNAPSHOT_TAIL_POS = 72;
    public static final int SNAPSHOT_SIZE_POS = 80;
    public static final int SNAPSHOT_VALID_POS = 84;
    public static final int SNAPSHOT_ALLOC_OFFSET_POS = 88;

    // ===== RogueList TTL 偏移量 =====
    public static final int LIST_EXPIRE_TIME_POS = 96;  // RogueList 整体过期时间戳

    // ===== RogueMemory 扩展字段（bytes 112-127）=====
    public static final int MEMORY_BM25_INDEX_OFFSET_POS = 112;  // BM25 倒排索引在文件中的偏移量（8 bytes）
    public static final int MEMORY_HNSW_GENERATION_POS = 120;    // HNSW 文件的 generation 号（8 bytes），用于一致性校验

    // 数据字段大小（CRC32 覆盖的范围）
    private static final int DATA_FIELDS_SIZE = 48;

    private int magicNumber;
    private int version;
    private int dataType;      // 数据结构类型
    private int indexType;      // 0=HashIndex, 1=SegmentedHashIndex, 2/3=Primitive, 4=LowHeapStringIndex
    private int entryCount;     // 条目数量
    private long currentOffset; // 当前分配偏移量
    private long indexOffset;   // 索引数据起始位置
    private long indexSize;     // 索引数据大小
    private int isTemporary;    // 0=persistent, 1=temporary

    public MmapFileHeader() {
        this.magicNumber = MAGIC_NUMBER;
        this.version = VERSION;
    }

    /**
     * 从内存地址读取头部
     */
    public static MmapFileHeader read(long address) {
        MmapFileHeader header = new MmapFileHeader();

        header.magicNumber = UnsafeOps.getInt(address);
        header.version = UnsafeOps.getInt(address + 4);
        header.dataType = UnsafeOps.getInt(address + 8);
        header.indexType = UnsafeOps.getInt(address + 12);
        header.entryCount = UnsafeOps.getInt(address + 16);
        header.currentOffset = UnsafeOps.getLong(address + 20);
        header.indexOffset = UnsafeOps.getLong(address + 28);
        header.indexSize = UnsafeOps.getLong(address + 36);
        header.isTemporary = UnsafeOps.getInt(address + 44);

        return header;
    }

    /**
     * 写入头部到内存地址（原子性写入：使用 Generation Counter + CRC32）
     *
     * 写入顺序：
     * 1. WriteGen 变为奇数（标记写入进行中）
     * 2. 写入数据字段（bytes 0-47）
     * 3. 计算 CRC32 并写入
     * 4. WriteGen 变为偶数（标记写入完成）
     * 5. 清除 DirtyFlag 和 SnapshotValid（标记正常关闭）
     */
    public void write(long address) {
        // 1. 标记写入开始（gen 变奇数）
        int gen = UnsafeOps.getInt(address + WRITE_GEN_POS);
        UnsafeOps.putInt(address + WRITE_GEN_POS, gen + 1);
        UnsafeOps.storeFence();

        // 2. 写入所有数据字段（bytes 0-47）
        UnsafeOps.putInt(address, magicNumber);
        UnsafeOps.putInt(address + 4, version);
        UnsafeOps.putInt(address + 8, dataType);
        UnsafeOps.putInt(address + 12, indexType);
        UnsafeOps.putInt(address + 16, entryCount);
        UnsafeOps.putLong(address + 20, currentOffset);
        UnsafeOps.putLong(address + 28, indexOffset);
        UnsafeOps.putLong(address + 36, indexSize);
        UnsafeOps.putInt(address + 44, isTemporary);

        // 3. 计算并写入 CRC32（覆盖 bytes 0-47）
        int crc = computeCRC32(address, DATA_FIELDS_SIZE);
        UnsafeOps.putInt(address + CRC32_POS, crc);

        // 4. 标记写入完成（gen 变偶数）
        UnsafeOps.storeFence();
        UnsafeOps.putInt(address + WRITE_GEN_POS, gen + 2);
        UnsafeOps.storeFence();

        // 5. 清除 DirtyFlag（正常关闭），清除 SnapshotValid（废弃快照）
        UnsafeOps.putInt(address + DIRTY_FLAG_POS, 0); // dirtyFlag = 正常关闭
        UnsafeOps.putInt(address + 60, 0);              // padding
        // bytes 64-87: snapshot data（保留，由 snapshotValid 控制是否有效）
        UnsafeOps.putInt(address + SNAPSHOT_VALID_POS, 0); // snapshotValid = 0（废弃快照）

        // 6. 清空剩余保留区域（bytes 96-4095）
        UnsafeOps.setMemory(address + 96, HEADER_SIZE - 96, (byte) 0);
    }

    /**
     * 检查文件是否已初始化（有效的头部）
     *
     * 兼容旧格式（WriteGen=0）：仅检查 Magic 和 Version
     * 新格式（WriteGen>0）：额外检查 Gen 完整性和 CRC32
     */
    public static boolean isValidHeader(long address) {
        int magic = UnsafeOps.getInt(address);
        int version = UnsafeOps.getInt(address + 4);
        if (magic != MAGIC_NUMBER || version != VERSION) {
            return false;
        }

        int writeGen = UnsafeOps.getInt(address + WRITE_GEN_POS);
        if (writeGen == 0) {
            // 旧格式兼容：跳过 CRC 检查
            return true;
        }
        if ((writeGen & 1) != 0) {
            // 写入未完成（gen 为奇数）：头部可能损坏
            return false;
        }

        // 验证 CRC32
        int storedCrc = UnsafeOps.getInt(address + CRC32_POS);
        int computedCrc = computeCRC32(address, DATA_FIELDS_SIZE);
        return storedCrc == computedCrc;
    }

    // ========== 崩溃检测与恢复（静态方法，直接操作 mmap 地址）==========

    /**
     * 标记文件为"已打开"状态（用于崩溃检测）
     * 应在打开已存在的持久化文件后立即调用
     */
    public static void markOpen(long address) {
        UnsafeOps.putInt(address + DIRTY_FLAG_POS, 1);
    }

    /**
     * 获取 DirtyFlag：1=上次未正常关闭（可能崩溃），0=正常关闭
     */
    public static int getDirtyFlag(long address) {
        return UnsafeOps.getInt(address + DIRTY_FLAG_POS);
    }

    /**
     * 获取快照有效标志：1=有效快照（崩溃前写入），0=无有效快照
     */
    public static int getSnapshotValid(long address) {
        return UnsafeOps.getInt(address + SNAPSHOT_VALID_POS);
    }

    /**
     * 获取快照中的 head 节点相对偏移量
     */
    public static long getSnapshotHead(long address) {
        return UnsafeOps.getLong(address + SNAPSHOT_HEAD_POS);
    }

    /**
     * 获取快照中的 tail 节点相对偏移量
     */
    public static long getSnapshotTail(long address) {
        return UnsafeOps.getLong(address + SNAPSHOT_TAIL_POS);
    }

    /**
     * 获取快照中的队列 size
     */
    public static int getSnapshotSize(long address) {
        return UnsafeOps.getInt(address + SNAPSHOT_SIZE_POS);
    }

    /**
     * 获取快照中的 allocator 当前偏移量（用于恢复 MmapAllocator 状态）
     */
    public static long getSnapshotAllocOffset(long address) {
        return UnsafeOps.getLong(address + SNAPSHOT_ALLOC_OFFSET_POS);
    }

    // ========== RogueList TTL ==========

    /**
     * 获取 RogueList 的整体过期时间戳
     *
     * @param address mmap 基地址
     * @return 过期时间戳（毫秒），0 表示永不过期
     */
    public static long getListExpireTime(long address) {
        return UnsafeOps.getLong(address + LIST_EXPIRE_TIME_POS);
    }

    /**
     * 设置 RogueList 的整体过期时间戳
     *
     * @param address    mmap 基地址
     * @param expireTime 过期时间戳（毫秒），0 表示永不过期
     */
    public static void setListExpireTime(long address, long expireTime) {
        UnsafeOps.putLong(address + LIST_EXPIRE_TIME_POS, expireTime);
    }

    // ========== RogueMemory 字段访问 ==========

    /**
     * 获取 BM25 倒排索引在文件中的偏移量
     *
     * @param address mmap 基地址
     * @return BM25 索引偏移量
     */
    public static long getBm25IndexOffset(long address) {
        return UnsafeOps.getLong(address + MEMORY_BM25_INDEX_OFFSET_POS);
    }

    /**
     * 设置 BM25 倒排索引在文件中的偏移量
     *
     * @param address mmap 基地址
     * @param offset  BM25 索引偏移量
     */
    public static void setBm25IndexOffset(long address, long offset) {
        UnsafeOps.putLong(address + MEMORY_BM25_INDEX_OFFSET_POS, offset);
    }

    /**
     * 获取 HNSW 文件的 generation 号（用于一致性校验）
     *
     * @param address mmap 基地址
     * @return HNSW generation 号
     */
    public static long getHnswGeneration(long address) {
        return UnsafeOps.getLong(address + MEMORY_HNSW_GENERATION_POS);
    }

    /**
     * 设置 HNSW 文件的 generation 号（用于一致性校验）
     *
     * @param address    mmap 基地址
     * @param generation HNSW generation 号
     */
    public static void setHnswGeneration(long address, long generation) {
        UnsafeOps.putLong(address + MEMORY_HNSW_GENERATION_POS, generation);
    }

    // ========== CRC32 计算 ==========

    /**
     * 计算 mmap 地址处的 CRC32（将数据复制到堆上 byte[] 后计算）
     */
    private static int computeCRC32(long address, int length) {
        byte[] data = new byte[length];
        UnsafeOps.copyToArray(address, data, 0, length);
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return (int) crc32.getValue();
    }

    // ========== Getters and Setters ==========

    public int getMagicNumber() {
        return magicNumber;
    }

    public void setMagicNumber(int magicNumber) {
        this.magicNumber = magicNumber;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getDataType() {
        return dataType;
    }

    public void setDataType(int dataType) {
        this.dataType = dataType;
    }

    public int getIndexType() {
        return indexType;
    }

    public void setIndexType(int indexType) {
        this.indexType = indexType;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
    }

    public long getCurrentOffset() {
        return currentOffset;
    }

    public void setCurrentOffset(long currentOffset) {
        this.currentOffset = currentOffset;
    }

    public long getIndexOffset() {
        return indexOffset;
    }

    public void setIndexOffset(long indexOffset) {
        this.indexOffset = indexOffset;
    }

    public long getIndexSize() {
        return indexSize;
    }

    public void setIndexSize(long indexSize) {
        this.indexSize = indexSize;
    }

    public int getIsTemporary() {
        return isTemporary;
    }

    public void setIsTemporary(int isTemporary) {
        this.isTemporary = isTemporary;
    }

    public boolean isTemporary() {
        return isTemporary == 1;
    }

    @Override
    public String toString() {
        return "MmapFileHeader{" +
                "magicNumber=0x" + Integer.toHexString(magicNumber) +
                ", version=" + version +
                ", dataType=" + dataType +
                ", indexType=" + indexType +
                ", entryCount=" + entryCount +
                ", currentOffset=" + currentOffset +
                ", indexOffset=" + indexOffset +
                ", indexSize=" + indexSize +
                ", isTemporary=" + isTemporary +
                '}';
    }
}
