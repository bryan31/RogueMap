package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.util.Tokenizer;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 倒排索引实现（基于 int ordinal）
 *
 * 支持增量 add/delete，serialize()/deserialize() 用于持久化。
 * 线程安全（ReentrantReadWriteLock 保护写操作）。
 */
public class BM25Index {

    private final float k1;
    private final float b;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // term → [ordinal, freq, ordinal, freq, ...]
    private final Map<String, int[]> postings = new HashMap<>();
    // ordinal → 文档词项数量
    private int[] docLengths = new int[16];
    private int maxOrdinal = -1;

    public BM25Index(float k1, float b) {
        this.k1 = k1;
        this.b = b;
    }

    public void addDocument(int ordinal, String content) {
        List<String> tokens = Tokenizer.tokenize(content);
        if (tokens.isEmpty()) return;

        lock.writeLock().lock();
        try {
            removeDocument(ordinal);

            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }

            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                int freq = e.getValue();
                int[] arr = postings.get(term);
                if (arr == null) {
                    arr = new int[4];
                    arr[0] = ordinal;
                    arr[1] = freq;
                    postings.put(term, arr);
                } else {
                    int len = arr.length;
                    int i = 0;
                    while (i < len && arr[i] != 0) i += 2;
                    if (i >= len) {
                        arr = Arrays.copyOf(arr, len * 2);
                    }
                    arr[i] = ordinal;
                    arr[i + 1] = freq;
                    postings.put(term, arr);
                }
            }

            if (ordinal >= docLengths.length) {
                docLengths = Arrays.copyOf(docLengths, Math.max(ordinal + 1, docLengths.length * 2));
            }
            docLengths[ordinal] = tokens.size();
            if (ordinal > maxOrdinal) maxOrdinal = ordinal;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeDocument(int ordinal) {
        lock.writeLock().lock();
        try {
            if (ordinal >= docLengths.length || docLengths[ordinal] == 0) return;

            for (int[] arr : postings.values()) {
                for (int i = 0; i < arr.length; i += 2) {
                    if (arr[i] == ordinal) {
                        arr[i] = -1;
                        break;
                    }
                }
            }
            docLengths[ordinal] = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ScoredOrdinal> search(String query, int topK) {
        List<String> queryTokens = Tokenizer.tokenize(query);
        lock.readLock().lock();
        try {
            if (queryTokens.isEmpty() || maxOrdinal < 0) return Collections.emptyList();

            int N = 0;
            long totalLen = 0;
            for (int i = 0; i <= maxOrdinal; i++) {
                if (i < docLengths.length && docLengths[i] > 0) {
                    N++;
                    totalLen += docLengths[i];
                }
            }
            if (N == 0) return Collections.emptyList();
            double avgDl = (double) totalLen / N;

            Map<Integer, Double> scores = new HashMap<>();

            for (String term : queryTokens) {
                int[] arr = postings.get(term);
                if (arr == null) continue;

                int df = 0;
                for (int i = 0; i < arr.length; i += 2) {
                    if (arr[i] >= 0) df++;
                }
                if (df == 0) continue;

                double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

                for (int i = 0; i < arr.length; i += 2) {
                    int ordinal = arr[i];
                    if (ordinal < 0) continue;
                    int tf = arr[i + 1];
                    int dl = ordinal < docLengths.length ? docLengths[ordinal] : 0;
                    if (dl == 0) continue;

                    double tfNorm = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgDl));
                    scores.merge(ordinal, idf * tfNorm, Double::sum);
                }
            }

            List<ScoredOrdinal> result = new ArrayList<>(scores.size());
            for (Map.Entry<Integer, Double> e : scores.entrySet()) {
                result.add(new ScoredOrdinal(e.getKey(), e.getValue()));
            }
            result.sort((a, x) -> Double.compare(x.score, a.score));
            return result.subList(0, Math.min(topK, result.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public byte[] serialize() throws IOException {
        lock.readLock().lock();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeInt(postings.size());
            for (Map.Entry<String, int[]> e : postings.entrySet()) {
                byte[] termBytes = e.getKey().getBytes("UTF-8");
                dos.writeShort(termBytes.length);
                dos.write(termBytes);
                int[] arr = e.getValue();
                dos.writeInt(arr.length);
                for (int v : arr) dos.writeInt(v);
            }

            dos.writeInt(docLengths.length);
            for (int v : docLengths) dos.writeInt(v);
            dos.writeInt(maxOrdinal);

            dos.flush();
            return baos.toByteArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    public static BM25Index deserialize(byte[] data, float k1, float b) throws IOException {
        BM25Index idx = new BM25Index(k1, b);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

        int termCount = dis.readInt();
        for (int i = 0; i < termCount; i++) {
            int termLen = dis.readShort();
            byte[] termBytes = new byte[termLen];
            dis.readFully(termBytes);
            String term = new String(termBytes, "UTF-8");

            int arrLen = dis.readInt();
            int[] arr = new int[arrLen];
            for (int j = 0; j < arrLen; j++) arr[j] = dis.readInt();
            idx.postings.put(term, arr);
        }

        int docLenSize = dis.readInt();
        idx.docLengths = new int[docLenSize];
        for (int i = 0; i < docLenSize; i++) idx.docLengths[i] = dis.readInt();
        idx.maxOrdinal = dis.readInt();

        return idx;
    }

    // ===== 临时兼容方法（Task 5 将移除） =====
    private final Map<String, Integer> uuidToOrdinal = new HashMap<>();
    private int nextOrdinal = 0;

    @Deprecated
    public void add(String uuid, String content) {
        int ordinal = uuidToOrdinal.computeIfAbsent(uuid, k -> nextOrdinal++);
        addDocument(ordinal, content);
    }

    @Deprecated
    public void delete(String uuid) {
        Integer ordinal = uuidToOrdinal.get(uuid);
        if (ordinal != null) {
            removeDocument(ordinal);
            uuidToOrdinal.remove(uuid);
        }
    }

    @Deprecated
    public static class ScoredId {
        public final String id;
        public final float score;
        public ScoredId(String id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    @Deprecated
    public List<ScoredId> search(List<String> queryTokens, int topK) {
        StringBuilder sb = new StringBuilder();
        for (String token : queryTokens) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(token);
        }
        List<ScoredOrdinal> ordResults = search(sb.toString(), topK);
        List<ScoredId> results = new ArrayList<>();
        Map<Integer, String> ordinalToUuid = new HashMap<>();
        for (Map.Entry<String, Integer> e : uuidToOrdinal.entrySet()) {
            ordinalToUuid.put(e.getValue(), e.getKey());
        }
        for (ScoredOrdinal so : ordResults) {
            String uuid = ordinalToUuid.get(so.ordinal);
            if (uuid != null) {
                results.add(new ScoredId(uuid, (float) so.score));
            }
        }
        return results;
    }

    public static class ScoredOrdinal {
        public final int ordinal;
        public final float score;
        public ScoredOrdinal(int ordinal, double score) {
            this.ordinal = ordinal;
            this.score = (float) score;
        }
    }
}
