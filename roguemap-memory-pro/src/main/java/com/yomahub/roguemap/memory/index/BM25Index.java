package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.util.Tokenizer;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 倒排索引实现
 *
 * 支持增量 add/delete，serialize()/deserialize() 用于持久化。
 * 线程安全（ReentrantReadWriteLock 保护写操作）。
 */
public class BM25Index {

    public static class ScoredId {
        public final String id;
        public final float score;
        public ScoredId(String id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    private final float k1;
    private final float b;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // term → { docId → tf }
    private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
    // docId → 文档词项数量（用于计算文档长度）
    private final Map<String, Integer> docLengths = new HashMap<>();
    // term → 包含该词项的文档数（df）
    private final Map<String, Integer> docFreqs = new HashMap<>();

    public BM25Index(float k1, float b) {
        this.k1 = k1;
        this.b = b;
    }

    public void add(String docId, String content) {
        List<String> tokens = Tokenizer.tokenize(content);
        if (tokens.isEmpty()) return;

        lock.writeLock().lock();
        try {
            // 先删除旧数据（支持 update）
            removeFromIndex(docId);

            // 统计词频
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }

            // 更新倒排索引
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                invertedIndex.computeIfAbsent(term, k -> new HashMap<>()).put(docId, e.getValue());
                docFreqs.merge(term, 1, Integer::sum);
            }

            docLengths.put(docId, tokens.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String docId) {
        lock.writeLock().lock();
        try {
            removeFromIndex(docId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void removeFromIndex(String docId) {
        if (!docLengths.containsKey(docId)) return;

        for (Map.Entry<String, Map<String, Integer>> e : invertedIndex.entrySet()) {
            if (e.getValue().remove(docId) != null) {
                docFreqs.merge(e.getKey(), -1, Integer::sum);
            }
        }
        docLengths.remove(docId);
    }

    /**
     * BM25 搜索，返回按分数降序排列的 top-k 结果
     */
    public List<ScoredId> search(List<String> queryTokens, int topK) {
        lock.readLock().lock();
        try {
            int N = docLengths.size();
            if (N == 0 || queryTokens.isEmpty()) return Collections.emptyList();

            double avgDl = docLengths.values().stream()
                .mapToInt(Integer::intValue).average().orElse(1.0);

            Map<String, Double> scores = new HashMap<>();

            for (String term : queryTokens) {
                Map<String, Integer> postings = invertedIndex.get(term);
                if (postings == null) continue;

                int df = docFreqs.getOrDefault(term, 0);
                double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

                for (Map.Entry<String, Integer> posting : postings.entrySet()) {
                    String docId = posting.getKey();
                    int tf = posting.getValue();
                    int dl = docLengths.getOrDefault(docId, 1);

                    double tfNorm = (tf * (k1 + 1))
                        / (tf + k1 * (1 - b + b * dl / avgDl));
                    scores.merge(docId, idf * tfNorm, Double::sum);
                }
            }

            // 取 top-k
            List<ScoredId> result = new ArrayList<>(scores.size());
            for (Map.Entry<String, Double> e : scores.entrySet()) {
                result.add(new ScoredId(e.getKey(), e.getValue().floatValue()));
            }
            result.sort((a, x) -> Float.compare(x.score, a.score));
            return result.subList(0, Math.min(topK, result.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 序列化为字节数组（用于持久化到 mmap 文件尾） */
    public byte[] serialize() throws IOException {
        lock.readLock().lock();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(invertedIndex);
            oos.writeObject(docLengths);
            oos.writeObject(docFreqs);
            oos.flush();
            return baos.toByteArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 从字节数组反序列化 */
    @SuppressWarnings("unchecked")
    public static BM25Index deserialize(byte[] data, float k1, float b) throws IOException, ClassNotFoundException {
        BM25Index idx = new BM25Index(k1, b);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            idx.invertedIndex.putAll((Map<String, Map<String, Integer>>) ois.readObject());
            idx.docLengths.putAll((Map<String, Integer>) ois.readObject());
            idx.docFreqs.putAll((Map<String, Integer>) ois.readObject());
        }
        return idx;
    }
}
