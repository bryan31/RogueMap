package com.yomahub.roguemap.memory.index;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HnswVectorIndex implements VectorIndex {

    private final int dimension;
    private final long[] vectorFileOffsets;
    private final MmapAllocator allocator;
    private HnswIndex<String, float[], MmapVectorItem, Float> hnswIndex;
    private final Set<Integer> deletedOrdinals = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public HnswVectorIndex(int dimension, long[] vectorFileOffsets, MmapAllocator allocator) {
        this.dimension = dimension;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
        this.hnswIndex = HnswIndex
            .newBuilder(dimension, DistanceFunctions.FLOAT_COSINE_DISTANCE, vectorFileOffsets.length)
            .withM(16)
            .withEfConstruction(200)
            .withEf(50)
            .build();
    }

    @Deprecated
    public HnswVectorIndex(int dimension, int maxElements) {
        this.dimension = dimension;
        this.vectorFileOffsets = null;
        this.allocator = null;
        this.hnswIndex = HnswIndex
            .newBuilder(dimension, DistanceFunctions.FLOAT_COSINE_DISTANCE, maxElements)
            .withM(16)
            .withEfConstruction(200)
            .withEf(50)
            .build();
    }

    private HnswVectorIndex(int dimension, long[] vectorFileOffsets, MmapAllocator allocator,
                             HnswIndex<String, float[], MmapVectorItem, Float> hnswIndex,
                             Set<Integer> deletedOrdinals) {
        this.dimension = dimension;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
        this.hnswIndex = hnswIndex;
        this.deletedOrdinals.addAll(deletedOrdinals);
    }

    public void add(int ordinal, float[] vector) {
        if (vectorFileOffsets == null || allocator == null) {
            throw new UnsupportedOperationException("This constructor requires vectorFileOffsets and allocator");
        }
        long fileOffset = vectorFileOffsets[ordinal];
        long vectorAddress = allocator.getAddressForOffset(fileOffset);
        hnswIndex.add(new MmapVectorItem(ordinal, vectorAddress, dimension));
    }

    public void markDeleted(int ordinal) {
        deletedOrdinals.add(ordinal);
    }

    @Deprecated
    @Override
    public void add(String id, float[] vector) {
        if (vectorFileOffsets != null) {
            throw new UnsupportedOperationException("Use add(int ordinal, float[] vector)");
        }
        // 旧API：创建一个假的MmapVectorItem，vector存在堆上
        int ordinal = Integer.parseInt(id);
        hnswIndex.add(new MmapVectorItem(ordinal, 0, dimension, vector));
    }

    @Deprecated
    @Override
    public void markDeleted(String id) {
        deletedOrdinals.add(Integer.parseInt(id));
    }

    public List<ScoredOrdinal> searchByOrdinal(float[] queryVector, int topK) {
        int candidates = topK + deletedOrdinals.size() + 10;
        List<SearchResult<MmapVectorItem, Float>> raw = hnswIndex.findNearest(queryVector, candidates);

        List<ScoredOrdinal> result = new ArrayList<>();
        for (SearchResult<MmapVectorItem, Float> r : raw) {
            if (!deletedOrdinals.contains(r.item().ordinal)) {
                result.add(new ScoredOrdinal(r.item().ordinal, 1f - r.distance()));
                if (result.size() >= topK) break;
            }
        }
        return result;
    }

    @Override
    public List<ScoredId> search(float[] queryVector, int topK) {
        List<ScoredOrdinal> ordinalResults = searchByOrdinal(queryVector, topK);
        List<ScoredId> idResults = new ArrayList<>();
        for (ScoredOrdinal r : ordinalResults) {
            idResults.add(new ScoredId(String.valueOf(r.ordinal), r.score));
        }
        return idResults;
    }

    @Override
    public void serialize(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(0L);
        dos.writeInt(deletedOrdinals.size());
        for (Integer ordinal : deletedOrdinals) {
            dos.writeInt(ordinal);
        }
        dos.flush();
        hnswIndex.save(out);
    }

    public static HnswVectorIndex deserialize(InputStream in, int dimension,
                                               long[] vectorFileOffsets, MmapAllocator allocator) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readLong();
        int deletedCount = dis.readInt();
        Set<Integer> deletedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int i = 0; i < deletedCount; i++) {
            deletedSet.add(dis.readInt());
        }
        HnswIndex<String, float[], MmapVectorItem, Float> loaded = HnswIndex.load(in);

        // 重新注入transient字段
        for (MmapVectorItem item : loaded.items()) {
            long fileOffset = vectorFileOffsets[item.ordinal];
            item.vectorAddress = allocator.getAddressForOffset(fileOffset);
            item.dimension = dimension;
        }

        return new HnswVectorIndex(dimension, vectorFileOffsets, allocator, loaded, deletedSet);
    }

    @Deprecated
    public static HnswVectorIndex load(InputStream in, int dimension) throws IOException {
        throw new UnsupportedOperationException("Use deserialize with vectorFileOffsets and allocator");
    }

    @Override
    public void close() {
        // jelmerk 不需要显式关闭
    }

    static class MmapVectorItem implements Item<String, float[]>, Serializable {
        private static final long serialVersionUID = 1L;
        final int ordinal;
        transient long vectorAddress;
        transient int dimension;
        private final float[] heapVector; // 用于旧API兼容

        MmapVectorItem(int ordinal, long vectorAddress, int dimension) {
            this.ordinal = ordinal;
            this.vectorAddress = vectorAddress;
            this.dimension = dimension;
            this.heapVector = null;
        }

        MmapVectorItem(int ordinal, long vectorAddress, int dimension, float[] heapVector) {
            this.ordinal = ordinal;
            this.vectorAddress = vectorAddress;
            this.dimension = dimension;
            this.heapVector = heapVector;
        }

        @Override public String id() { return String.valueOf(ordinal); }
        @Override public float[] vector() {
            return heapVector != null ? heapVector : UnsafeOps.getFloatArray(vectorAddress, dimension);
        }
        @Override public int dimensions() { return dimension; }
    }
}
