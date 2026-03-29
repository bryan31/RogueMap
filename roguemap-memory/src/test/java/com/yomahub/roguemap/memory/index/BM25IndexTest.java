package com.yomahub.roguemap.memory.index;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BM25IndexTest {

    @Test
    public void addAndSearch() {
        BM25Index index = new BM25Index(1.5f, 0.75f);
        index.addDocument(1, "hello world");
        index.addDocument(2, "hello java");

        List<ScoredOrdinal> results = index.search("hello", 10);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.ordinal == 1));
        assertTrue(results.stream().anyMatch(r -> r.ordinal == 2));
    }

    @Test
    public void removeDocument() {
        BM25Index index = new BM25Index(1.5f, 0.75f);
        index.addDocument(1, "hello world");
        index.addDocument(2, "hello java");
        index.removeDocument(1);

        List<ScoredOrdinal> results = index.search("hello", 10);
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).ordinal);
    }

    @Test
    public void emptyQuery() {
        BM25Index index = new BM25Index(1.5f, 0.75f);
        index.addDocument(1, "hello world");

        List<ScoredOrdinal> results = index.search("", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    public void unknownTerm() {
        BM25Index index = new BM25Index(1.5f, 0.75f);
        index.addDocument(1, "hello world");

        List<ScoredOrdinal> results = index.search("unknown", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    public void serializeDeserialize() throws Exception {
        BM25Index index = new BM25Index(1.5f, 0.75f);
        index.addDocument(1, "hello world");
        index.addDocument(2, "hello java");

        byte[] data = index.serialize();
        BM25Index restored = BM25Index.deserialize(data, 1.5f, 0.75f);

        List<ScoredOrdinal> results = restored.search("hello", 10);
        assertEquals(2, results.size());
    }

    @Test
    public void tombstonesSkipped() {
        BM25Index index = new BM25Index(1.5f, 0.75f);
        index.addDocument(1, "hello world");
        index.removeDocument(1);
        index.addDocument(2, "hello world");

        List<ScoredOrdinal> results = index.search("hello", 10);
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).ordinal);
    }
}
