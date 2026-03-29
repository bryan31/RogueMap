package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.embedding.EmbeddingProvider;
import com.yomahub.roguemap.memory.index.BM25Index;
import com.yomahub.roguemap.memory.index.JVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import com.yomahub.roguemap.memory.util.Tokenizer;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.storage.MmapFileHeader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RogueMemory — 基于 mmap 的 AI 记忆存储，支持向量搜索 + BM25 关键词搜索。
 *
 * <p>记录格式（mmap 数据区）：
 * <pre>
 * [expireTime: 8B long]
 * [id: 16B UUID bytes (msb 8B + lsb 8B)]
 * [ns_len: 2B short][namespace bytes]
 * [content_len: 4B int][content bytes]
 * [meta_len: 4B int][metadata bytes]
 * [vector_len: 4B int][vector floats (4B each)]
 * [deleted: 1B]
 * [createdAt: 8B long]
 * </pre>
 *
 * <p>Metadata 编码：[pair_count: 2B short][key_len: 2B][key bytes][val_len: 2B][val bytes]...
 */
public class RogueMemory implements AutoCloseable {

    // ===== 默认配置 =====
    private static final long DEFAULT_FILE_SIZE = 64L * 1024 * 1024; // 64MB
    private static final int HNSW_MAX_ELEMENTS = 100_000;

    // ===== 内部状态 =====
    private final MmapAllocator allocator;
    private final String basePath;          // e.g. "target/test/mem"
    private final SearchMode searchMode;
    private final EmbeddingProvider embeddingProvider;

    private final BM25Index bm25Index;
    private JVectorIndex vectorIndex;    // null when KEYWORD_ONLY

    private OrdinalRegistry ordinalRegistry;
    private long[] offsetTable;             // ordinal → file offset of record start
    private long[] vectorOffsetTable;       // ordinal → file offset of vector floats within record

    private volatile boolean closed = false;

    // ===== 构造（私有，通过 Builder 创建）=====

    private RogueMemory(MmapAllocator allocator, String basePath,
                        SearchMode searchMode, EmbeddingProvider embeddingProvider,
                        BM25Index bm25Index, JVectorIndex vectorIndex,
                        OrdinalRegistry ordinalRegistry, long[] offsetTable, long[] vectorOffsetTable) {
        this.allocator = allocator;
        this.basePath = basePath;
        this.searchMode = searchMode;
        this.embeddingProvider = embeddingProvider;
        this.bm25Index = bm25Index;
        this.vectorIndex = vectorIndex;
        this.ordinalRegistry = ordinalRegistry;
        this.offsetTable = offsetTable;
        this.vectorOffsetTable = vectorOffsetTable;
    }

    // ===== 公开 API =====

    public String add(String content) {
        return add(content, Collections.<String, String>emptyMap(), "default");
    }

    public String add(String content, Map<String, String> metadata, String namespace) {
        checkOpen();
        String id = UUID.randomUUID().toString();
        int ordinal = ordinalRegistry.register(id);
        growTablesIfNeeded(ordinal);

        long createdAt = System.currentTimeMillis();
        float[] vector = null;
        if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
            vector = embeddingProvider.embed(content);
        }

        long recordOffset = writeRecordToAllocator(allocator, id, content, metadata, namespace,
                createdAt, 0L, vector, false);
        offsetTable[ordinal] = allocator.getFileOffsetForAddress(recordOffset);

        if (vector != null) {
            vectorOffsetTable[ordinal] = computeVectorOffset(recordOffset, namespace, content, metadata);
        }

        bm25Index.addDocument(ordinal, content);
        if (vectorIndex != null && vector != null) {
            vectorIndex.add(ordinal, vector);
        }
        return id;
    }

    public MemoryEntry get(String id) {
        checkOpen();
        int ordinal = ordinalRegistry.getOrdinal(id);
        if (ordinal == -1) return null;
        if (ordinal >= offsetTable.length || offsetTable[ordinal] == 0) return null;
        return readRecord(offsetTable[ordinal]);
    }

    public void delete(String id) {
        checkOpen();
        int ordinal = ordinalRegistry.getOrdinal(id);
        if (ordinal == -1) return;
        if (ordinal >= offsetTable.length || offsetTable[ordinal] == 0) return;

        long fileOffset = offsetTable[ordinal];
        long addr = allocator.getAddressForOffset(fileOffset);
        int deletedByteOffset = computeDeletedByteOffset(addr);
        UnsafeOps.putByte(addr + deletedByteOffset, (byte) 1);

        bm25Index.removeDocument(ordinal);
        if (vectorIndex != null) vectorIndex.markDeleted(ordinal);
        ordinalRegistry.release(id);
        offsetTable[ordinal] = 0;
        if (ordinal < vectorOffsetTable.length) vectorOffsetTable[ordinal] = 0;
    }

    public void update(String id, String newContent) {
        checkOpen();
        int ordinal = ordinalRegistry.getOrdinal(id);
        if (ordinal == -1) return;
        if (ordinal >= offsetTable.length || offsetTable[ordinal] == 0) return;

        MemoryEntry old = readRecord(offsetTable[ordinal]);
        if (old == null) return;

        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        int deletedByteOffset = computeDeletedByteOffset(addr);
        UnsafeOps.putByte(addr + deletedByteOffset, (byte) 1);

        float[] vector = null;
        if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
            vector = embeddingProvider.embed(newContent);
        }

        long recordOffset = writeRecordToAllocator(allocator, id, newContent, old.getMetadata(),
                old.getNamespace(), old.getCreatedAt(), old.getExpireTime(), vector, false);
        offsetTable[ordinal] = allocator.getFileOffsetForAddress(recordOffset);

        if (vector != null) {
            vectorOffsetTable[ordinal] = computeVectorOffset(recordOffset, old.getNamespace(), newContent, old.getMetadata());
        }

        bm25Index.addDocument(ordinal, newContent);
        if (vectorIndex != null && vector != null) {
            vectorIndex.add(ordinal, vector);
        }
    }

    public List<MemoryResult> search(String query, int topK) {
        return search(query, topK, SearchOptions.builder().build());
    }

    public List<MemoryResult> search(String query, int topK, SearchOptions options) {
        checkOpen();
        List<MemoryResult> candidates;
        switch (searchMode) {
            case VECTOR_ONLY:
                candidates = vectorSearch(query, topK * 4, options);
                break;
            case KEYWORD_ONLY:
                candidates = keywordSearch(query, topK * 4, options);
                break;
            default: // HYBRID
                candidates = hybridSearch(query, topK * 4, options);
                break;
        }
        // Apply filters
        candidates = applyFilters(candidates, options);
        // Trim to topK
        if (candidates.size() > topK) candidates = candidates.subList(0, topK);
        return candidates;
    }

    public void checkpoint() {
        checkOpen();
        saveIndexes();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        saveIndexes();
        allocator.close();
        if (vectorIndex != null) vectorIndex.close();
    }

    /**
     * Compact: copy only live records to a new file, rebuild indexes, return new instance.
     */
    public RogueMemory compact(long newFileSize) {
        checkOpen();
        String tmpMemPath = basePath + ".mem.tmp";
        String tmpHnswPath = basePath + ".hnsw.tmp";
        String memPath = basePath + ".mem";
        String hnswPath = basePath + ".hnsw";

        MmapAllocator newAlloc = new MmapAllocator(tmpMemPath, newFileSize, false);
        long newBase = newAlloc.getBaseAddress();
        MmapFileHeader newHeader = new MmapFileHeader();
        newHeader.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
        newHeader.setVersion(MmapFileHeader.VERSION);
        newHeader.setDataType(MmapFileHeader.DATA_TYPE_MEMORY);
        newHeader.setCurrentOffset(MmapFileHeader.HEADER_SIZE);
        newHeader.write(newBase);

        OrdinalRegistry newRegistry = new OrdinalRegistry();
        long[] newOffsetTable = new long[1024];
        long[] newVectorOffsetTable = new long[1024];
        BM25Index newBm25 = new BM25Index(1.2f, 0.75f);
        JVectorIndex newHnsw = (vectorIndex != null)
            ? new JVectorIndex(embeddingProvider.getDimension(), newVectorOffsetTable, newAlloc)
            : null;

        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long currentEnd = allocator.usedMemory();
        while (scanOffset < currentEnd) {
            long addr = allocator.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                MemoryEntry entry = parseRecordFull(addr, rh);
                if (entry != null && !entry.isExpired()) {
                    int ordinal = newRegistry.register(entry.getId());
                    if (ordinal >= newOffsetTable.length) {
                        newOffsetTable = Arrays.copyOf(newOffsetTable, ordinal + 1);
                        newVectorOffsetTable = Arrays.copyOf(newVectorOffsetTable, ordinal + 1);
                    }
                    long newAddr = writeRecordToAllocator(newAlloc, entry.getId(),
                            entry.getContent(), entry.getMetadata(), entry.getNamespace(),
                            entry.getCreatedAt(), entry.getExpireTime(), entry.getVector(), false);
                    newOffsetTable[ordinal] = newAlloc.getFileOffsetForAddress(newAddr);
                    if (entry.getVector() != null) {
                        newVectorOffsetTable[ordinal] = newOffsetTable[ordinal] + computeVectorOffsetInRecord(entry);
                    }
                    newBm25.addDocument(ordinal, entry.getContent());
                    if (newHnsw != null && entry.getVector() != null) {
                        newHnsw.add(ordinal, entry.getVector());
                    }
                }
            }
            scanOffset += rh.totalSize;
        }

        long newOrdinalRegistryOffset = 0;
        try {
            byte[] regData = newRegistry.serialize();
            long regAddr = newAlloc.allocate(4 + regData.length);
            UnsafeOps.putInt(regAddr, regData.length);
            UnsafeOps.copyFromArray(regData, 0, regAddr + 4, regData.length);
            newOrdinalRegistryOffset = newAlloc.getFileOffsetForAddress(regAddr);
        } catch (IOException e) {
            throw new RuntimeException("compact: OrdinalRegistry serialize failed", e);
        }

        long newBm25FileOffset = 0;
        try {
            byte[] bm25Data = newBm25.serialize();
            long bm25Addr = newAlloc.allocate(4 + bm25Data.length);
            UnsafeOps.putInt(bm25Addr, bm25Data.length);
            UnsafeOps.copyFromArray(bm25Data, 0, bm25Addr + 4, bm25Data.length);
            newBm25FileOffset = newAlloc.getFileOffsetForAddress(bm25Addr);
        } catch (IOException e) {
            throw new RuntimeException("compact: BM25 serialize failed", e);
        }

        long newGeneration = System.currentTimeMillis();
        if (newHnsw != null) {
            try (FileOutputStream fos = new FileOutputStream(tmpHnswPath)) {
                newHnsw.serialize(fos);
            } catch (IOException e) {
                throw new RuntimeException("compact: HNSW serialize failed", e);
            }
        }

        MmapFileHeader hdr = newAlloc.readHeader();
        hdr.setCurrentOffset(newAlloc.usedMemory());
        hdr.setEntryCount(newRegistry.capacity());
        hdr.write(newBase);

        if (newOrdinalRegistryOffset != 0) {
            MmapFileHeader.setOrdinalRegistryOffset(newBase, newOrdinalRegistryOffset);
        }
        if (newBm25FileOffset != 0) {
            MmapFileHeader.setBm25IndexOffset(newBase, newBm25FileOffset);
        }
        if (newHnsw != null) {
            MmapFileHeader.setHnswGeneration(newBase, newGeneration);
        }

        newAlloc.flush();
        newAlloc.close();

        try {
            Files.move(new File(tmpMemPath).toPath(), new File(memPath).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            if (newHnsw != null) {
                Files.move(new File(tmpHnswPath).toPath(), new File(hnswPath).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("compact: rename failed", e);
        }

        MmapAllocator compactedAlloc = new MmapAllocator(memPath, newFileSize, false);
        compactedAlloc.restoreOffset(compactedAlloc.readHeader().getCurrentOffset());

        JVectorIndex loadedHnsw = null;
        if (newHnsw != null) {
            try (FileInputStream fis = new FileInputStream(hnswPath)) {
                loadedHnsw = JVectorIndex.deserialize(fis, embeddingProvider.getDimension(), newVectorOffsetTable, compactedAlloc);
            } catch (IOException e) {
                throw new RuntimeException("compact: HNSW load failed", e);
            }
        }

        return new RogueMemory(compactedAlloc, basePath, searchMode,
                embeddingProvider, newBm25, loadedHnsw, newRegistry, newOffsetTable, newVectorOffsetTable);
    }

    // ===== Builder =====

    public static MmapBuilder mmap() {
        return new MmapBuilder();
    }

    public static class MmapBuilder {
        private String path;
        private EmbeddingProvider embeddingProvider;
        private SearchMode searchMode = SearchMode.HYBRID;
        private long fileSize = DEFAULT_FILE_SIZE;

        public MmapBuilder persistent(String path) {
            this.path = path;
            return this;
        }

        public MmapBuilder embeddingProvider(EmbeddingProvider provider) {
            this.embeddingProvider = provider;
            return this;
        }

        public MmapBuilder searchMode(SearchMode mode) {
            this.searchMode = mode;
            return this;
        }

        public MmapBuilder allocateSize(long size) {
            this.fileSize = size;
            return this;
        }

        public RogueMemory build() {
            if (path == null) throw new IllegalArgumentException("path must be set");
            String memPath = path + ".mem";
            String hnswPath = path + ".hnsw";

            MmapAllocator allocator = new MmapAllocator(memPath, fileSize, false);
            long baseAddr = allocator.getBaseAddress();

            OrdinalRegistry ordinalRegistry = new OrdinalRegistry();
            long[] offsetTable = new long[1024];
            long[] vectorOffsetTable = new long[1024];
            BM25Index bm25 = new BM25Index(1.2f, 0.75f);
            JVectorIndex hnsw = null;

            boolean isExisting = allocator.isExistingFile();
            if (isExisting) {
                MmapFileHeader header = allocator.readHeader();
                allocator.restoreOffset(header.getCurrentOffset());
                MmapFileHeader.markOpen(baseAddr);

                int dirtyFlag = MmapFileHeader.getDirtyFlag(baseAddr);
                if (dirtyFlag == 1) {
                    // Dirty: full scan to rebuild
                    RebuildResult result = rebuildFromScan(allocator, embeddingProvider, searchMode);
                    ordinalRegistry = result.ordinalRegistry;
                    offsetTable = result.offsetTable;
                    vectorOffsetTable = result.vectorOffsetTable;
                    bm25 = result.bm25Index;
                    hnsw = result.hnswIndex;
                } else {
                    // Clean: restore from saved indexes
                    long ordinalRegistryOffset = MmapFileHeader.getOrdinalRegistryOffset(baseAddr);
                    if (ordinalRegistryOffset > 0) {
                        ordinalRegistry = loadOrdinalRegistry(allocator, ordinalRegistryOffset);
                    }

                    RebuildResult result = rebuildOffsetTables(allocator, ordinalRegistry);
                    offsetTable = result.offsetTable;
                    vectorOffsetTable = result.vectorOffsetTable;

                    bm25 = loadBm25(allocator, baseAddr);
                    hnsw = loadHnsw(allocator, baseAddr, hnswPath, embeddingProvider, searchMode, vectorOffsetTable);

                    if (hnsw == null && searchMode != SearchMode.KEYWORD_ONLY) {
                        RebuildResult fullResult = rebuildFromScan(allocator, embeddingProvider, searchMode);
                        hnsw = fullResult.hnswIndex;
                    }
                }

                return new RogueMemory(allocator, path, searchMode, embeddingProvider, bm25, hnsw,
                        ordinalRegistry, offsetTable, vectorOffsetTable);
            } else {
                MmapFileHeader header = new MmapFileHeader();
                header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
                header.setVersion(MmapFileHeader.VERSION);
                header.setDataType(MmapFileHeader.DATA_TYPE_MEMORY);
                header.setCurrentOffset(MmapFileHeader.HEADER_SIZE);
                header.write(baseAddr);
                MmapFileHeader.markOpen(baseAddr);

                if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
                    ensureDimension(embeddingProvider);
                    hnsw = new JVectorIndex(embeddingProvider.getDimension(), vectorOffsetTable, allocator);
                }
                return new RogueMemory(allocator, path, searchMode, embeddingProvider, bm25, hnsw,
                        ordinalRegistry, offsetTable, vectorOffsetTable);
            }
        }
    }

    // ===== Private helpers: record I/O =====

    /** Minimal header info needed to skip a record during scan */
    private static class RecordHeader {
        long expireTime;
        String id;
        boolean deleted;
        int totalSize;
    }

    /**
     * Read just enough of a record to get id, deleted flag, and total size.
     * Returns null if the record is malformed.
     */
    private static RecordHeader parseRecordHeader(long addr) {
        try {
            RecordHeader rh = new RecordHeader();
            long pos = addr;
            rh.expireTime = UnsafeOps.getLong(pos); pos += 8;
            // id: 16 bytes (UUID msb + lsb)
            long msb = UnsafeOps.getLong(pos); pos += 8;
            long lsb = UnsafeOps.getLong(pos); pos += 8;
            rh.id = new UUID(msb, lsb).toString();
            // namespace
            int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2;
            pos += nsLen;
            // content
            int contentLen = UnsafeOps.getInt(pos); pos += 4;
            pos += contentLen;
            // metadata
            int metaLen = UnsafeOps.getInt(pos); pos += 4;
            pos += metaLen;
            // vector
            int vectorLen = UnsafeOps.getInt(pos); pos += 4;
            pos += (long) vectorLen * 4;
            // deleted
            rh.deleted = UnsafeOps.getByte(pos) != 0; pos += 1;
            // createdAt
            pos += 8;
            rh.totalSize = (int)(pos - addr);
            return rh;
        } catch (Exception e) {
            return null;
        }
    }

    /** Compute byte offset of the 'deleted' flag from record start address */
    private static int computeDeletedByteOffset(long addr) {
        long pos = addr;
        pos += 8; // expireTime
        pos += 16; // id
        int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2;
        pos += nsLen;
        int contentLen = UnsafeOps.getInt(pos); pos += 4;
        pos += contentLen;
        int metaLen = UnsafeOps.getInt(pos); pos += 4;
        pos += metaLen;
        int vectorLen = UnsafeOps.getInt(pos); pos += 4;
        pos += (long) vectorLen * 4;
        return (int)(pos - addr);
    }

    /** Read a full MemoryEntry from a file offset */
    private MemoryEntry readRecord(long fileOffset) {
        long addr = allocator.getAddressForOffset(fileOffset);
        RecordHeader rh = parseRecordHeader(addr);
        if (rh == null || rh.deleted) return null;
        return parseRecordFull(addr, rh);
    }

    /** Read full MemoryEntry given address and pre-parsed header */
    private static MemoryEntry parseRecordFull(long addr, RecordHeader rh) {
        long pos = addr;
        long expireTime = UnsafeOps.getLong(pos); pos += 8;
        long msb = UnsafeOps.getLong(pos); pos += 8;
        long lsb = UnsafeOps.getLong(pos); pos += 8;
        String id = new UUID(msb, lsb).toString();
        int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2;
        byte[] nsBytes = new byte[nsLen];
        UnsafeOps.copyToArray(pos, nsBytes, 0, nsLen); pos += nsLen;
        String namespace = new String(nsBytes, StandardCharsets.UTF_8);
        int contentLen = UnsafeOps.getInt(pos); pos += 4;
        byte[] contentBytes = new byte[contentLen];
        UnsafeOps.copyToArray(pos, contentBytes, 0, contentLen); pos += contentLen;
        int metaLen = UnsafeOps.getInt(pos); pos += 4;
        byte[] metaBytes = new byte[metaLen];
        UnsafeOps.copyToArray(pos, metaBytes, 0, metaLen); pos += metaLen;
        Map<String, String> metadata = decodeMetadata(metaBytes);
        int vectorLen = UnsafeOps.getInt(pos); pos += 4;
        float[] vector = null;
        if (vectorLen > 0) {
            vector = new float[vectorLen];
            for (int i = 0; i < vectorLen; i++) {
                vector[i] = UnsafeOps.getFloat(pos); pos += 4;
            }
        }
        boolean deleted = UnsafeOps.getByte(pos) != 0; pos += 1;
        long createdAt = UnsafeOps.getLong(pos);
        if (deleted) return null;
        String content = new String(contentBytes, StandardCharsets.UTF_8);
        return new MemoryEntry(id, content, metadata, namespace, createdAt, expireTime, vector);
    }

    /** Write a new record (generates new UUID) */
    private long writeRecordToAllocator(MmapAllocator alloc, String id, String content,
                                        Map<String, String> metadata, String namespace,
                                        long createdAt, long expireTime,
                                        float[] vector, boolean deleted) {
        byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] metaBytes = encodeMetadata(metadata);
        int vectorLen = (vector != null) ? vector.length : 0;

        int size = 8 + 16 + 2 + nsBytes.length + 4 + contentBytes.length
                + 4 + metaBytes.length + 4 + vectorLen * 4 + 1 + 8;

        long addr = alloc.allocate(size);
        long pos = addr;
        UnsafeOps.putLong(pos, expireTime); pos += 8;
        UUID uuid = UUID.fromString(id);
        UnsafeOps.putLong(pos, uuid.getMostSignificantBits()); pos += 8;
        UnsafeOps.putLong(pos, uuid.getLeastSignificantBits()); pos += 8;
        UnsafeOps.putShort(pos, (short) nsBytes.length); pos += 2;
        UnsafeOps.copyFromArray(nsBytes, 0, pos, nsBytes.length); pos += nsBytes.length;
        UnsafeOps.putInt(pos, contentBytes.length); pos += 4;
        UnsafeOps.copyFromArray(contentBytes, 0, pos, contentBytes.length); pos += contentBytes.length;
        UnsafeOps.putInt(pos, metaBytes.length); pos += 4;
        UnsafeOps.copyFromArray(metaBytes, 0, pos, metaBytes.length); pos += metaBytes.length;
        UnsafeOps.putInt(pos, vectorLen); pos += 4;
        for (int i = 0; i < vectorLen; i++) {
            UnsafeOps.putFloat(pos, vector[i]); pos += 4;
        }
        UnsafeOps.putByte(pos, deleted ? (byte) 1 : (byte) 0); pos += 1;
        UnsafeOps.putLong(pos, createdAt);
        return addr;
    }

    private void growTablesIfNeeded(int ordinal) {
        if (ordinal >= offsetTable.length) {
            int newSize = Math.max(ordinal + 1, offsetTable.length * 2);
            offsetTable = Arrays.copyOf(offsetTable, newSize);
            vectorOffsetTable = Arrays.copyOf(vectorOffsetTable, newSize);
        }
    }

    private long computeVectorOffset(long recordAddr, String namespace, String content, Map<String, String> metadata) {
        byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] metaBytes = encodeMetadata(metadata);
        long offset = 8 + 16 + 2 + nsBytes.length + 4 + contentBytes.length + 4 + metaBytes.length + 4;
        return allocator.getFileOffsetForAddress(recordAddr) + offset;
    }

    // ===== Metadata encoding =====

    private static byte[] encodeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new byte[]{0, 0}; // pair_count = 0
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeShort(metadata.size());
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(k.length);
                dos.write(k);
                dos.writeShort(v.length);
                dos.write(v);
            }
            dos.flush();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return baos.toByteArray();
    }

    private static Map<String, String> decodeMetadata(byte[] data) {
        if (data == null || data.length < 2) return Collections.emptyMap();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int count = dis.readShort() & 0xFFFF;
            if (count == 0) return Collections.emptyMap();
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                int kLen = dis.readShort() & 0xFFFF;
                byte[] k = new byte[kLen];
                dis.readFully(k);
                int vLen = dis.readShort() & 0xFFFF;
                byte[] v = new byte[vLen];
                dis.readFully(v);
                map.put(new String(k, StandardCharsets.UTF_8), new String(v, StandardCharsets.UTF_8));
            }
            return map;
        } catch (IOException ex) {
            return Collections.emptyMap();
        }
    }

    // ===== Scan helpers =====

    private static class RebuildResult {
        OrdinalRegistry ordinalRegistry;
        long[] offsetTable;
        long[] vectorOffsetTable;
        BM25Index bm25Index;
        JVectorIndex hnswIndex;
    }

    private static OrdinalRegistry loadOrdinalRegistry(MmapAllocator alloc, long fileOffset) {
        try {
            long addr = alloc.getAddressForOffset(fileOffset);
            int size = UnsafeOps.getInt(addr);
            byte[] data = new byte[size];
            UnsafeOps.copyToArray(addr + 4, data, 0, size);
            return OrdinalRegistry.deserialize(data);
        } catch (Exception e) {
            return new OrdinalRegistry();
        }
    }

    private static RebuildResult rebuildOffsetTables(MmapAllocator alloc, OrdinalRegistry registry) {
        RebuildResult result = new RebuildResult();
        result.ordinalRegistry = registry;
        int capacity = registry.capacity();
        result.offsetTable = new long[Math.max(capacity, 1024)];
        result.vectorOffsetTable = new long[Math.max(capacity, 1024)];

        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long end = alloc.usedMemory();
        while (scanOffset < end) {
            long addr = alloc.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                int ordinal = registry.getOrdinal(rh.id);
                if (ordinal >= 0) {
                    if (ordinal >= result.offsetTable.length) {
                        result.offsetTable = Arrays.copyOf(result.offsetTable, ordinal + 1);
                        result.vectorOffsetTable = Arrays.copyOf(result.vectorOffsetTable, ordinal + 1);
                    }
                    result.offsetTable[ordinal] = scanOffset;
                    MemoryEntry entry = parseRecordFull(addr, rh);
                    if (entry != null && entry.getVector() != null) {
                        result.vectorOffsetTable[ordinal] = scanOffset + computeVectorOffsetInRecord(entry);
                    }
                }
            }
            scanOffset += rh.totalSize;
        }
        return result;
    }

    private static long computeVectorOffsetInRecord(MemoryEntry entry) {
        byte[] nsBytes = entry.getNamespace().getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = entry.getContent().getBytes(StandardCharsets.UTF_8);
        byte[] metaBytes = encodeMetadata(entry.getMetadata());
        return 8 + 16 + 2 + nsBytes.length + 4 + contentBytes.length + 4 + metaBytes.length + 4;
    }

    /**
     * If the provider's dimension is unknown (0), trigger auto-detection by
     * making a minimal embed() call so that getDimension() returns a valid value.
     */
    private static void ensureDimension(EmbeddingProvider provider) {
        if (provider.getDimension() == 0) {
            provider.embed("a");
        }
    }

    private static RebuildResult rebuildFromScan(MmapAllocator alloc, EmbeddingProvider provider, SearchMode mode) {
        RebuildResult result = new RebuildResult();
        result.ordinalRegistry = new OrdinalRegistry();
        result.offsetTable = new long[1024];
        result.vectorOffsetTable = new long[1024];
        result.bm25Index = new BM25Index(1.2f, 0.75f);
        result.hnswIndex = null;

        if (mode != SearchMode.KEYWORD_ONLY && provider != null) {
            ensureDimension(provider);
            result.hnswIndex = new JVectorIndex(provider.getDimension(), result.vectorOffsetTable, alloc);
        }

        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long end = alloc.usedMemory();
        while (scanOffset < end) {
            long addr = alloc.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                MemoryEntry entry = parseRecordFull(addr, rh);
                if (entry != null && !entry.isExpired()) {
                    int ordinal = result.ordinalRegistry.register(entry.getId());
                    if (ordinal >= result.offsetTable.length) {
                        result.offsetTable = Arrays.copyOf(result.offsetTable, ordinal + 1);
                        result.vectorOffsetTable = Arrays.copyOf(result.vectorOffsetTable, ordinal + 1);
                    }
                    result.offsetTable[ordinal] = scanOffset;
                    if (entry.getVector() != null) {
                        result.vectorOffsetTable[ordinal] = scanOffset + computeVectorOffsetInRecord(entry);
                    }
                    result.bm25Index.addDocument(ordinal, entry.getContent());
                    if (result.hnswIndex != null && entry.getVector() != null) {
                        result.hnswIndex.add(ordinal, entry.getVector());
                    }
                }
            }
            scanOffset += rh.totalSize;
        }
        return result;
    }

    private static BM25Index loadBm25(MmapAllocator alloc, long baseAddr) {
        long bm25FileOffset = MmapFileHeader.getBm25IndexOffset(baseAddr);
        if (bm25FileOffset == 0) {
            return new BM25Index(1.2f, 0.75f);
        }
        try {
            long bm25Addr = alloc.getAddressForOffset(bm25FileOffset);
            int bm25Size = UnsafeOps.getInt(bm25Addr);
            byte[] bm25Data = new byte[bm25Size];
            UnsafeOps.copyToArray(bm25Addr + 4, bm25Data, 0, bm25Size);
            return BM25Index.deserialize(bm25Data, 1.2f, 0.75f);
        } catch (Exception e) {
            return new BM25Index(1.2f, 0.75f);
        }
    }

    private static JVectorIndex loadHnsw(MmapAllocator alloc, long baseAddr,
                                             String hnswPath, EmbeddingProvider provider,
                                             SearchMode mode, long[] vectorOffsetTable) {
        if (mode == SearchMode.KEYWORD_ONLY || provider == null) return null;
        File hnswFile = new File(hnswPath);
        if (!hnswFile.exists()) return null;
        long storedGen = MmapFileHeader.getHnswGeneration(baseAddr);
        if (storedGen == 0) return null;
        try (FileInputStream fis = new FileInputStream(hnswFile)) {
            return JVectorIndex.deserialize(fis, provider.getDimension(), vectorOffsetTable, alloc);
        } catch (IOException e) {
            return null;
        }
    }

    // ===== Persistence =====

    private void saveIndexes() {
        long baseAddr = allocator.getBaseAddress();

        // Save OrdinalRegistry to mmap
        long ordinalRegistryOffset = 0;
        try {
            byte[] regData = ordinalRegistry.serialize();
            int regSize = regData.length;
            long regAddr = allocator.allocate(4 + regSize);
            UnsafeOps.putInt(regAddr, regSize);
            UnsafeOps.copyFromArray(regData, 0, regAddr + 4, regSize);
            ordinalRegistryOffset = allocator.getFileOffsetForAddress(regAddr);
        } catch (IOException e) {
            // non-fatal
        }

        // Save BM25 to mmap
        long bm25FileOffset = 0;
        try {
            byte[] bm25Data = bm25Index.serialize();
            int bm25Size = bm25Data.length;
            long bm25Addr = allocator.allocate(4 + bm25Size);
            UnsafeOps.putInt(bm25Addr, bm25Size);
            UnsafeOps.copyFromArray(bm25Data, 0, bm25Addr + 4, bm25Size);
            bm25FileOffset = allocator.getFileOffsetForAddress(bm25Addr);
        } catch (IOException e) {
            // non-fatal
        }

        // Save HNSW to .hnsw file
        long generation = System.currentTimeMillis();
        if (vectorIndex != null) {
            String hnswPath = basePath + ".hnsw";
            try (FileOutputStream fos = new FileOutputStream(hnswPath)) {
                vectorIndex.serialize(fos);
            } catch (IOException e) {
                generation = 0;
            }
        }

        // Write main header
        MmapFileHeader header = allocator.readHeader();
        header.setCurrentOffset(allocator.usedMemory());
        header.setEntryCount(ordinalRegistry.capacity());
        header.write(baseAddr);

        // Write extension fields AFTER header.write()
        if (ordinalRegistryOffset != 0) {
            MmapFileHeader.setOrdinalRegistryOffset(baseAddr, ordinalRegistryOffset);
        }
        if (bm25FileOffset != 0) {
            MmapFileHeader.setBm25IndexOffset(baseAddr, bm25FileOffset);
        }
        if (vectorIndex != null && generation != 0) {
            MmapFileHeader.setHnswGeneration(baseAddr, generation);
        }

        allocator.flush();
    }

    // ===== Search helpers =====

    private List<MemoryResult> vectorSearch(String query, int candidates, SearchOptions options) {
        if (vectorIndex == null || embeddingProvider == null) return Collections.emptyList();
        float[] qv = embeddingProvider.embed(query);
        List<VectorIndex.ScoredOrdinal> raw = vectorIndex.searchByOrdinal(qv, candidates);
        List<MemoryResult> results = new ArrayList<>();
        for (VectorIndex.ScoredOrdinal s : raw) {
            String id = ordinalRegistry.getId(s.ordinal);
            if (id != null) {
                MemoryEntry e = get(id);
                if (e != null && !e.isExpired()) {
                    results.add(toResult(e, s.score));
                }
            }
        }
        return results;
    }

    private List<MemoryResult> keywordSearch(String query, int candidates, SearchOptions options) {
        List<BM25Index.ScoredOrdinal> raw = bm25Index.search(query, candidates);
        List<MemoryResult> results = new ArrayList<>();
        for (BM25Index.ScoredOrdinal s : raw) {
            String id = ordinalRegistry.getId(s.ordinal);
            if (id != null) {
                MemoryEntry e = get(id);
                if (e != null && !e.isExpired()) {
                    results.add(toResult(e, s.score));
                }
            }
        }
        return results;
    }

    private List<MemoryResult> hybridSearch(String query, int candidates, SearchOptions options) {
        // RRF fusion
        List<MemoryResult> vecResults = vectorSearch(query, candidates, options);
        List<MemoryResult> kwResults = keywordSearch(query, candidates, options);

        int C = (options != null) ? options.getRrfConstant() : 60;
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, MemoryResult> byId = new LinkedHashMap<>();

        for (int rank = 0; rank < vecResults.size(); rank++) {
            MemoryResult r = vecResults.get(rank);
            rrfScores.merge(r.getId(), 1.0 / (C + rank + 1), Double::sum);
            byId.put(r.getId(), r);
        }
        for (int rank = 0; rank < kwResults.size(); rank++) {
            MemoryResult r = kwResults.get(rank);
            rrfScores.merge(r.getId(), 1.0 / (C + rank + 1), Double::sum);
            byId.putIfAbsent(r.getId(), r);
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<MemoryResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> e : sorted) {
            MemoryResult orig = byId.get(e.getKey());
            results.add(new MemoryResult(orig.getId(), orig.getContent(), orig.getMetadata(),
                    orig.getNamespace(), e.getValue().floatValue(),
                    orig.getCreatedAt(), orig.getExpireTime()));
        }
        return results;
    }

    private List<MemoryResult> applyFilters(List<MemoryResult> results, SearchOptions options) {
        if (options == null) return results;
        String ns = options.getNamespace();
        Map<String, String> filters = options.getFilters();
        if (ns == null && (filters == null || filters.isEmpty())) return results;

        List<MemoryResult> filtered = new ArrayList<>();
        for (MemoryResult r : results) {
            if (ns != null && !ns.equals(r.getNamespace())) continue;
            if (filters != null && !filters.isEmpty()) {
                boolean match = true;
                for (Map.Entry<String, String> f : filters.entrySet()) {
                    Map<String, String> meta = r.getMetadata();
                    if (meta == null || !f.getValue().equals(meta.get(f.getKey()))) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;
            }
            filtered.add(r);
        }
        return filtered;
    }

    private static MemoryResult toResult(MemoryEntry e, float score) {
        return new MemoryResult(e.getId(), e.getContent(), e.getMetadata(),
                e.getNamespace(), score, e.getCreatedAt(), e.getExpireTime());
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("RogueMemory is closed");
    }
}
