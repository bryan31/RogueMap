package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HnswVectorIndexTest {

    private Path tempDir;
    private MmapAllocator allocator;
    private long[] vectorFileOffsets;
    private static final int DIMENSION = 3;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("hnsw-test");
        Path dataFile = tempDir.resolve("vectors.dat");
        allocator = new MmapAllocator(dataFile.toString(), 1024 * 1024, false);
        vectorFileOffsets = new long[10];
    }

    @AfterEach
    void tearDown() throws IOException {
        if (allocator != null) {
            allocator.close();
        }
        if (tempDir != null) {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) {}
                });
        }
    }

    @Test
    void addAndSearch() {
        HnswVectorIndex index = new HnswVectorIndex(DIMENSION, vectorFileOffsets, allocator);

        // 写入3个向量到mmap
        float[] v0 = {1f, 0f, 0f};
        float[] v1 = {0f, 1f, 0f};
        float[] v2 = {0f, 0f, 1f};

        long addr0 = allocator.allocate(12);
        long addr1 = allocator.allocate(12);
        long addr2 = allocator.allocate(12);

        UnsafeOps.putFloatArray(addr0, v0);
        UnsafeOps.putFloatArray(addr1, v1);
        UnsafeOps.putFloatArray(addr2, v2);

        vectorFileOffsets[0] = allocator.getFileOffsetForAddress(addr0);
        vectorFileOffsets[1] = allocator.getFileOffsetForAddress(addr1);
        vectorFileOffsets[2] = allocator.getFileOffsetForAddress(addr2);

        index.add(0, v0);
        index.add(1, v1);
        index.add(2, v2);

        // 搜索最接近 [1,0,0] 的向量
        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(new float[]{1f, 0f, 0f}, 2);

        assertEquals(2, results.size());
        assertEquals(0, results.get(0).ordinal);
        assertTrue(results.get(0).score > 0.9f);
    }

    @Test
    void markDeleted() {
        HnswVectorIndex index = new HnswVectorIndex(DIMENSION, vectorFileOffsets, allocator);

        float[] v0 = {1f, 0f, 0f};
        float[] v1 = {0f, 1f, 0f};

        long addr0 = allocator.allocate(12);
        long addr1 = allocator.allocate(12);

        UnsafeOps.putFloatArray(addr0, v0);
        UnsafeOps.putFloatArray(addr1, v1);

        vectorFileOffsets[0] = allocator.getFileOffsetForAddress(addr0);
        vectorFileOffsets[1] = allocator.getFileOffsetForAddress(addr1);

        index.add(0, v0);
        index.add(1, v1);

        index.markDeleted(0);

        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(new float[]{1f, 0f, 0f}, 2);

        assertEquals(1, results.size());
        assertEquals(1, results.get(0).ordinal);
    }

    @Test
    void emptyIndex() {
        HnswVectorIndex index = new HnswVectorIndex(DIMENSION, vectorFileOffsets, allocator);

        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(new float[]{1f, 0f, 0f}, 10);

        assertTrue(results.isEmpty());
    }

    @Test
    void serializeDeserialize() throws IOException {
        HnswVectorIndex index = new HnswVectorIndex(DIMENSION, vectorFileOffsets, allocator);

        float[] v0 = {1f, 0f, 0f};
        float[] v1 = {0f, 1f, 0f};

        long addr0 = allocator.allocate(12);
        long addr1 = allocator.allocate(12);

        UnsafeOps.putFloatArray(addr0, v0);
        UnsafeOps.putFloatArray(addr1, v1);

        vectorFileOffsets[0] = allocator.getFileOffsetForAddress(addr0);
        vectorFileOffsets[1] = allocator.getFileOffsetForAddress(addr1);

        index.add(0, v0);
        index.add(1, v1);

        // 序列化
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        index.serialize(baos);
        byte[] data = baos.toByteArray();

        // 反序列化
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        HnswVectorIndex restored = HnswVectorIndex.deserialize(bais, DIMENSION, vectorFileOffsets, allocator);

        // 验证搜索仍然有效
        List<VectorIndex.ScoredOrdinal> results = restored.searchByOrdinal(new float[]{1f, 0f, 0f}, 2);

        assertEquals(2, results.size());
        assertEquals(0, results.get(0).ordinal);
    }

    @Test
    void lazyVectorRead() {
        HnswVectorIndex index = new HnswVectorIndex(DIMENSION, vectorFileOffsets, allocator);

        float[] v0 = {1f, 2f, 3f};

        long addr0 = allocator.allocate(12);
        UnsafeOps.putFloatArray(addr0, v0);

        vectorFileOffsets[0] = allocator.getFileOffsetForAddress(addr0);

        index.add(0, v0);

        // 搜索会触发 vector() 调用
        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(new float[]{1f, 2f, 3f}, 1);

        assertEquals(1, results.size());
        assertEquals(0, results.get(0).ordinal);

        // 验证从mmap读取的向量值正确
        float[] readBack = UnsafeOps.getFloatArray(addr0, DIMENSION);
        assertArrayEquals(v0, readBack, 0.001f);
    }
}
