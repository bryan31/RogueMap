package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVectorIndex — jvector-backed HNSW vector index for roguemap-memory-pro.
 *
 * <p>Uses datastax/jvector 3.x GraphIndexBuilder with lazy mmap vector reads.
 * Vectors are stored in mmap and read on-demand via MmapVectorValues.
 */
public class JVectorIndex implements VectorIndex {

    private static final VectorTypeSupport VECTOR_SUPPORT =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private final int dimension;
    private final long[] vectorOffsets;
    private final MmapAllocator allocator;
    private final Map<Integer, String> ordinalToId = new ConcurrentHashMap<>();
    private final Set<Integer> deletedOrdinals = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private int nextOrdinal = 0;
    private OnHeapGraphIndex graph;
    private boolean graphDirty = true;

    static class MmapVectorValues implements RandomAccessVectorValues {
        private final long[] vectorOffsets;
        private final MmapAllocator allocator;
        private final int dimension;
        private final int size;
        private final VectorTypeSupport vts = VectorizationProvider.getInstance().getVectorTypeSupport();

        MmapVectorValues(long[] vectorOffsets, MmapAllocator allocator, int dimension, int size) {
            this.vectorOffsets = vectorOffsets;
            this.allocator = allocator;
            this.dimension = dimension;
            this.size = size;
        }

        @Override public int size() { return size; }
        @Override public int dimension() { return dimension; }
        @Override public VectorFloat<?> getVector(int ordinal) {
            if (ordinal < 0 || ordinal >= size) {
                throw new IndexOutOfBoundsException("ordinal " + ordinal + " out of range [0, " + size + ")");
            }
            long fileOffset = vectorOffsets[ordinal];
            if (fileOffset == 0) {
                // Return tiny random vector for deleted/uninitialized ordinals to avoid NaN in cosine similarity
                float[] tiny = new float[dimension];
                for (int i = 0; i < dimension; i++) {
                    tiny[i] = 0.0001f * (i + 1);
                }
                return vts.createFloatVector(tiny);
            }
            long address = allocator.getAddressForOffset(fileOffset);
            float[] arr = UnsafeOps.getFloatArray(address, dimension);
            return vts.createFloatVector(arr);
        }
        @Override public boolean isValueShared() { return false; }
        @Override public MmapVectorValues copy() { return this; }
    }

    @Deprecated
    public JVectorIndex(int dimension, int maxElements) {
        this.dimension = dimension;
        this.vectorOffsets = null;
        this.allocator = null;
    }

    public JVectorIndex(int dimension, long[] vectorOffsets, MmapAllocator allocator) {
        this.dimension = dimension;
        this.vectorOffsets = vectorOffsets;
        this.allocator = allocator;
    }

    private JVectorIndex(int dimension, long[] vectorOffsets, MmapAllocator allocator,
                         int nextOrdinal, Map<Integer, String> ordinalToId, Set<Integer> deletedOrdinals) {
        this.dimension = dimension;
        this.vectorOffsets = vectorOffsets;
        this.allocator = allocator;
        this.nextOrdinal = nextOrdinal;
        this.ordinalToId.putAll(ordinalToId);
        this.deletedOrdinals.addAll(deletedOrdinals);
        this.graphDirty = true;
    }

    public synchronized void add(int ordinal, float[] vector) {
        if (vectorOffsets == null || allocator == null) {
            throw new UnsupportedOperationException("This constructor requires vectorOffsets and allocator");
        }
        if (vectorOffsets[ordinal] == 0) {
            throw new IllegalStateException("Vector at ordinal " + ordinal + " not written to mmap yet");
        }
        ordinalToId.put(ordinal, String.valueOf(ordinal));
        nextOrdinal = Math.max(nextOrdinal, ordinal + 1);
        graphDirty = true;
    }

    public void markDeleted(int ordinal) {
        deletedOrdinals.add(ordinal);
    }

    @Deprecated
    @Override
    public synchronized void add(String id, float[] vector) {
        ordinalToId.put(nextOrdinal, id);
        nextOrdinal++;
        graphDirty = true;
    }

    @Deprecated
    @Override
    public void markDeleted(String id) {
        for (Map.Entry<Integer, String> e : ordinalToId.entrySet()) {
            if (e.getValue().equals(id)) {
                deletedOrdinals.add(e.getKey());
                break;
            }
        }
    }

    public synchronized List<ScoredOrdinal> searchByOrdinal(float[] queryVector, int topK) {
        if (nextOrdinal == 0) return Collections.emptyList();

        if (graphDirty || graph == null) {
            buildGraph();
        }

        MmapVectorValues vectorValues = new MmapVectorValues(vectorOffsets, allocator, dimension, nextOrdinal);
        Bits acceptBits = deletedOrdinals.isEmpty() ? Bits.ALL : (node -> !deletedOrdinals.contains(node));

        VectorFloat<?> qvf = toVectorFloat(queryVector);
        int k = Math.min(topK, nextOrdinal - deletedOrdinals.size());
        if (k <= 0) return Collections.emptyList();

        SearchResult result = GraphSearcher.search(qvf, k, vectorValues, VectorSimilarityFunction.COSINE,
                graph, acceptBits);

        List<ScoredOrdinal> out = new ArrayList<>();
        for (SearchResult.NodeScore ns : result.getNodes()) {
            if (!deletedOrdinals.contains(ns.node)) {
                out.add(new ScoredOrdinal(ns.node, ns.score));
            }
        }
        return out;
    }

    private void buildGraph() {
        MmapVectorValues vectorValues = new MmapVectorValues(vectorOffsets, allocator, dimension, nextOrdinal);
        GraphIndexBuilder builder = new GraphIndexBuilder(
                vectorValues,
                VectorSimilarityFunction.COSINE,
                16, 100, 1.2f, 1.4f
        );
        graph = builder.build(vectorValues);
        graphDirty = false;
    }

    @Deprecated
    @Override
    public synchronized List<ScoredId> search(float[] queryVector, int topK) {
        List<ScoredOrdinal> ordResults = searchByOrdinal(queryVector, topK);
        List<ScoredId> out = new ArrayList<>();
        for (ScoredOrdinal so : ordResults) {
            String id = ordinalToId.get(so.ordinal);
            if (id != null) out.add(new ScoredId(id, so.score));
        }
        return out;
    }

    @Override
    public synchronized void serialize(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(dimension);
        dos.writeInt(nextOrdinal);
        dos.writeInt(ordinalToId.size());
        for (Map.Entry<Integer, String> e : ordinalToId.entrySet()) {
            dos.writeInt(e.getKey());
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            dos.writeShort(b.length);
            dos.write(b);
        }
        dos.writeInt(deletedOrdinals.size());
        for (int ord : deletedOrdinals) {
            dos.writeInt(ord);
        }
        dos.flush();
    }

    public static JVectorIndex deserialize(InputStream in, int dimension, long[] vectorOffsets, MmapAllocator allocator) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readInt(); // skip dimension (already provided as parameter)
        int nextOrd = dis.readInt();
        int mapSize = dis.readInt();
        Map<Integer, String> ordToId = new ConcurrentHashMap<>();
        for (int i = 0; i < mapSize; i++) {
            int ord = dis.readInt();
            int len = dis.readShort() & 0xFFFF;
            byte[] b = new byte[len];
            dis.readFully(b);
            ordToId.put(ord, new String(b, StandardCharsets.UTF_8));
        }
        int delSize = dis.readInt();
        Set<Integer> deleted = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int i = 0; i < delSize; i++) {
            deleted.add(dis.readInt());
        }
        return new JVectorIndex(dimension, vectorOffsets, allocator, nextOrd, ordToId, deleted);
    }

    @Deprecated
    public static JVectorIndex load(InputStream in, int dimension) throws IOException {
        return deserialize(in, dimension, null, null);
    }

    @Override
    public void close() {}

    private VectorFloat<?> toVectorFloat(float[] arr) {
        return VECTOR_SUPPORT.createFloatVector(arr);
    }
}
