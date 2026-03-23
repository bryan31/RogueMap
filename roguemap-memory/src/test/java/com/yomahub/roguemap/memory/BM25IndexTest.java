package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.BM25Index;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BM25IndexTest {

    private BM25Index index;

    @BeforeEach
    void setUp() {
        index = new BM25Index(1.2f, 0.75f);
    }

    @Test
    void addAndSearchBasic() {
        index.add("doc1", "我有一件红衣服");
        index.add("doc2", "今天天气很好");
        index.add("doc3", "我喜欢穿衣服");

        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("红衣", "衣服"), 5);

        assertFalse(results.isEmpty());
        // doc1 和 doc3 都包含"衣服"相关 bigram，应排在前面
        assertEquals("doc1", results.get(0).id);
    }

    @Test
    void deleteRemovesFromResults() {
        index.add("doc1", "我有一件红衣服");
        index.add("doc2", "红色的衣服真好看");
        index.delete("doc1");

        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("红衣", "衣服"), 5);

        assertTrue(results.stream().noneMatch(r -> r.id.equals("doc1")));
        assertTrue(results.stream().anyMatch(r -> r.id.equals("doc2")));
    }

    @Test
    void searchReturnsEmptyWhenNoMatch() {
        index.add("doc1", "今天天气很好");
        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("red", "dress"), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void emptyIndexReturnsEmpty() {
        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("test"), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void serializeAndDeserialize() throws Exception {
        index.add("doc1", "我有一件红衣服");
        index.add("doc2", "今天天气很好");

        byte[] serialized = index.serialize();
        BM25Index restored = BM25Index.deserialize(serialized, 1.2f, 0.75f);

        List<BM25Index.ScoredId> results = restored.search(
            Arrays.asList("红衣", "衣服"), 5);
        assertFalse(results.isEmpty());
        assertEquals("doc1", results.get(0).id);
    }

    @Test
    void topKRespected() {
        for (int i = 0; i < 10; i++) {
            index.add("doc" + i, "衣服 红色 好看 " + i);
        }
        List<BM25Index.ScoredId> results = index.search(
            Arrays.asList("衣服"), 3);
        assertEquals(3, results.size());
    }
}
