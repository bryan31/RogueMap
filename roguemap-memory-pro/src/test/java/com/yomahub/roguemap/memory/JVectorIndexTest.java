package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.JVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JVectorIndexTest {

    static float[] vec(float... v) { return v; }

    @Test
    void addAndSearch() {
        JVectorIndex index = new JVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));
        index.add("c", vec(0f, 0f, 1f));
        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 1);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id);
    }

    @Test
    void markDeletedExcludesFromResults() {
        JVectorIndex index = new JVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0.99f, 0.01f, 0f));
        index.markDeleted("a");
        List<VectorIndex.ScoredId> results = index.search(vec(1f, 0f, 0f), 2);
        assertTrue(results.stream().noneMatch(r -> r.id.equals("a")));
    }

    @Test
    void serializeAndDeserialize() throws IOException {
        JVectorIndex index = new JVectorIndex(3, 1000);
        index.add("a", vec(1f, 0f, 0f));
        index.add("b", vec(0f, 1f, 0f));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        index.serialize(baos);
        JVectorIndex restored = JVectorIndex.load(
            new ByteArrayInputStream(baos.toByteArray()), 3);
        List<VectorIndex.ScoredId> results = restored.search(vec(1f, 0f, 0f), 1);
        assertEquals("a", results.get(0).id);
    }
}
