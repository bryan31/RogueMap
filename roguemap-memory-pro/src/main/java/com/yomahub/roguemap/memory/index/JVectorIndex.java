package com.yomahub.roguemap.memory.index;

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
 * <p>Uses datastax/jvector 3.x GraphIndexBuilder for approximate nearest-neighbor search.
 * Maintains an ordinal→id mapping so jvector node IDs can be translated back to string IDs.
 *
 * <p>Serialization format (compatible with VectorIndex contract):
 * <pre>
 * [generation: 8B long]
 * [deletedCount: 4B int][deletedId_1_len: 2B][deletedId_1 UTF-8]...
 * [entryCount: 4B int]
 * [id_1_len: 2B][id_1 UTF-8][vecLen: 4B int][float * vecLen]...
 * </pre>
 */
public class JVectorIndex implements VectorIndex {

    private static final VectorTypeSupport VECTOR_SUPPORT =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private final int dimension;
    // ordinal → id (append-only; ordinal == index in this list)
    private final List<String> ordinalToId = new ArrayList<>();
    // ordinal → raw float[] (kept for rebuild after deserialization)
    private final List<float[]> ordinalToVector = new ArrayList<>();
    private final Set<String> deletedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // The built graph index (rebuilt lazily on search after adds)
    private OnHeapGraphIndex builtIndex;
    private boolean dirty = false; // true when vectors added since last build

    public JVectorIndex(int dimension, int maxElements) {
        this.dimension = dimension;
    }

    /** Private constructor used by load() */
    private JVectorIndex(int dimension, List<String> ids, List<float[]> vectors,
                         Set<String> deleted) {
        this.dimension = dimension;
        this.ordinalToId.addAll(ids);
        this.ordinalToVector.addAll(vectors);
        this.deletedIds.addAll(deleted);
        this.dirty = true; // need to build index before first search
    }

    @Override
    public synchronized void add(String id, float[] vector) {
        ordinalToId.add(id);
        ordinalToVector.add(vector.clone());
        dirty = true;
        builtIndex = null;
    }

    @Override
    public void markDeleted(String id) {
        deletedIds.add(id);
    }

    @Override
    public synchronized List<ScoredId> search(float[] queryVector, int topK) {
        if (ordinalToId.isEmpty()) return Collections.emptyList();

        // Rebuild graph if dirty
        if (dirty || builtIndex == null) {
            builtIndex = buildGraph();
            dirty = false;
        }

        // Build a Bits mask that excludes deleted nodes
        Set<Integer> deletedOrdinals = new HashSet<>();
        for (int i = 0; i < ordinalToId.size(); i++) {
            if (deletedIds.contains(ordinalToId.get(i))) {
                deletedOrdinals.add(i);
            }
        }
        Bits acceptBits = deletedOrdinals.isEmpty() ? Bits.ALL : (node -> !deletedOrdinals.contains(node));

        // Build RAVV for the query
        List<VectorFloat<?>> vfList = toVectorFloatList(ordinalToVector);
        ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vfList, dimension);

        VectorFloat<?> qvf = toVectorFloat(queryVector);
        SearchScoreProvider ssp = SearchScoreProvider.exact(qvf, VectorSimilarityFunction.COSINE, ravv);

        int k = Math.min(topK, ordinalToId.size() - deletedOrdinals.size());
        if (k <= 0) return Collections.emptyList();

        SearchResult result = GraphSearcher.search(qvf, k, ravv, VectorSimilarityFunction.COSINE,
                builtIndex, acceptBits);

        List<ScoredId> out = new ArrayList<>();
        for (SearchResult.NodeScore ns : result.getNodes()) {
            String id = ordinalToId.get(ns.node);
            if (!deletedIds.contains(id)) {
                out.add(new ScoredId(id, ns.score));
            }
        }
        return out;
    }

    @Override
    public synchronized void serialize(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(0L); // generation placeholder
        dos.writeInt(deletedIds.size());
        for (String id : deletedIds) {
            byte[] b = id.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(b.length);
            dos.write(b);
        }
        dos.writeInt(ordinalToId.size());
        for (int i = 0; i < ordinalToId.size(); i++) {
            byte[] idBytes = ordinalToId.get(i).getBytes(StandardCharsets.UTF_8);
            dos.writeShort(idBytes.length);
            dos.write(idBytes);
            float[] v = ordinalToVector.get(i);
            dos.writeInt(v.length);
            for (float f : v) dos.writeFloat(f);
        }
        dos.flush();
    }

    public static JVectorIndex load(InputStream in, int dimension) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readLong(); // skip generation
        int deletedCount = dis.readInt();
        Set<String> deletedSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int i = 0; i < deletedCount; i++) {
            int len = dis.readShort() & 0xFFFF;
            byte[] b = new byte[len];
            dis.readFully(b);
            deletedSet.add(new String(b, StandardCharsets.UTF_8));
        }
        int count = dis.readInt();
        List<String> ids = new ArrayList<>(count);
        List<float[]> vectors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idLen = dis.readShort() & 0xFFFF;
            byte[] idBytes = new byte[idLen];
            dis.readFully(idBytes);
            ids.add(new String(idBytes, StandardCharsets.UTF_8));
            int vecLen = dis.readInt();
            float[] v = new float[vecLen];
            for (int j = 0; j < vecLen; j++) v[j] = dis.readFloat();
            vectors.add(v);
        }
        return new JVectorIndex(dimension, ids, vectors, deletedSet);
    }

    @Override
    public void close() {}

    // ===== Private helpers =====

    private OnHeapGraphIndex buildGraph() {
        if (ordinalToVector.isEmpty()) return null;
        List<VectorFloat<?>> vfList = toVectorFloatList(ordinalToVector);
        ListRandomAccessVectorValues ravv = new ListRandomAccessVectorValues(vfList, dimension);
        GraphIndexBuilder builder = new GraphIndexBuilder(
                ravv,
                VectorSimilarityFunction.COSINE,
                16,    // M
                100,   // efConstruction
                1.2f,  // alpha
                1.4f   // neighborOverflow
        );
        return builder.build(ravv);
    }

    private List<VectorFloat<?>> toVectorFloatList(List<float[]> floatArrays) {
        List<VectorFloat<?>> list = new ArrayList<>(floatArrays.size());
        for (float[] arr : floatArrays) {
            list.add(toVectorFloat(arr));
        }
        return list;
    }

    private VectorFloat<?> toVectorFloat(float[] arr) {
        return VECTOR_SUPPORT.createFloatVector(arr);
    }
}
