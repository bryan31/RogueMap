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

class JVectorIndexTest {
    private Path tempDir;
    private MmapAllocator allocator;
    private long[] vectorOffsets;
    private int dimension = 3;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jvector-test");
        Path dataFile = tempDir.resolve("vectors.dat");
        allocator = new MmapAllocator(dataFile.toString(), 1024 * 1024, false);
        vectorOffsets = new long[10];
    }

    @AfterEach
    void tearDown() throws IOException {
        if (allocator != null) allocator.close();
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
        JVectorIndex index = new JVectorIndex(dimension, vectorOffsets, allocator);

        // Add 3 vectors
        float[] v0 = {1f, 0f, 0f};
        float[] v1 = {0f, 1f, 0f};
        float[] v2 = {0f, 0f, 1f};

        vectorOffsets[0] = writeVector(v0);
        vectorOffsets[1] = writeVector(v1);
        vectorOffsets[2] = writeVector(v2);

        index.add(0, v0);
        index.add(1, v1);
        index.add(2, v2);

        // Search for v0
        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(v0, 2);
        assertEquals(2, results.size());
        assertEquals(0, results.get(0).ordinal);
        assertTrue(results.get(0).score > 0.99f);
    }

    @Test
    void markDeleted() {
        JVectorIndex index = new JVectorIndex(dimension, vectorOffsets, allocator);

        float[] v0 = {1f, 0f, 0f};
        float[] v1 = {0f, 1f, 0f};

        vectorOffsets[0] = writeVector(v0);
        vectorOffsets[1] = writeVector(v1);

        index.add(0, v0);
        index.add(1, v1);

        index.markDeleted(0);

        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(v0, 2);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).ordinal);
    }

    @Test
    void emptyIndex() {
        JVectorIndex index = new JVectorIndex(dimension, vectorOffsets, allocator);
        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(new float[]{1f, 0f, 0f}, 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void serializeDeserialize() throws IOException {
        JVectorIndex index = new JVectorIndex(dimension, vectorOffsets, allocator);

        float[] v0 = {1f, 0f, 0f};
        float[] v1 = {0f, 1f, 0f};

        vectorOffsets[0] = writeVector(v0);
        vectorOffsets[1] = writeVector(v1);

        index.add(0, v0);
        index.add(1, v1);

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        index.serialize(baos);

        // Deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        JVectorIndex loaded = JVectorIndex.deserialize(bais, dimension, vectorOffsets, allocator);

        // Search should work (graph rebuilt)
        List<VectorIndex.ScoredOrdinal> results = loaded.searchByOrdinal(v0, 2);
        assertEquals(2, results.size());
        assertEquals(0, results.get(0).ordinal);
    }

    @Test
    void lazyVectorRead() {
        JVectorIndex index = new JVectorIndex(dimension, vectorOffsets, allocator);

        float[] v0 = {1f, 0f, 0f};
        vectorOffsets[0] = writeVector(v0);

        index.add(0, v0);

        // Search triggers lazy read from mmap
        List<VectorIndex.ScoredOrdinal> results = index.searchByOrdinal(v0, 1);
        assertEquals(1, results.size());

        // Verify vector was read correctly
        long addr = allocator.getAddressForOffset(vectorOffsets[0]);
        float[] read = UnsafeOps.getFloatArray(addr, dimension);
        assertArrayEquals(v0, read, 0.0001f);
    }

    private long writeVector(float[] vector) {
        long addr = allocator.allocate(vector.length * 4);
        UnsafeOps.putFloatArray(addr, vector);
        return allocator.getFileOffsetForAddress(addr);
    }
}
