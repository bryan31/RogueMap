package com.yomahub.roguemap.memory.index;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HnswVectorIndex implements VectorIndex {

    private final int dimension;
    private HnswIndex<String, float[], VectorItem, Float> hnswIndex;
    // tombstone 集合，markDeleted 后加入此集合，search 时后过滤
    private final Set<String> deletedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public HnswVectorIndex(int dimension, int maxElements) {
        this.dimension = dimension;
        this.hnswIndex = HnswIndex
            .newBuilder(dimension, DistanceFunctions.FLOAT_COSINE_DISTANCE, maxElements)
            .withM(16)
            .withEfConstruction(200)
            .withEf(50)
            .build();
    }

    // 私有构造，用于反序列化
    private HnswVectorIndex(int dimension,
                             HnswIndex<String, float[], VectorItem, Float> hnswIndex,
                             Set<String> deletedIds) {
        this.dimension = dimension;
        this.hnswIndex = hnswIndex;
        this.deletedIds.addAll(deletedIds);
    }

    @Override
    public void add(String id, float[] vector) {
        hnswIndex.add(new VectorItem(id, vector));
    }

    @Override
    public void markDeleted(String id) {
        deletedIds.add(id);
    }

    @Override
    public List<ScoredId> search(float[] queryVector, int topK) {
        // 多取一些候选，再过滤 tombstone
        int candidates = topK + deletedIds.size() + 10;
        List<SearchResult<VectorItem, Float>> raw = hnswIndex.findNearest(queryVector, candidates);

        List<ScoredId> result = new ArrayList<>();
        for (SearchResult<VectorItem, Float> r : raw) {
            if (!deletedIds.contains(r.item().id())) {
                // jelmerk 返回的 distance（越小越近），转换为 score（越大越好）
                result.add(new ScoredId(r.item().id(), 1f - r.distance()));
                if (result.size() >= topK) break;
            }
        }
        return result;
    }

    @Override
    public void serialize(OutputStream out) throws IOException {
        // 格式：[generation: 8B][deletedCount: 4B][id_len: 2B][id bytes]... [hnswData]
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(0L);  // generation 由 RogueMemory 在文件头管理，此处写占位 0
        dos.writeInt(deletedIds.size());
        for (String id : deletedIds) {
            byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(idBytes.length);
            dos.write(idBytes);
        }
        dos.flush();
        // hnswIndex.save() 直接写到底层 OutputStream
        hnswIndex.save(out);
    }

    public static HnswVectorIndex load(InputStream in, int dimension) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readLong();   // 跳过 generation 占位
        int deletedCount = dis.readInt();
        Set<String> deletedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int i = 0; i < deletedCount; i++) {
            int len = dis.readShort() & 0xFFFF;
            byte[] idBytes = new byte[len];
            dis.readFully(idBytes);
            deletedSet.add(new String(idBytes, StandardCharsets.UTF_8));
        }
        // 流位置恰好在 hnswlib 数据开始处
        HnswIndex<String, float[], VectorItem, Float> loaded = HnswIndex.load(in);
        return new HnswVectorIndex(dimension, loaded, deletedSet);
    }

    @Override
    public void close() {
        // jelmerk 不需要显式关闭
    }

    /** jelmerk Item 实现 */
    static class VectorItem implements Item<String, float[]>, Serializable {
        private static final long serialVersionUID = 1L;
        private final String id;
        private final float[] vector;

        VectorItem(String id, float[] vector) {
            this.id = id;
            this.vector = vector;
        }

        @Override public String id() { return id; }
        @Override public float[] vector() { return vector; }
        @Override public int dimensions() { return vector.length; }
    }
}
