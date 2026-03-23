package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.HnswVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HnswVectorIndexTest {

    static float[] vec(float... v) { return v; }

    @Test
    void addAndSearch() {
        HnswVectorIndex index = new HnswVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));
        index.add("c", vec(0f, 0f, 1f));

        // 查询与 a 最近的向量
        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 1);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id);
    }

    @Test
    void markDeletedExcludesFromResults() {
        HnswVectorIndex index = new HnswVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0.99f, 0.01f, 0f));  // 非常接近 a
        index.markDeleted("a");

        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 2);
        assertTrue(results.stream().noneMatch(r -> r.id.equals("a")));
        assertTrue(results.stream().anyMatch(r -> r.id.equals("b")));
    }

    @Test
    void serializeAndDeserialize() throws IOException {
        HnswVectorIndex index = new HnswVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        index.serialize(baos);

        HnswVectorIndex restored = HnswVectorIndex.load(
            new ByteArrayInputStream(baos.toByteArray()), 3);

        List<VectorIndex.ScoredId> results = restored.search(vec(1f, 0f, 0f), 1);
        assertEquals("a", results.get(0).id);
    }
}
