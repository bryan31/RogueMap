package com.yomahub.roguemap.storage;

import com.yomahub.roguemap.memory.UnsafeOps;

/**
 * MMAP 文件头管理
 *
 * 负责读写文件元数据，支持数据持久化和恢复
 *
 * 文件头布局（4KB）：
 * - Magic Number (4 bytes): 0x524D4150 "RMAP"
 * - Version (4 bytes): 1
 * - Index Type (4 bytes): 0=HashIndex, 1=SegmentedHashIndex
 * - Entry Count (4 bytes)
 * - Current Offset (8 bytes)
 * - Index Offset (8 bytes)
 * - Index Size (8 bytes)
 * - Is Temporary (4 bytes): 0=persistent, 1=temporary
 * - Reserved (3956 bytes)
 */
public class MmapFileHeader {

    public static final int MAGIC_NUMBER = 0x524D4150;  // "RMAP"
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 4096;  // 4KB

    private int magicNumber;
    private int version;
    private int indexType;      // 0=HashIndex, 1=SegmentedHashIndex
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
        header.indexType = UnsafeOps.getInt(address + 8);
        header.entryCount = UnsafeOps.getInt(address + 12);
        header.currentOffset = UnsafeOps.getLong(address + 16);
        header.indexOffset = UnsafeOps.getLong(address + 24);
        header.indexSize = UnsafeOps.getLong(address + 32);
        header.isTemporary = UnsafeOps.getInt(address + 40);

        return header;
    }

    /**
     * 写入头部到内存地址
     */
    public void write(long address) {
        UnsafeOps.putInt(address, magicNumber);
        UnsafeOps.putInt(address + 4, version);
        UnsafeOps.putInt(address + 8, indexType);
        UnsafeOps.putInt(address + 12, entryCount);
        UnsafeOps.putLong(address + 16, currentOffset);
        UnsafeOps.putLong(address + 24, indexOffset);
        UnsafeOps.putLong(address + 32, indexSize);
        UnsafeOps.putInt(address + 40, isTemporary);

        // 清空保留区域（确保干净的头部）
        UnsafeOps.setMemory(address + 44, HEADER_SIZE - 44, (byte) 0);
    }

    /**
     * 检查文件是否已初始化（有效的头部）
     */
    public static boolean isValidHeader(long address) {
        int magic = UnsafeOps.getInt(address);
        int version = UnsafeOps.getInt(address + 4);
        return magic == MAGIC_NUMBER && version == VERSION;
    }

    // Getters and Setters

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
                ", indexType=" + indexType +
                ", entryCount=" + entryCount +
                ", currentOffset=" + currentOffset +
                ", indexOffset=" + indexOffset +
                ", indexSize=" + indexSize +
                ", isTemporary=" + isTemporary +
                '}';
    }
}
