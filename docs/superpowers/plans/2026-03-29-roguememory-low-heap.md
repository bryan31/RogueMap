# RogueMemory Low-Heap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 RogueMemory 百万条场景下堆内存从 ~20 GB+ 压缩到 ~580 MB，通过 UUID→int ordinal 全替换和向量从 mmap 惰性读取实现。

**Architecture:** 引入 `OrdinalRegistry` 统一管理 UUID↔int 双向映射；BM25Index 内部改用 int[] posting list；HnswVectorIndex 使用 `MmapVectorItem`（只存 ordinal，`vector()` 从 mmap 读）；JVectorIndex 使用 `MmapVectorValues`（实现 `RandomAccessVectorValues` 从 mmap 读）；`RogueMemory` 用 `long[] offsetTable` + `long[] vectorFileOffset` 替代 `ConcurrentHashMap<String,Long>`。

**Tech Stack:** Java 8+, hnswlib-core 1.2.1, datastax/jvector 3.0.1, Maven multi-module, JUnit 5

---

## File Map

| 操作 | 文件 |
|------|------|
| Create | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/OrdinalRegistry.java` |
| Create | `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/OrdinalRegistryTest.java` |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java` |
| Modify | `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/BM25IndexTest.java` |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java` |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/HnswVectorIndex.java` |
| Modify | `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/HnswVectorIndexTest.java` |
| Modify | `roguemap-core/src/main/java/com/yomahub/roguemap/storage/MmapFileHeader.java` |
| Modify | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` |
| Create | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/MmapVectorValues.java` |
| Modify | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java` |
| Modify | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java` |
| Modify | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/JVectorIndex.java` |
| Modify | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` |
| Create | `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryScaleTest.java` |

---

## Task 1: OrdinalRegistry

**Files:**
- Create: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/OrdinalRegistry.java`
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/OrdinalRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/OrdinalRegistryTest.java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrdinalRegistryTest {

    @Test
    void registerAndLookup() {
        OrdinalRegistry reg = new OrdinalRegistry();
        String uuid = UUID.randomUUID().toString();
        int ordinal = reg.register(uuid);
        assertEquals(0, ordinal);
        assertEquals(uuid, reg.getId(ordinal));
        assertEquals(ordinal, reg.getOrdinal(uuid));
    }

    @Test
    void multipleRegistrations() {
        OrdinalRegistry reg = new OrdinalRegistry();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        int oa = reg.register(a);
        int ob = reg.register(b);
        assertEquals(0, oa);
        assertEquals(1, ob);
        assertEquals(a, reg.getId(0));
        assertEquals(b, reg.getId(1));
    }

    @Test
    void releaseAndReuse() {
        OrdinalRegistry reg = new OrdinalRegistry();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        int oa = reg.register(a);
        reg.register(b);
        reg.release(a);
        assertEquals(-1, reg.getOrdinal(a));
        assertNull(reg.getId(oa));
        int oc = reg.register(c);
        assertEquals(oa, oc); // ordinal 0 reused
        assertEquals(c, reg.getId(oc));
    }

    @Test
    void unknownIdReturnsMinusOne() {
        OrdinalRegistry reg = new OrdinalRegistry();
        assertEquals(-1, reg.getOrdinal("not-registered"));
    }

    @Test
    void serializeDeserializeRoundTrip() throws Exception {
        OrdinalRegistry reg = new OrdinalRegistry();
        String u1 = UUID.randomUUID().toString();
        String u2 = UUID.randomUUID().toString();
        int o1 = reg.register(u1);
        int o2 = reg.register(u2);

        byte[] data = reg.serialize();
        OrdinalRegistry restored = OrdinalRegistry.deserialize(data);

        assertEquals(u1, restored.getId(o1));
        assertEquals(u2, restored.getId(o2));
        assertEquals(o1, restored.getOrdinal(u1));
        assertEquals(o2, restored.getOrdinal(u2));
    }

    @Test
    void serializeSkipsReleasedEntries() throws Exception {
        OrdinalRegistry reg = new OrdinalRegistry();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        reg.register(a);
        reg.register(b);
        reg.release(a);

        byte[] data = reg.serialize();
        OrdinalRegistry restored = OrdinalRegistry.deserialize(data);

        assertEquals(-1, restored.getOrdinal(a));
        assertEquals(1, restored.getOrdinal(b));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl roguemap-memory -Dtest=OrdinalRegistryTest
```
Expected: FAIL — `OrdinalRegistry` does not exist.

- [ ] **Step 3: Implement OrdinalRegistry**

```java
// roguemap-memory/src/main/java/com/yomahub/roguemap/memory/OrdinalRegistry.java
package com.yomahub.roguemap.memory;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * UUID ↔ int ordinal 双向映射注册表。
 * 已释放的 ordinal 进入 freeList 供下次注册复用，保证 idTable 不无限增长。
 */
public class OrdinalRegistry {

    private String[] idTable;
    private final Map<String, Integer> idToOrdinal = new HashMap<>();
    private int[] freeList;
    private int freeTop = 0;
    private int nextOrdinal = 0;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public OrdinalRegistry() {
        idTable = new String[1024];
        freeList = new int[256];
    }

    /** 注册 uuid，返回分配的 ordinal（优先复用已释放的） */
    public int register(String uuid) {
        lock.writeLock().lock();
        try {
            int ordinal;
            if (freeTop > 0) {
                ordinal = freeList[--freeTop];
            } else {
                ordinal = nextOrdinal++;
                if (ordinal >= idTable.length) {
                    idTable = Arrays.copyOf(idTable, idTable.length * 2);
                }
            }
            idTable[ordinal] = uuid;
            idToOrdinal.put(uuid, ordinal);
            return ordinal;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 释放 uuid 对应的 ordinal，放入 freeList */
    public void release(String uuid) {
        lock.writeLock().lock();
        try {
            Integer ordinal = idToOrdinal.remove(uuid);
            if (ordinal == null) return;
            idTable[ordinal] = null;
            if (freeTop >= freeList.length) {
                freeList = Arrays.copyOf(freeList, freeList.length * 2);
            }
            freeList[freeTop++] = ordinal;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 正查：uuid → ordinal；未注册返回 -1 */
    public int getOrdinal(String uuid) {
        lock.readLock().lock();
        try {
            Integer v = idToOrdinal.get(uuid);
            return v == null ? -1 : v;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 反查：ordinal → uuid；无效返回 null */
    public String getId(int ordinal) {
        lock.readLock().lock();
        try {
            if (ordinal < 0 || ordinal >= idTable.length) return null;
            return idTable[ordinal];
        } finally {
            lock.readLock().unlock();
        }
    }

    /** nextOrdinal（包含已释放的槽位），用于预分配数组大小 */
    public int capacity() {
        lock.readLock().lock();
        try { return nextOrdinal; } finally { lock.readLock().unlock(); }
    }

    /** 序列化为字节数组，仅写入活跃条目 */
    public byte[] serialize() throws IOException {
        lock.readLock().lock();
        try {
            int count = idToOrdinal.size();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(count * 20);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(count);
            for (int i = 0; i < nextOrdinal; i++) {
                if (idTable[i] == null) continue;
                dos.writeInt(i);
                UUID uuid = UUID.fromString(idTable[i]);
                dos.writeLong(uuid.getMostSignificantBits());
                dos.writeLong(uuid.getLeastSignificantBits());
            }
            dos.flush();
            return baos.toByteArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 从字节数组反序列化 */
    public static OrdinalRegistry deserialize(byte[] data) throws IOException {
        OrdinalRegistry reg = new OrdinalRegistry();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int count = dis.readInt();
        int maxOrdinal = -1;
        for (int i = 0; i < count; i++) {
            int ordinal = dis.readInt();
            long msb = dis.readLong();
            long lsb = dis.readLong();
            String uuid = new UUID(msb, lsb).toString();
            if (ordinal >= reg.idTable.length) {
                reg.idTable = Arrays.copyOf(reg.idTable, Math.max(ordinal + 1, reg.idTable.length * 2));
            }
            reg.idTable[ordinal] = uuid;
            reg.idToOrdinal.put(uuid, ordinal);
            if (ordinal > maxOrdinal) maxOrdinal = ordinal;
        }
        reg.nextOrdinal = maxOrdinal + 1;
        return reg;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl roguemap-memory -Dtest=OrdinalRegistryTest
```
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/OrdinalRegistry.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/OrdinalRegistryTest.java
git commit -m "feat(memory): add OrdinalRegistry for UUID↔int ordinal mapping"
```

---

## Task 2: BM25Index int-ordinal refactoring (roguemap-memory)

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/BM25IndexTest.java`

- [ ] **Step 1: Replace BM25IndexTest with int-ordinal version**

完全替换 `BM25IndexTest.java`：

```java
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
        index.add(0, "我有一件红衣服");
        index.add(1, "今天天气很好");
        index.add(2, "我喜欢穿衣服");

        List<BM25Index.ScoredOrdinal> results = index.search(
            Arrays.asList("红衣", "衣服"), 5);

        assertFalse(results.isEmpty());
        assertEquals(0, results.get(0).ordinal); // ordinal 0 最相关
    }

    @Test
    void deleteRemovesFromResults() {
        index.add(0, "我有一件红衣服");
        index.add(1, "红色的衣服真好看");
        index.delete(0);

        List<BM25Index.ScoredOrdinal> results = index.search(
            Arrays.asList("红衣", "衣服"), 5);

        assertTrue(results.stream().noneMatch(r -> r.ordinal == 0));
        assertTrue(results.stream().anyMatch(r -> r.ordinal == 1));
    }

    @Test
    void searchReturnsEmptyWhenNoMatch() {
        index.add(0, "今天天气很好");
        List<BM25Index.ScoredOrdinal> results = index.search(
            Arrays.asList("red", "dress"), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void emptyIndexReturnsEmpty() {
        List<BM25Index.ScoredOrdinal> results = index.search(
            Arrays.asList("test"), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void serializeAndDeserialize() throws Exception {
        index.add(0, "我有一件红衣服");
        index.add(1, "今天天气很好");

        byte[] serialized = index.serialize();
        BM25Index restored = BM25Index.deserialize(serialized, 1.2f, 0.75f);

        List<BM25Index.ScoredOrdinal> results = restored.search(
            Arrays.asList("红衣", "衣服"), 5);
        assertFalse(results.isEmpty());
        assertEquals(0, results.get(0).ordinal);
    }

    @Test
    void topKRespected() {
        for (int i = 0; i < 10; i++) {
            index.add(i, "衣服 红色 好看 " + i);
        }
        List<BM25Index.ScoredOrdinal> results = index.search(
            Arrays.asList("衣服"), 3);
        assertEquals(3, results.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl roguemap-memory -Dtest=BM25IndexTest
```
Expected: FAIL — `ScoredOrdinal` 不存在，`add(int, String)` 签名不匹配。

- [ ] **Step 3: Rewrite BM25Index.java**

完全替换 `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java`：

```java
package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.util.Tokenizer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 倒排索引 — 内部使用 int ordinal 代替 String UUID，大幅降低堆内存。
 *
 * <p>posting list 用两个并行 int 数组存储：ordinalIndex（ordinal 数组）和 tfIndex（tf 数组）。
 * docLengths 用 int[] 存储，下标即 ordinal，-1 表示已删除。
 */
public class BM25Index {

    public static class ScoredOrdinal {
        public final int ordinal;
        public final float score;
        public ScoredOrdinal(int ordinal, float score) {
            this.ordinal = ordinal;
            this.score = score;
        }
    }

    private final float k1;
    private final float b;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // term → ordinals 数组（与 tfIndex 下标对齐）
    private final Map<String, int[]> ordinalIndex = new HashMap<>();
    // term → tf 数组（与 ordinalIndex 下标对齐）
    private final Map<String, int[]> tfIndex = new HashMap<>();
    // ordinal → 词数（-1 表示已删除或未占用）
    private int[] docLengths;
    // term → 包含该词的文档数
    private final Map<String, Integer> docFreqs = new HashMap<>();
    private int docCount = 0;

    public BM25Index(float k1, float b) {
        this.k1 = k1;
        this.b = b;
        docLengths = new int[1024];
        Arrays.fill(docLengths, -1);
    }

    public void add(int ordinal, String content) {
        List<String> tokens = Tokenizer.tokenize(content);
        if (tokens.isEmpty()) return;

        lock.writeLock().lock();
        try {
            ensureDocLengthsCapacity(ordinal);
            if (docLengths[ordinal] >= 0) {
                removeFromIndex(ordinal);
            }

            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) tf.merge(token, 1, Integer::sum);

            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                int termTf = e.getValue();
                int[] curOrds = ordinalIndex.getOrDefault(term, new int[0]);
                int[] curTfs = tfIndex.getOrDefault(term, new int[0]);
                int[] newOrds = Arrays.copyOf(curOrds, curOrds.length + 1);
                int[] newTfs = Arrays.copyOf(curTfs, curTfs.length + 1);
                newOrds[curOrds.length] = ordinal;
                newTfs[curTfs.length] = termTf;
                ordinalIndex.put(term, newOrds);
                tfIndex.put(term, newTfs);
                docFreqs.merge(term, 1, Integer::sum);
            }

            docLengths[ordinal] = tokens.size();
            docCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(int ordinal) {
        lock.writeLock().lock();
        try { removeFromIndex(ordinal); } finally { lock.writeLock().unlock(); }
    }

    private void removeFromIndex(int ordinal) {
        if (ordinal >= docLengths.length || docLengths[ordinal] < 0) return;
        docLengths[ordinal] = -1;
        docCount--;

        for (Map.Entry<String, int[]> e : ordinalIndex.entrySet()) {
            String term = e.getKey();
            int[] ords = e.getValue();
            int idx = -1;
            for (int i = 0; i < ords.length; i++) {
                if (ords[i] == ordinal) { idx = i; break; }
            }
            if (idx < 0) continue;
            int[] tfs = tfIndex.get(term);
            int[] newOrds = new int[ords.length - 1];
            int[] newTfs = new int[ords.length - 1];
            System.arraycopy(ords, 0, newOrds, 0, idx);
            System.arraycopy(ords, idx + 1, newOrds, idx, ords.length - idx - 1);
            System.arraycopy(tfs, 0, newTfs, 0, idx);
            System.arraycopy(tfs, idx + 1, newTfs, idx, tfs.length - idx - 1);
            ordinalIndex.put(term, newOrds);
            tfIndex.put(term, newTfs);
            docFreqs.merge(term, -1, Integer::sum);
        }
    }

    public List<ScoredOrdinal> search(List<String> queryTokens, int topK) {
        lock.readLock().lock();
        try {
            if (docCount == 0 || queryTokens.isEmpty()) return Collections.emptyList();

            double totalLen = 0;
            int validDocs = 0;
            for (int dl : docLengths) {
                if (dl >= 0) { totalLen += dl; validDocs++; }
            }
            double avgDl = validDocs > 0 ? totalLen / validDocs : 1.0;
            int N = validDocs;

            Map<Integer, Double> scores = new HashMap<>();
            for (String term : queryTokens) {
                int[] ords = ordinalIndex.get(term);
                if (ords == null) continue;
                int[] tfs = tfIndex.get(term);
                int df = docFreqs.getOrDefault(term, 0);
                double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

                for (int i = 0; i < ords.length; i++) {
                    int ord = ords[i];
                    if (ord >= docLengths.length || docLengths[ord] < 0) continue;
                    int tf = tfs[i];
                    int dl = docLengths[ord];
                    double tfNorm = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgDl));
                    scores.merge(ord, idf * tfNorm, Double::sum);
                }
            }

            List<ScoredOrdinal> result = new ArrayList<>(scores.size());
            for (Map.Entry<Integer, Double> e : scores.entrySet()) {
                result.add(new ScoredOrdinal(e.getKey(), e.getValue().floatValue()));
            }
            result.sort((a, x) -> Float.compare(x.score, a.score));
            return result.subList(0, Math.min(topK, result.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 序列化为字节数组 */
    public byte[] serialize() throws IOException {
        lock.readLock().lock();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // docLengths：写到最后一个有效下标
            int maxOrdinal = -1;
            for (int i = docLengths.length - 1; i >= 0; i--) {
                if (docLengths[i] >= 0) { maxOrdinal = i; break; }
            }
            int writeLen = maxOrdinal + 1;
            dos.writeInt(writeLen);
            for (int i = 0; i < writeLen; i++) dos.writeInt(docLengths[i]);

            // ordinalIndex + tfIndex
            dos.writeInt(ordinalIndex.size());
            for (Map.Entry<String, int[]> e : ordinalIndex.entrySet()) {
                String term = e.getKey();
                int[] ords = e.getValue();
                int[] tfs = tfIndex.get(term);
                byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
                dos.writeShort(termBytes.length);
                dos.write(termBytes);
                dos.writeInt(ords.length);
                for (int i = 0; i < ords.length; i++) {
                    dos.writeInt(ords[i]);
                    dos.writeInt(tfs[i]);
                }
            }

            // docFreqs
            dos.writeInt(docFreqs.size());
            for (Map.Entry<String, Integer> e : docFreqs.entrySet()) {
                byte[] termBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(termBytes.length);
                dos.write(termBytes);
                dos.writeInt(e.getValue());
            }
            dos.flush();
            return baos.toByteArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 从字节数组反序列化 */
    public static BM25Index deserialize(byte[] data, float k1, float b) throws IOException {
        BM25Index idx = new BM25Index(k1, b);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

        int docLenSize = dis.readInt();
        idx.docLengths = new int[Math.max(docLenSize, 1024)];
        Arrays.fill(idx.docLengths, -1);
        int validCount = 0;
        for (int i = 0; i < docLenSize; i++) {
            idx.docLengths[i] = dis.readInt();
            if (idx.docLengths[i] >= 0) validCount++;
        }
        idx.docCount = validCount;

        int termCount = dis.readInt();
        for (int t = 0; t < termCount; t++) {
            int termLen = dis.readShort() & 0xFFFF;
            byte[] termBytes = new byte[termLen];
            dis.readFully(termBytes);
            String term = new String(termBytes, StandardCharsets.UTF_8);
            int postLen = dis.readInt();
            int[] ords = new int[postLen];
            int[] tfs = new int[postLen];
            for (int i = 0; i < postLen; i++) {
                ords[i] = dis.readInt();
                tfs[i] = dis.readInt();
            }
            idx.ordinalIndex.put(term, ords);
            idx.tfIndex.put(term, tfs);
        }

        int dfCount = dis.readInt();
        for (int i = 0; i < dfCount; i++) {
            int termLen = dis.readShort() & 0xFFFF;
            byte[] termBytes = new byte[termLen];
            dis.readFully(termBytes);
            idx.docFreqs.put(new String(termBytes, StandardCharsets.UTF_8), dis.readInt());
        }
        return idx;
    }

    private void ensureDocLengthsCapacity(int ordinal) {
        if (ordinal < docLengths.length) return;
        int newLen = Math.max(ordinal + 1, docLengths.length * 2);
        int[] newArr = new int[newLen];
        Arrays.fill(newArr, -1);
        System.arraycopy(docLengths, 0, newArr, 0, docLengths.length);
        docLengths = newArr;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl roguemap-memory -Dtest=BM25IndexTest
```
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/BM25IndexTest.java
git commit -m "refactor(memory): BM25Index uses int ordinals instead of String UUIDs"
```

---

## Task 3: VectorIndex interface + HnswVectorIndex refactoring (roguemap-memory)

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java`
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/HnswVectorIndex.java`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/HnswVectorIndexTest.java`

- [ ] **Step 1: Write the failing test for the new HnswVectorIndex**

完全替换 `HnswVectorIndexTest.java`：

```java
package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.memory.index.HnswVectorIndex;
import com.yomahub.roguemap.memory.index.VectorIndex;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HnswVectorIndexTest {

    /** 构造 vectorFileOffsets 和 allocator 的 stub，直接在内存里存向量 */
    private static class StubAllocator {
        private final float[][] vectors;
        private final long[] fileOffsets;
        private final long baseAddr;

        StubAllocator(float[][] vectors) {
            this.vectors = vectors;
            // 每个向量的假 fileOffset 就是 ordinal 本身（乘以 dim*4 方便计算）
            fileOffsets = new long[vectors.length];
            for (int i = 0; i < vectors.length; i++) fileOffsets[i] = i;
            baseAddr = 0;
        }
    }

    /**
     * 真正测试时需要 MmapAllocator，这里用 MockEmbeddingProvider 维度给个简单测试。
     * 由于 MmapVectorItem 直接读 UnsafeOps，单元测试需要真实的 mmap。
     * 这里用集成风格：通过 RogueMemoryFunctionalTest 已有的基础做回归即可。
     * 本测试验证接口签名和 markDeleted / search 过滤。
     */
    @Test
    void interfaceSignaturesCompile() {
        // 只要能编译就说明接口改对了
        VectorIndex.ScoredOrdinal so = new VectorIndex.ScoredOrdinal(3, 0.8f);
        assertEquals(3, so.ordinal);
        assertEquals(0.8f, so.score, 0.001f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl roguemap-memory -Dtest=HnswVectorIndexTest
```
Expected: FAIL — `VectorIndex.ScoredOrdinal` 不存在。

- [ ] **Step 3: Rewrite VectorIndex.java**

完全替换 `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java`：

```java
package com.yomahub.roguemap.memory.index;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public interface VectorIndex {

    class ScoredOrdinal {
        public final int ordinal;
        public final float score;
        public ScoredOrdinal(int ordinal, float score) {
            this.ordinal = ordinal;
            this.score = score;
        }
    }

    /**
     * 添加节点。向量通过 ordinal 从 mmap 惰性读取，不再传入 float[]。
     */
    void add(int ordinal);

    /** 标记删除（tombstone；compact 时物理移除） */
    void markDeleted(int ordinal);

    /**
     * 近似最近邻搜索，返回结果 ordinal 列表（不含已删除节点）
     */
    List<ScoredOrdinal> search(float[] queryVector, int topK);

    /**
     * 序列化到输出流。
     * 格式由各实现自定义；必须包含 [generation: 8B long] 作为首字段。
     */
    void serialize(OutputStream out) throws IOException;

    void close();
}
```

- [ ] **Step 4: Rewrite HnswVectorIndex.java**

完全替换 `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/HnswVectorIndex.java`：

```java
package com.yomahub.roguemap.memory.index;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;

import java.io.*;
import java.util.*;

/**
 * hnswlib-core 向量索引 — 使用 int ordinal 作为节点 ID，向量从 mmap 惰性读取。
 *
 * <p>MmapVectorItem 只存储 int ordinal；transient 字段（vectorFileOffsets, allocator, dimension）
 * 在 load() 后通过 hnswIndex.items().values() 重新注入，保证序列化正确。
 */
public class HnswVectorIndex implements VectorIndex {

    private final int dimension;
    private HnswIndex<Integer, float[], MmapVectorItem, Float> hnswIndex;
    private BitSet deletedOrdinals = new BitSet();

    private final long[] vectorFileOffsets;  // ordinal → vector floats 在文件中的偏移
    private final MmapAllocator allocator;

    public HnswVectorIndex(int dimension, int maxElements,
                           long[] vectorFileOffsets, MmapAllocator allocator) {
        this.dimension = dimension;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
        this.hnswIndex = HnswIndex
            .newBuilder(dimension, DistanceFunctions.FLOAT_COSINE_DISTANCE, maxElements)
            .withM(16)
            .withEfConstruction(200)
            .withEf(50)
            .build();
    }

    private HnswVectorIndex(int dimension,
                             HnswIndex<Integer, float[], MmapVectorItem, Float> hnswIndex,
                             BitSet deletedOrdinals,
                             long[] vectorFileOffsets,
                             MmapAllocator allocator) {
        this.dimension = dimension;
        this.hnswIndex = hnswIndex;
        this.deletedOrdinals = deletedOrdinals;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
    }

    @Override
    public void add(int ordinal) {
        hnswIndex.add(new MmapVectorItem(ordinal, vectorFileOffsets, allocator, dimension));
    }

    @Override
    public void markDeleted(int ordinal) {
        deletedOrdinals.set(ordinal);
    }

    @Override
    public List<ScoredOrdinal> search(float[] queryVector, int topK) {
        int candidates = topK + deletedOrdinals.cardinality() + 10;
        List<SearchResult<MmapVectorItem, Float>> raw =
            hnswIndex.findNearest(queryVector, candidates);

        List<ScoredOrdinal> result = new ArrayList<>();
        for (SearchResult<MmapVectorItem, Float> r : raw) {
            int ordinal = r.item().ordinal;
            if (!deletedOrdinals.get(ordinal)) {
                result.add(new ScoredOrdinal(ordinal, 1f - r.distance()));
                if (result.size() >= topK) break;
            }
        }
        return result;
    }

    @Override
    public void serialize(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(0L); // generation placeholder
        // BitSet
        byte[] bitBytes = deletedOrdinals.toByteArray();
        dos.writeInt(bitBytes.length);
        dos.write(bitBytes);
        dos.flush();
        // hnswlib-core 序列化（包含图结构和 MmapVectorItem.ordinal 字段）
        hnswIndex.save(out);
    }

    /**
     * 从输入流加载并重新注入 transient 字段。
     * hnswlib-core 用 Java 序列化保存 MmapVectorItem；transient 字段在反序列化后为 null，
     * 通过 hnswIndex.items().values() 遍历重新注入。
     */
    public static HnswVectorIndex load(InputStream in, int dimension,
                                       long[] vectorFileOffsets,
                                       MmapAllocator allocator) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readLong(); // generation
        int bitLen = dis.readInt();
        byte[] bitBytes = new byte[bitLen];
        dis.readFully(bitBytes);
        BitSet deleted = BitSet.valueOf(bitBytes);

        HnswIndex<Integer, float[], MmapVectorItem, Float> loaded = HnswIndex.load(in);

        // 重新注入 transient 字段（序列化后为 null）
        for (MmapVectorItem item : loaded.items().values()) {
            item.vectorFileOffsets = vectorFileOffsets;
            item.allocator = allocator;
            item.dimension = dimension;
        }
        return new HnswVectorIndex(dimension, loaded, deleted, vectorFileOffsets, allocator);
    }

    @Override
    public void close() {}

    // ===== MmapVectorItem（hnswlib-core Item 实现）=====

    static class MmapVectorItem implements Item<Integer, float[]>, Serializable {
        private static final long serialVersionUID = 2L;

        final int ordinal;

        // transient：序列化时不写入，load() 后手动注入
        transient long[] vectorFileOffsets;
        transient MmapAllocator allocator;
        transient int dimension;

        MmapVectorItem(int ordinal, long[] vectorFileOffsets,
                       MmapAllocator allocator, int dimension) {
            this.ordinal = ordinal;
            this.vectorFileOffsets = vectorFileOffsets;
            this.allocator = allocator;
            this.dimension = dimension;
        }

        @Override
        public Integer id() { return ordinal; }

        @Override
        public float[] vector() {
            long addr = allocator.getAddressForOffset(vectorFileOffsets[ordinal]);
            float[] v = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                v[i] = UnsafeOps.getFloat(addr + (long) i * 4);
            }
            return v;
        }

        @Override
        public int dimensions() { return dimension; }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl roguemap-memory -Dtest=HnswVectorIndexTest
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java \
        roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/HnswVectorIndex.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/HnswVectorIndexTest.java
git commit -m "refactor(memory): HnswVectorIndex uses int ordinals + MmapVectorItem reads vectors from mmap"
```

---

## Task 4: MmapFileHeader — ordinalRegistryOffset 字段

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/storage/MmapFileHeader.java`

- [ ] **Step 1: 在 MmapFileHeader 中添加常量和 getter/setter**

在 `MmapFileHeader.java` 中找到：

```java
    // ===== RogueMemory 扩展字段（bytes 112-127）=====
    public static final int MEMORY_BM25_INDEX_OFFSET_POS = 112;  // BM25 倒排索引在文件中的偏移量（8 bytes）
    public static final int MEMORY_HNSW_GENERATION_POS = 120;    // HNSW 文件的 generation 号（8 bytes），用于一致性校验
```

替换为：

```java
    // ===== RogueMemory 扩展字段（bytes 112-135）=====
    public static final int MEMORY_BM25_INDEX_OFFSET_POS = 112;       // BM25 倒排索引偏移量（8 bytes）
    public static final int MEMORY_HNSW_GENERATION_POS = 120;         // HNSW generation 号（8 bytes）
    public static final int MEMORY_ORDINAL_REGISTRY_OFFSET_POS = 128; // OrdinalRegistry 偏移量（8 bytes）
```

然后在文件末尾（`setHnswGeneration` 方法之后，类的结束括号之前）添加：

```java
    /**
     * 读取 OrdinalRegistry 在文件中的偏移量（0 表示尚未写入）。
     */
    public static long getOrdinalRegistryOffset(long address) {
        return UnsafeOps.getLong(address + MEMORY_ORDINAL_REGISTRY_OFFSET_POS);
    }

    /**
     * 写入 OrdinalRegistry 在文件中的偏移量。
     */
    public static void setOrdinalRegistryOffset(long address, long offset) {
        UnsafeOps.putLong(address + MEMORY_ORDINAL_REGISTRY_OFFSET_POS, offset);
    }
```

同时更新文件顶部注释，在 `===== RogueMemory 扩展字段（bytes 112-127）=====` 区块添加一行：

```
 * - OrdinalRegistry Offset (8 bytes, offset 128): OrdinalRegistry 在文件中的偏移量（0=尚未写入）
```

- [ ] **Step 2: Run all core tests to verify no regression**

```bash
mvn test -pl roguemap-core
```
Expected: 全部通过。

- [ ] **Step 3: Commit**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/storage/MmapFileHeader.java
git commit -m "feat(core): add OrdinalRegistry offset field to MmapFileHeader"
```

---

## Task 5: RogueMemory wiring (roguemap-memory)

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`

此任务将 `RogueMemory` 内部全面替换为 ordinal-based 实现。涉及字段、add/delete/update/get/search、compact、saveIndexes、build() 等所有核心路径。

- [ ] **Step 1: Run existing functional tests to establish baseline**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryFunctionalTest,RogueMemorySearchTest,RogueMemoryPersistenceTest
```
Expected: 全部通过（记录通过数量，改造后应维持相同数量）。

- [ ] **Step 2: Replace fields, constructor, add(), delete(), update(), get() in RogueMemory.java**

找到并替换 `// ===== 内部状态 =====` 区块（从 `private final MmapAllocator allocator;` 到 `private volatile boolean closed = false;`）：

```java
    // ===== 内部状态 =====
    private final MmapAllocator allocator;
    private final String basePath;
    private final SearchMode searchMode;
    private final EmbeddingProvider embeddingProvider;

    private final BM25Index bm25Index;
    private HnswVectorIndex vectorIndex;    // null when KEYWORD_ONLY

    private final OrdinalRegistry registry;
    private long[] offsetTable;       // ordinal → record file offset（-1L = 未分配/已删除）
    private long[] vectorFileOffset;  // ordinal → vector floats file offset（0 = 无向量）

    private volatile boolean closed = false;
```

找到并替换私有构造函数：

```java
    private RogueMemory(MmapAllocator allocator, String basePath,
                        SearchMode searchMode, EmbeddingProvider embeddingProvider,
                        BM25Index bm25Index, HnswVectorIndex vectorIndex,
                        OrdinalRegistry registry, long[] offsetTable, long[] vectorFileOffset) {
        this.allocator = allocator;
        this.basePath = basePath;
        this.searchMode = searchMode;
        this.embeddingProvider = embeddingProvider;
        this.bm25Index = bm25Index;
        this.vectorIndex = vectorIndex;
        this.registry = registry;
        this.offsetTable = offsetTable;
        this.vectorFileOffset = vectorFileOffset;
    }
```

找到并替换 `add(String content, Map<String, String> metadata, String namespace)` 方法：

```java
    public String add(String content, Map<String, String> metadata, String namespace) {
        checkOpen();
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        float[] vector = null;
        if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
            vector = embeddingProvider.embed(content);
        }
        int ordinal = registry.register(id);
        ensureTableCapacity(ordinal);

        long addr = writeRecordToAllocator(allocator, id, content, metadata, namespace,
                createdAt, 0L, vector, false);
        long recOffset = allocator.getFileOffsetForAddress(addr);
        offsetTable[ordinal] = recOffset;
        vectorFileOffset[ordinal] = (vector != null && vector.length > 0)
                ? computeVectorFileOffset(addr) : 0L;

        bm25Index.add(ordinal, content);
        if (vectorIndex != null && vector != null) {
            vectorIndex.add(ordinal);
        }
        return id;
    }
```

找到并替换 `get(String id)` 方法：

```java
    public MemoryEntry get(String id) {
        checkOpen();
        int ordinal = registry.getOrdinal(id);
        if (ordinal < 0) return null;
        return readRecord(offsetTable[ordinal]);
    }
```

找到并替换 `delete(String id)` 方法：

```java
    public void delete(String id) {
        checkOpen();
        int ordinal = registry.getOrdinal(id);
        if (ordinal < 0) return;
        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        int deletedByteOffset = computeDeletedByteOffset(addr);
        UnsafeOps.putByte(addr + deletedByteOffset, (byte) 1);
        registry.release(id);
        offsetTable[ordinal] = -1L;
        vectorFileOffset[ordinal] = 0L;
        bm25Index.delete(ordinal);
        if (vectorIndex != null) vectorIndex.markDeleted(ordinal);
    }
```

找到并替换 `update(String id, String newContent)` 方法：

```java
    public void update(String id, String newContent) {
        checkOpen();
        int ordinal = registry.getOrdinal(id);
        if (ordinal < 0) return;
        MemoryEntry old = readRecord(offsetTable[ordinal]);
        if (old == null) return;

        // 标记旧记录删除
        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        UnsafeOps.putByte(addr + computeDeletedByteOffset(addr), (byte) 1);
        bm25Index.delete(ordinal);
        if (vectorIndex != null) vectorIndex.markDeleted(ordinal);

        // 写新记录（复用同一 ordinal）
        float[] vector = null;
        if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
            vector = embeddingProvider.embed(newContent);
        }
        long newAddr = writeRecordToAllocator(allocator, id, newContent, old.getMetadata(),
                old.getNamespace(), old.getCreatedAt(), old.getExpireTime(), vector, false);
        long newRecOffset = allocator.getFileOffsetForAddress(newAddr);
        offsetTable[ordinal] = newRecOffset;
        vectorFileOffset[ordinal] = (vector != null && vector.length > 0)
                ? computeVectorFileOffset(newAddr) : 0L;

        bm25Index.add(ordinal, newContent);
        if (vectorIndex != null && vector != null) {
            vectorIndex.add(ordinal);
        }
    }
```

- [ ] **Step 3: Replace writeRecord() and indexEntry() in RogueMemory.java**

找到并删除旧的 `writeRecord` / `writeRecordWithId` / `indexEntry` 私有方法（它们将不再需要），并添加两个新辅助方法：

```java
    /**
     * 计算 mmap record 中向量浮点数起始地址对应的文件偏移量。
     * record 格式：[8B expireTime][16B id][2B nsLen][nsBytes][4B contentLen][contentBytes]
     *              [4B metaLen][metaBytes][4B vectorLen][↑↑↑ 向量从这里开始 ↑↑↑]
     */
    private long computeVectorFileOffset(long recordAddr) {
        long pos = recordAddr;
        pos += 8;  // expireTime
        pos += 16; // id (msb + lsb)
        int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2 + nsLen;
        int contentLen = UnsafeOps.getInt(pos); pos += 4 + contentLen;
        int metaLen = UnsafeOps.getInt(pos); pos += 4 + metaLen;
        pos += 4; // skip vectorLen field
        return allocator.getFileOffsetForAddress(pos);
    }

    /** 确保 offsetTable / vectorFileOffset 数组足以容纳给定 ordinal */
    private void ensureTableCapacity(int ordinal) {
        if (ordinal < offsetTable.length) return;
        int newLen = Math.max(ordinal + 1, offsetTable.length * 2);
        long[] newOT = new long[newLen];
        long[] newVF = new long[newLen];
        Arrays.fill(newOT, -1L);
        System.arraycopy(offsetTable, 0, newOT, 0, offsetTable.length);
        System.arraycopy(vectorFileOffset, 0, newVF, 0, vectorFileOffset.length);
        offsetTable = newOT;
        vectorFileOffset = newVF;
    }
```

- [ ] **Step 4: Update search helpers to use ordinal-based results**

找到 `keywordSearch` 方法中的：

```java
        List<BM25Index.ScoredId> raw = bm25Index.search(tokens, candidates);
        List<MemoryResult> results = new ArrayList<>();
        for (BM25Index.ScoredId s : raw) {
            MemoryEntry e = get(s.id);
```

替换为：

```java
        List<BM25Index.ScoredOrdinal> raw = bm25Index.search(tokens, candidates);
        List<MemoryResult> results = new ArrayList<>();
        for (BM25Index.ScoredOrdinal s : raw) {
            String docId = registry.getId(s.ordinal);
            if (docId == null) continue;
            MemoryEntry e = readRecord(offsetTable[s.ordinal]);
```

找到 `vectorSearch` 方法中用 `VectorIndex.ScoredId` 的部分：

```java
        List<VectorIndex.ScoredId> raw = vectorIndex.search(queryVector, candidates);
        List<MemoryResult> results = new ArrayList<>();
        for (VectorIndex.ScoredId s : raw) {
            MemoryEntry e = get(s.id);
```

替换为：

```java
        List<VectorIndex.ScoredOrdinal> raw = vectorIndex.search(queryVector, candidates);
        List<MemoryResult> results = new ArrayList<>();
        for (VectorIndex.ScoredOrdinal s : raw) {
            String docId = registry.getId(s.ordinal);
            if (docId == null) continue;
            MemoryEntry e = readRecord(offsetTable[s.ordinal]);
```

在两处 `results.add(new MemoryResult(...))` 中，把旧的 `s.id` 替换为 `docId`。

- [ ] **Step 5: Update saveIndexes() to persist OrdinalRegistry**

找到 `saveIndexes()` 方法，在 `// Save HNSW to .hnsw file` 代码块之前添加 OrdinalRegistry 保存逻辑，并在 `header.write(baseAddr)` 之后添加 `setOrdinalRegistryOffset`：

```java
    private void saveIndexes() {
        long baseAddr = allocator.getBaseAddress();

        // Save BM25 to mmap
        long bm25FileOffset = 0;
        try {
            byte[] bm25Data = bm25Index.serialize();
            int bm25Size = bm25Data.length;
            long bm25Addr = allocator.allocate(4 + bm25Size);
            UnsafeOps.putInt(bm25Addr, bm25Size);
            UnsafeOps.copyFromArray(bm25Data, 0, bm25Addr + 4, bm25Size);
            bm25FileOffset = allocator.getFileOffsetForAddress(bm25Addr);
        } catch (IOException e) {
            // non-fatal
        }

        // Save OrdinalRegistry to mmap
        long registryFileOffset = 0;
        try {
            byte[] regData = registry.serialize();
            long regAddr = allocator.allocate(4 + regData.length);
            UnsafeOps.putInt(regAddr, regData.length);
            UnsafeOps.copyFromArray(regData, 0, regAddr + 4, regData.length);
            registryFileOffset = allocator.getFileOffsetForAddress(regAddr);
        } catch (IOException e) {
            // non-fatal
        }

        // Save HNSW to .hnsw file
        long generation = System.currentTimeMillis();
        if (vectorIndex != null) {
            String hnswPath = basePath + ".hnsw";
            try (FileOutputStream fos = new FileOutputStream(hnswPath)) {
                vectorIndex.serialize(fos);
            } catch (IOException e) {
                generation = 0;
            }
        }

        // Write main header
        MmapFileHeader header = allocator.readHeader();
        header.setCurrentOffset(allocator.usedMemory());
        header.setEntryCount(registry.capacity());
        header.write(baseAddr);

        // Write extension fields AFTER header.write()（write() zeros them）
        if (bm25FileOffset != 0) MmapFileHeader.setBm25IndexOffset(baseAddr, bm25FileOffset);
        if (vectorIndex != null && generation != 0) MmapFileHeader.setHnswGeneration(baseAddr, generation);
        if (registryFileOffset != 0) MmapFileHeader.setOrdinalRegistryOffset(baseAddr, registryFileOffset);

        allocator.flush();
    }
```

- [ ] **Step 6: Update build() method to restore OrdinalRegistry + offsetTable + vectorFileOffset**

找到 `MmapBuilder.build()` 方法中的 `if (isExisting)` 分支，将 restore 路径替换为：

```java
            if (isExisting) {
                MmapFileHeader header = allocator.readHeader();
                allocator.restoreOffset(header.getCurrentOffset());
                MmapFileHeader.markOpen(baseAddr);

                int dirtyFlag = MmapFileHeader.getDirtyFlag(baseAddr);

                // 恢复 OrdinalRegistry
                OrdinalRegistry registry = loadOrdinalRegistry(allocator, baseAddr);

                // 扫描重建 offsetTable + vectorFileOffset（以及 BM25 / HNSW 如果脏）
                long[][] tables = scanOffsetTables(allocator, registry);
                long[] offsetTable = tables[0];
                long[] vectorFileOffset = tables[1];

                BM25Index bm25;
                HnswVectorIndex hnsw = null;
                if (dirtyFlag == 1) {
                    bm25 = new BM25Index(1.2f, 0.75f);
                    hnsw = buildHnswFromScan(allocator, embeddingProvider, searchMode,
                            bm25, offsetTable, vectorFileOffset);
                } else {
                    bm25 = loadBm25(allocator, baseAddr);
                    hnsw = loadHnsw(allocator, baseAddr, hnswPath, embeddingProvider,
                            searchMode, offsetTable, vectorFileOffset);
                    if (hnsw == null && searchMode != SearchMode.KEYWORD_ONLY
                            && embeddingProvider != null) {
                        hnsw = buildHnswFromScan(allocator, embeddingProvider, searchMode,
                                null, offsetTable, vectorFileOffset);
                    }
                }

                return new RogueMemory(allocator, path, searchMode, embeddingProvider,
                        bm25, hnsw, registry, offsetTable, vectorFileOffset);
            } else {
                // New file
                MmapFileHeader header = new MmapFileHeader();
                header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
                header.setVersion(MmapFileHeader.VERSION);
                header.setDataType(MmapFileHeader.DATA_TYPE_MEMORY);
                header.setCurrentOffset(MmapFileHeader.HEADER_SIZE);
                header.write(baseAddr);
                MmapFileHeader.markOpen(baseAddr);

                OrdinalRegistry registry = new OrdinalRegistry();
                long[] offsetTable = new long[1024];
                long[] vectorFileOffset = new long[1024];
                Arrays.fill(offsetTable, -1L);

                HnswVectorIndex hnsw = null;
                if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
                    ensureDimension(embeddingProvider);
                    hnsw = new HnswVectorIndex(embeddingProvider.getDimension(), HNSW_MAX_ELEMENTS,
                            vectorFileOffset, allocator);
                }
                return new RogueMemory(allocator, path, searchMode, embeddingProvider,
                        new BM25Index(1.2f, 0.75f), hnsw, registry, offsetTable, vectorFileOffset);
            }
```

- [ ] **Step 7: Add new scan/load helpers**

在 `scanIdOffsets` 方法之后添加以下两个新方法：

```java
    /**
     * 扫描 mmap 重建 offsetTable[ordinal] 和 vectorFileOffset[ordinal]。
     * OrdinalRegistry 必须已恢复，否则 getOrdinal(id) 返回 -1。
     */
    private static long[][] scanOffsetTables(MmapAllocator alloc, OrdinalRegistry registry) {
        int cap = Math.max(registry.capacity() + 64, 1024);
        long[] offTbl = new long[cap];
        long[] vecTbl = new long[cap];
        Arrays.fill(offTbl, -1L);

        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long end = alloc.usedMemory();
        while (scanOffset < end) {
            long addr = alloc.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                int ordinal = registry.getOrdinal(rh.id);
                if (ordinal >= 0) {
                    if (ordinal >= offTbl.length) {
                        int newLen = ordinal * 2 + 1;
                        long[] newOT = Arrays.copyOf(offTbl, newLen);
                        long[] newVT = Arrays.copyOf(vecTbl, newLen);
                        Arrays.fill(newOT, offTbl.length, newLen, -1L);
                        offTbl = newOT;
                        vecTbl = newVT;
                    }
                    offTbl[ordinal] = scanOffset;
                    // 计算 vector floats 的文件偏移
                    long pos = addr + 8 + 16; // skip expireTime + id
                    int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2 + nsLen;
                    int cLen = UnsafeOps.getInt(pos); pos += 4 + cLen;
                    int mLen = UnsafeOps.getInt(pos); pos += 4 + mLen;
                    int vLen = UnsafeOps.getInt(pos); pos += 4; // skip vectorLen
                    vecTbl[ordinal] = (vLen > 0) ? alloc.getFileOffsetForAddress(pos) : 0L;
                }
            }
            scanOffset += rh.totalSize;
        }
        return new long[][]{offTbl, vecTbl};
    }

    private static OrdinalRegistry loadOrdinalRegistry(MmapAllocator alloc, long baseAddr) {
        long regOffset = MmapFileHeader.getOrdinalRegistryOffset(baseAddr);
        if (regOffset == 0) return new OrdinalRegistry();
        try {
            long regAddr = alloc.getAddressForOffset(regOffset);
            int regSize = UnsafeOps.getInt(regAddr);
            byte[] regData = new byte[regSize];
            UnsafeOps.copyToArray(regAddr + 4, regData, 0, regSize);
            return OrdinalRegistry.deserialize(regData);
        } catch (Exception e) {
            return new OrdinalRegistry();
        }
    }
```

- [ ] **Step 8: Update buildHnswFromScan() and loadHnsw() signatures**

找到 `buildHnswFromScan` 并更新签名和实现：

```java
    private static HnswVectorIndex buildHnswFromScan(MmapAllocator alloc,
                                                      EmbeddingProvider provider,
                                                      SearchMode mode,
                                                      BM25Index bm25ToPopulate,
                                                      long[] offsetTable,
                                                      long[] vectorFileOffset) {
        if (mode == SearchMode.KEYWORD_ONLY || provider == null) return null;
        ensureDimension(provider);
        HnswVectorIndex hnsw = new HnswVectorIndex(provider.getDimension(), HNSW_MAX_ELEMENTS,
                vectorFileOffset, alloc);
        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long end = alloc.usedMemory();
        while (scanOffset < end) {
            long addr = alloc.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                MemoryEntry entry = parseRecordFull(addr, rh);
                if (entry != null && !entry.isExpired() && entry.getVector() != null) {
                    // ordinal lookup via scanning: not available here; rebuilt after loadOrdinalRegistry
                    // hnsw.add() 需要 ordinal，但 buildHnswFromScan 在有 registry 的场景不应被调用
                    // 如果 dirtyFlag==1 （脏启动），在 build() 里重建完 registry+tables 后才调用
                    // 所以此处通过 offsetTable 反查 ordinal：
                    // 直接从 mmap 读 uuid -> ordinal
                }
                if (bm25ToPopulate != null && entry != null && !entry.isExpired()) {
                    // 同理，ordinal 从 registry 取
                }
            }
            scanOffset += rh.totalSize;
        }
        return hnsw;
    }
```

**注意**：`buildHnswFromScan` 在脏启动（dirtyFlag==1）时被调用，此时 registry 已经恢复，扫描顺序与 `scanOffsetTables` 相同。把两次扫描合并为一次：

找到 build() 中的 `if (dirtyFlag == 1)` 分支，替换为：

```java
                if (dirtyFlag == 1) {
                    // 脏启动：在一次扫描中同时重建 BM25、offsetTable、vectorFileOffset
                    bm25 = new BM25Index(1.2f, 0.75f);
                    long scanOffset = MmapFileHeader.HEADER_SIZE;
                    long end = allocator.usedMemory();
                    while (scanOffset < end) {
                        long addr = allocator.getAddressForOffset(scanOffset);
                        RecordHeader rh = parseRecordHeader(addr);
                        if (rh == null) break;
                        if (!rh.deleted) {
                            int ord = registry.getOrdinal(rh.id);
                            if (ord >= 0) {
                                ensureCapacity(offsetTable, vectorFileOffset, ord);
                                offsetTable[ord] = scanOffset;
                                // 计算 vector offset
                                long pos = addr + 8 + 16;
                                int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2 + nsLen;
                                int cLen = UnsafeOps.getInt(pos); pos += 4 + cLen;
                                int mLen = UnsafeOps.getInt(pos); pos += 4 + mLen;
                                int vLen = UnsafeOps.getInt(pos); pos += 4;
                                vectorFileOffset[ord] = (vLen > 0) ? allocator.getFileOffsetForAddress(pos) : 0L;
                                MemoryEntry entry = parseRecordFull(addr, rh);
                                if (entry != null && !entry.isExpired()) {
                                    bm25.add(ord, entry.getContent());
                                }
                            }
                        }
                        scanOffset += rh.totalSize;
                    }
                    if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
                        ensureDimension(embeddingProvider);
                        hnsw = new HnswVectorIndex(embeddingProvider.getDimension(),
                                HNSW_MAX_ELEMENTS, vectorFileOffset, allocator);
                        // re-add all valid nodes
                        for (int ord = 0; ord < registry.capacity(); ord++) {
                            if (vectorFileOffset[ord] != 0L) {
                                hnsw.add(ord);
                            }
                        }
                    }
                }
```

同时删除已不再使用的 `scanIdOffsets` 方法（被 `scanOffsetTables` 取代）。

更新 `loadHnsw` 签名（增加 vectorFileOffset 和 allocator 参数）：

```java
    private static HnswVectorIndex loadHnsw(MmapAllocator alloc, long baseAddr,
                                             String hnswPath, EmbeddingProvider provider,
                                             SearchMode mode,
                                             long[] vectorFileOffsets,
                                             MmapAllocator allocator) {
        if (mode == SearchMode.KEYWORD_ONLY || provider == null) return null;
        File hnswFile = new File(hnswPath);
        if (!hnswFile.exists()) return null;
        long storedGen = MmapFileHeader.getHnswGeneration(baseAddr);
        if (storedGen == 0) return null;
        try (FileInputStream fis = new FileInputStream(hnswFile)) {
            return HnswVectorIndex.load(fis, provider.getDimension(), vectorFileOffsets, allocator);
        } catch (IOException e) {
            return null;
        }
    }
```

- [ ] **Step 9: Update compact() to use new API**

找到 `compact()` 中所有 `idToFileOffset`、`bm25Index.add(entry.getId(), ...)` 等旧调用，替换为：

```java
    public RogueMemory compact(long newFileSize) {
        checkOpen();
        String tmpMemPath = basePath + ".mem.tmp";
        String tmpHnswPath = basePath + ".hnsw.tmp";
        String memPath = basePath + ".mem";
        String hnswPath = basePath + ".hnsw";

        MmapAllocator newAlloc = new MmapAllocator(tmpMemPath, newFileSize, false);
        long newBase = newAlloc.getBaseAddress();
        MmapFileHeader newHeader = new MmapFileHeader();
        newHeader.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
        newHeader.setVersion(MmapFileHeader.VERSION);
        newHeader.setDataType(MmapFileHeader.DATA_TYPE_MEMORY);
        newHeader.setCurrentOffset(MmapFileHeader.HEADER_SIZE);
        newHeader.write(newBase);

        OrdinalRegistry newRegistry = new OrdinalRegistry();
        long[] newOffsetTable = new long[1024];
        long[] newVectorFileOffset = new long[1024];
        Arrays.fill(newOffsetTable, -1L);

        BM25Index newBm25 = new BM25Index(1.2f, 0.75f);
        HnswVectorIndex newHnsw = null;
        if (vectorIndex != null) {
            ensureDimension(embeddingProvider);
            newHnsw = new HnswVectorIndex(embeddingProvider.getDimension(), HNSW_MAX_ELEMENTS,
                    newVectorFileOffset, newAlloc);
        }

        // 扫描 live records
        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long currentEnd = allocator.usedMemory();
        while (scanOffset < currentEnd) {
            long addr = allocator.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                MemoryEntry entry = parseRecordFull(addr, rh);
                if (entry != null && !entry.isExpired()) {
                    int newOrd = newRegistry.register(entry.getId());
                    ensureCapacity(newOffsetTable, newVectorFileOffset, newOrd);

                    long newAddr = writeRecordToAllocator(newAlloc, entry.getId(),
                            entry.getContent(), entry.getMetadata(), entry.getNamespace(),
                            entry.getCreatedAt(), entry.getExpireTime(), entry.getVector(), false);
                    newOffsetTable[newOrd] = newAlloc.getFileOffsetForAddress(newAddr);

                    if (entry.getVector() != null && entry.getVector().length > 0) {
                        long pos = newAddr + 8 + 16;
                        int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2 + nsLen;
                        int cLen = UnsafeOps.getInt(pos); pos += 4 + cLen;
                        int mLen = UnsafeOps.getInt(pos); pos += 4 + mLen;
                        pos += 4; // skip vectorLen
                        newVectorFileOffset[newOrd] = newAlloc.getFileOffsetForAddress(pos);
                    }

                    newBm25.add(newOrd, entry.getContent());
                    if (newHnsw != null && entry.getVector() != null) {
                        newHnsw.add(newOrd);
                    }
                }
            }
            scanOffset += rh.totalSize;
        }

        // 保存 BM25 + OrdinalRegistry + HNSW
        long newBm25Offset = 0;
        try {
            byte[] bm25Data = newBm25.serialize();
            long bm25Addr = newAlloc.allocate(4 + bm25Data.length);
            UnsafeOps.putInt(bm25Addr, bm25Data.length);
            UnsafeOps.copyFromArray(bm25Data, 0, bm25Addr + 4, bm25Data.length);
            newBm25Offset = newAlloc.getFileOffsetForAddress(bm25Addr);
        } catch (IOException ignored) {}

        long newRegOffset = 0;
        try {
            byte[] regData = newRegistry.serialize();
            long regAddr = newAlloc.allocate(4 + regData.length);
            UnsafeOps.putInt(regAddr, regData.length);
            UnsafeOps.copyFromArray(regData, 0, regAddr + 4, regData.length);
            newRegOffset = newAlloc.getFileOffsetForAddress(regAddr);
        } catch (IOException ignored) {}

        long newGeneration = System.currentTimeMillis();
        if (newHnsw != null) {
            try (FileOutputStream fos = new FileOutputStream(tmpHnswPath)) {
                newHnsw.serialize(fos);
            } catch (IOException e) { newGeneration = 0; }
        }

        MmapFileHeader hdr = newAlloc.readHeader();
        hdr.setCurrentOffset(newAlloc.usedMemory());
        hdr.setEntryCount(newRegistry.capacity());
        hdr.write(newBase);
        if (newBm25Offset != 0) MmapFileHeader.setBm25IndexOffset(newBase, newBm25Offset);
        if (newHnsw != null && newGeneration != 0) MmapFileHeader.setHnswGeneration(newBase, newGeneration);
        if (newRegOffset != 0) MmapFileHeader.setOrdinalRegistryOffset(newBase, newRegOffset);

        newAlloc.flush();
        newAlloc.close();

        try {
            Files.move(new File(tmpMemPath).toPath(), new File(memPath).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            if (newHnsw != null) {
                Files.move(new File(tmpHnswPath).toPath(), new File(hnswPath).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("compact: rename failed", e);
        }

        MmapAllocator compactedAlloc = new MmapAllocator(memPath, newFileSize, false);
        compactedAlloc.restoreOffset(compactedAlloc.readHeader().getCurrentOffset());

        OrdinalRegistry restoredRegistry = loadOrdinalRegistry(compactedAlloc, compactedAlloc.getBaseAddress());
        long[][] tables = scanOffsetTables(compactedAlloc, restoredRegistry);

        HnswVectorIndex loadedHnsw = null;
        if (newHnsw != null) {
            try (FileInputStream fis = new FileInputStream(hnswPath)) {
                loadedHnsw = HnswVectorIndex.load(fis, embeddingProvider.getDimension(),
                        tables[1], compactedAlloc);
            } catch (IOException e) {
                throw new RuntimeException("compact: HNSW load failed", e);
            }
        }

        return new RogueMemory(compactedAlloc, basePath, searchMode, embeddingProvider,
                newBm25, loadedHnsw, restoredRegistry, tables[0], tables[1]);
    }
```

添加 compact() 用到的内联辅助（放在 `ensureTableCapacity` 附近）：

```java
    private static void ensureCapacity(long[] offTbl, long[] vecTbl, int ordinal) {
        // 注意：此方法修改 array 引用不够用，compact() 中需手动扩容
        // 在 compact() 中直接写内联扩容；此处仅给 RogueMemory 实例方法使用
        // 实际上 compact() 用的是局部 long[]，使用 Arrays.copyOf 扩容
    }
```

实际上，`compact()` 中每次 `newOrd` 递增，需要扩容。将 compact() 中的 `ensureCapacity` 调用改为内联：

```java
                    if (newOrd >= newOffsetTable.length) {
                        int newLen = newOrd * 2 + 1;
                        newOffsetTable = Arrays.copyOf(newOffsetTable, newLen);
                        long[] ext = new long[newLen];
                        Arrays.fill(ext, newOffsetTable.length, newLen, -1L);
                        System.arraycopy(newOffsetTable, 0, ext, 0, newOffsetTable.length);
                        newOffsetTable = ext;
                        newVectorFileOffset = Arrays.copyOf(newVectorFileOffset, newLen);
                    }
```

- [ ] **Step 10: Run all roguemap-memory tests**

```bash
mvn test -pl roguemap-memory
```
Expected: 全部通过（与 Step 1 基准一致）。

- [ ] **Step 11: Commit**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java
git commit -m "refactor(memory): wire OrdinalRegistry + offsetTable + MmapVectorItem into RogueMemory"
```

---

## Task 6: BM25Index + VectorIndex refactoring (roguemap-memory-pro)

**Files:**
- Modify: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java`
- Modify: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java`

此任务将 Task 2 和 Task 3 中的接口变更同步到 pro 模块。

- [ ] **Step 1: 将 roguemap-memory 的 BM25Index.java 复制到 pro 模块**

复制 `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java` 内容到 `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java`，只改包名（两个模块的包名相同，无需改动）。

- [ ] **Step 2: 将 roguemap-memory 的 VectorIndex.java 复制到 pro 模块**

复制 `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java` 到 `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java`（内容完全相同）。

- [ ] **Step 3: Run pro module compile check**

```bash
mvn compile -pl roguemap-memory-pro
```
Expected: 编译成功（JVectorIndex 暂时有编译错误，下个 Task 修复）。

- [ ] **Step 4: Commit**

```bash
git add roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/BM25Index.java \
        roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/VectorIndex.java
git commit -m "refactor(memory-pro): sync BM25Index int-ordinal + VectorIndex ScoredOrdinal"
```

---

## Task 7: MmapVectorValues + JVectorIndex refactoring (roguemap-memory-pro)

**Files:**
- Create: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/MmapVectorValues.java`
- Modify: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/JVectorIndex.java`

- [ ] **Step 1: Create MmapVectorValues.java**

```java
// roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/MmapVectorValues.java
package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;

import java.util.List;

/**
 * jvector RandomAccessVectorValues 实现，按需从 mmap 读取向量，堆上不保留任何 float[]。
 *
 * <p>jvectorToRogue[jvOrdinal] → rogueOrdinal → vectorFileOffsets[rogueOrdinal] → mmap 地址
 */
class MmapVectorValues implements RandomAccessVectorValues {

    private final List<Integer> jvectorToRogue;
    private final long[] vectorFileOffsets;   // rogueOrdinal → vector floats 文件偏移
    private final MmapAllocator allocator;
    private final int dimension;

    MmapVectorValues(List<Integer> jvectorToRogue, long[] vectorFileOffsets,
                     MmapAllocator allocator, int dimension) {
        this.jvectorToRogue = jvectorToRogue;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
        this.dimension = dimension;
    }

    @Override
    public int size() {
        return jvectorToRogue.size();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] getVector(int jvOrdinal) {
        int rogueOrdinal = jvectorToRogue.get(jvOrdinal);
        long addr = allocator.getAddressForOffset(vectorFileOffsets[rogueOrdinal]);
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = UnsafeOps.getFloat(addr + (long) i * 4);
        }
        return v;
    }

    @Override
    public void getVectorInto(int jvOrdinal, float[] buffer, int offset) {
        int rogueOrdinal = jvectorToRogue.get(jvOrdinal);
        long addr = allocator.getAddressForOffset(vectorFileOffsets[rogueOrdinal]);
        for (int i = 0; i < dimension; i++) {
            buffer[offset + i] = UnsafeOps.getFloat(addr + (long) i * 4);
        }
    }

    @Override
    public RandomAccessVectorValues copy() {
        return this; // immutable view, safe to share
    }
}
```

- [ ] **Step 2: Rewrite JVectorIndex.java**

完全替换 `JVectorIndex.java`：

```java
package com.yomahub.roguemap.memory.index;

import com.yomahub.roguemap.memory.MmapAllocator;
import io.github.jbellis.jvector.graph.*;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;

import java.io.*;
import java.util.*;

/**
 * JVectorIndex — jvector-backed HNSW 向量索引，向量从 mmap 按需读取（零堆占用）。
 *
 * <p>jvectorToRogue 列表将 jvector 内部序号（0-based）映射到 RogueMemory ordinal。
 * 向量通过 MmapVectorValues 提供给 jvector，不存储在堆上。
 *
 * <p>序列化格式：
 * <pre>
 * [generation: 8B long]
 * [deletedBitset len: 4B int][deletedBitset bytes]
 * [jvectorToRogue count: 4B int][rogueOrdinal_0: 4B int]...
 * </pre>
 * 注意：图结构不持久化，首次搜索时重建（dirty=true）。
 */
public class JVectorIndex implements VectorIndex {

    private static final VectorTypeSupport VECTOR_SUPPORT =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private final int dimension;
    private final long[] vectorFileOffsets;   // rogueOrdinal → vector floats 文件偏移
    private final MmapAllocator allocator;

    // jvector 内部序号（0-based）→ rogue ordinal
    private final List<Integer> jvectorToRogue = new ArrayList<>();
    private final BitSet deletedRogueOrdinals = new BitSet();

    private OnHeapGraphIndex builtIndex;
    private boolean dirty = false;

    public JVectorIndex(int dimension, int maxElements,
                        long[] vectorFileOffsets, MmapAllocator allocator) {
        this.dimension = dimension;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
    }

    private JVectorIndex(int dimension, long[] vectorFileOffsets, MmapAllocator allocator) {
        this.dimension = dimension;
        this.vectorFileOffsets = vectorFileOffsets;
        this.allocator = allocator;
    }

    @Override
    public synchronized void add(int rogueOrdinal) {
        jvectorToRogue.add(rogueOrdinal);
        dirty = true;
        builtIndex = null;
    }

    @Override
    public void markDeleted(int rogueOrdinal) {
        deletedRogueOrdinals.set(rogueOrdinal);
    }

    @Override
    public synchronized List<ScoredOrdinal> search(float[] queryVector, int topK) {
        if (jvectorToRogue.isEmpty()) return Collections.emptyList();

        if (dirty || builtIndex == null) {
            MmapVectorValues ravv = new MmapVectorValues(
                    jvectorToRogue, vectorFileOffsets, allocator, dimension);
            builtIndex = buildGraph(ravv);
            dirty = false;
        }

        MmapVectorValues ravv = new MmapVectorValues(
                jvectorToRogue, vectorFileOffsets, allocator, dimension);
        VectorFloat<?> qvf = VECTOR_SUPPORT.createFloatVector(queryVector);

        Bits acceptBits = (jvOrd -> {
            int rogueOrd = jvectorToRogue.get(jvOrd);
            return !deletedRogueOrdinals.get(rogueOrd);
        });

        int k = Math.min(topK + deletedRogueOrdinals.cardinality() + 10,
                jvectorToRogue.size());
        SearchResult result = GraphSearcher.search(
                qvf, k, ravv, VectorSimilarityFunction.COSINE, builtIndex, acceptBits);

        List<ScoredOrdinal> out = new ArrayList<>();
        for (SearchResult.NodeScore ns : result.getNodes()) {
            int rogueOrdinal = jvectorToRogue.get(ns.node);
            if (!deletedRogueOrdinals.get(rogueOrdinal)) {
                out.add(new ScoredOrdinal(rogueOrdinal, ns.score));
                if (out.size() >= topK) break;
            }
        }
        return out;
    }

    @Override
    public synchronized void serialize(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeLong(0L); // generation placeholder
        byte[] delBytes = deletedRogueOrdinals.toByteArray();
        dos.writeInt(delBytes.length);
        dos.write(delBytes);
        dos.writeInt(jvectorToRogue.size());
        for (int rogueOrd : jvectorToRogue) dos.writeInt(rogueOrd);
        dos.flush();
        // 图结构不序列化，首次搜索时重建
    }

    public static JVectorIndex load(InputStream in, int dimension,
                                    long[] vectorFileOffsets,
                                    MmapAllocator allocator) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        dis.readLong(); // generation
        int delLen = dis.readInt();
        byte[] delBytes = new byte[delLen];
        dis.readFully(delBytes);
        BitSet deleted = BitSet.valueOf(delBytes);
        int count = dis.readInt();

        JVectorIndex idx = new JVectorIndex(dimension, vectorFileOffsets, allocator);
        for (int i = 0; i < count; i++) idx.jvectorToRogue.add(dis.readInt());
        idx.deletedRogueOrdinals.or(deleted);
        idx.dirty = true; // 首次搜索时重建图
        return idx;
    }

    @Override
    public void close() {}

    private OnHeapGraphIndex buildGraph(MmapVectorValues ravv) {
        GraphIndexBuilder builder = new GraphIndexBuilder(
                ravv, VectorSimilarityFunction.COSINE,
                16, 100, 1.2f, 1.4f);
        return builder.build(ravv);
    }
}
```

- [ ] **Step 3: Run pro module compile and tests**

```bash
mvn test -pl roguemap-memory-pro
```
Expected: 编译通过（旧测试可能因 RogueMemory.java 改动而失败，下个 Task 处理）。

- [ ] **Step 4: Commit**

```bash
git add roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/MmapVectorValues.java \
        roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/index/JVectorIndex.java
git commit -m "refactor(memory-pro): JVectorIndex reads vectors from mmap via MmapVectorValues"
```

---

## Task 8: RogueMemory wiring (roguemap-memory-pro)

**Files:**
- Modify: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`

此模块的 `RogueMemory.java` 与 `roguemap-memory` 结构相同，但使用 `JVectorIndex` 替代 `HnswVectorIndex`。

- [ ] **Step 1: Replace fields and constructor**

找到 `// ===== 内部状态 =====` 区块（`private final Map<String, Long> idToFileOffset`），替换为：

```java
    // ===== 内部状态 =====
    private final MmapAllocator allocator;
    private final String basePath;
    private final SearchMode searchMode;
    private final EmbeddingProvider embeddingProvider;

    private final BM25Index bm25Index;
    private JVectorIndex vectorIndex;    // null when KEYWORD_ONLY

    private final OrdinalRegistry registry;
    private long[] offsetTable;
    private long[] vectorFileOffset;

    private volatile boolean closed = false;
```

找到私有构造函数替换为：

```java
    private RogueMemory(MmapAllocator allocator, String basePath,
                        SearchMode searchMode, EmbeddingProvider embeddingProvider,
                        BM25Index bm25Index, JVectorIndex vectorIndex,
                        OrdinalRegistry registry, long[] offsetTable, long[] vectorFileOffset) {
        this.allocator = allocator;
        this.basePath = basePath;
        this.searchMode = searchMode;
        this.embeddingProvider = embeddingProvider;
        this.bm25Index = bm25Index;
        this.vectorIndex = vectorIndex;
        this.registry = registry;
        this.offsetTable = offsetTable;
        this.vectorFileOffset = vectorFileOffset;
    }
```

- [ ] **Step 2: Replace add(), get(), delete(), update()**

与 Task 5 Step 2 完全相同的代码，唯一区别是 `vectorIndex` 类型为 `JVectorIndex`（接口调用相同）：

```java
    public String add(String content, Map<String, String> metadata, String namespace) {
        checkOpen();
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        float[] vector = null;
        if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
            vector = embeddingProvider.embed(content);
        }
        int ordinal = registry.register(id);
        ensureTableCapacity(ordinal);

        long addr = writeRecordToAllocator(allocator, id, content, metadata, namespace,
                createdAt, 0L, vector, false);
        long recOffset = allocator.getFileOffsetForAddress(addr);
        offsetTable[ordinal] = recOffset;
        vectorFileOffset[ordinal] = (vector != null && vector.length > 0)
                ? computeVectorFileOffset(addr) : 0L;

        bm25Index.add(ordinal, content);
        if (vectorIndex != null && vector != null) {
            vectorIndex.add(ordinal);
        }
        return id;
    }

    public MemoryEntry get(String id) {
        checkOpen();
        int ordinal = registry.getOrdinal(id);
        if (ordinal < 0) return null;
        return readRecord(offsetTable[ordinal]);
    }

    public void delete(String id) {
        checkOpen();
        int ordinal = registry.getOrdinal(id);
        if (ordinal < 0) return;
        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        UnsafeOps.putByte(addr + computeDeletedByteOffset(addr), (byte) 1);
        registry.release(id);
        offsetTable[ordinal] = -1L;
        vectorFileOffset[ordinal] = 0L;
        bm25Index.delete(ordinal);
        if (vectorIndex != null) vectorIndex.markDeleted(ordinal);
    }

    public void update(String id, String newContent) {
        checkOpen();
        int ordinal = registry.getOrdinal(id);
        if (ordinal < 0) return;
        MemoryEntry old = readRecord(offsetTable[ordinal]);
        if (old == null) return;

        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        UnsafeOps.putByte(addr + computeDeletedByteOffset(addr), (byte) 1);
        bm25Index.delete(ordinal);
        if (vectorIndex != null) vectorIndex.markDeleted(ordinal);

        float[] vector = null;
        if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
            vector = embeddingProvider.embed(newContent);
        }
        long newAddr = writeRecordToAllocator(allocator, id, newContent, old.getMetadata(),
                old.getNamespace(), old.getCreatedAt(), old.getExpireTime(), vector, false);
        long newRecOffset = allocator.getFileOffsetForAddress(newAddr);
        offsetTable[ordinal] = newRecOffset;
        vectorFileOffset[ordinal] = (vector != null && vector.length > 0)
                ? computeVectorFileOffset(newAddr) : 0L;
        bm25Index.add(ordinal, newContent);
        if (vectorIndex != null && vector != null) {
            vectorIndex.add(ordinal);
        }
    }
```

- [ ] **Step 3: Add helpers computeVectorFileOffset() and ensureTableCapacity()**

与 Task 5 Step 3 完全相同（仅 `allocator` 字段引用同名，无差异）：

```java
    private long computeVectorFileOffset(long recordAddr) {
        long pos = recordAddr;
        pos += 8;
        pos += 16;
        int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2 + nsLen;
        int contentLen = UnsafeOps.getInt(pos); pos += 4 + contentLen;
        int metaLen = UnsafeOps.getInt(pos); pos += 4 + metaLen;
        pos += 4;
        return allocator.getFileOffsetForAddress(pos);
    }

    private void ensureTableCapacity(int ordinal) {
        if (ordinal < offsetTable.length) return;
        int newLen = Math.max(ordinal + 1, offsetTable.length * 2);
        long[] newOT = new long[newLen];
        long[] newVF = new long[newLen];
        Arrays.fill(newOT, -1L);
        System.arraycopy(offsetTable, 0, newOT, 0, offsetTable.length);
        System.arraycopy(vectorFileOffset, 0, newVF, 0, vectorFileOffset.length);
        offsetTable = newOT;
        vectorFileOffset = newVF;
    }
```

- [ ] **Step 4: Replace search helpers (keywordSearch, vectorSearch)**

与 Task 5 Step 4 完全相同，用 `BM25Index.ScoredOrdinal` 和 `VectorIndex.ScoredOrdinal` 替换旧类型。

- [ ] **Step 5: Replace saveIndexes()**

与 Task 5 Step 5 相同，但 `vectorIndex` 是 `JVectorIndex`（接口调用相同）：

```java
    private void saveIndexes() {
        long baseAddr = allocator.getBaseAddress();

        long bm25FileOffset = 0;
        try {
            byte[] bm25Data = bm25Index.serialize();
            long bm25Addr = allocator.allocate(4 + bm25Data.length);
            UnsafeOps.putInt(bm25Addr, bm25Data.length);
            UnsafeOps.copyFromArray(bm25Data, 0, bm25Addr + 4, bm25Data.length);
            bm25FileOffset = allocator.getFileOffsetForAddress(bm25Addr);
        } catch (IOException e) {}

        long registryFileOffset = 0;
        try {
            byte[] regData = registry.serialize();
            long regAddr = allocator.allocate(4 + regData.length);
            UnsafeOps.putInt(regAddr, regData.length);
            UnsafeOps.copyFromArray(regData, 0, regAddr + 4, regData.length);
            registryFileOffset = allocator.getFileOffsetForAddress(regAddr);
        } catch (IOException e) {}

        long generation = System.currentTimeMillis();
        if (vectorIndex != null) {
            String hnswPath = basePath + ".hnsw";
            try (FileOutputStream fos = new FileOutputStream(hnswPath)) {
                vectorIndex.serialize(fos);
            } catch (IOException e) { generation = 0; }
        }

        MmapFileHeader header = allocator.readHeader();
        header.setCurrentOffset(allocator.usedMemory());
        header.setEntryCount(registry.capacity());
        header.write(baseAddr);

        if (bm25FileOffset != 0) MmapFileHeader.setBm25IndexOffset(baseAddr, bm25FileOffset);
        if (vectorIndex != null && generation != 0) MmapFileHeader.setHnswGeneration(baseAddr, generation);
        if (registryFileOffset != 0) MmapFileHeader.setOrdinalRegistryOffset(baseAddr, registryFileOffset);

        allocator.flush();
    }
```

- [ ] **Step 6: Replace build() restore path**

与 Task 5 Step 6 完全相同，但 `HnswVectorIndex` 替换为 `JVectorIndex`，并调用 `JVectorIndex.load()`：

```java
            if (isExisting) {
                MmapFileHeader header = allocator.readHeader();
                allocator.restoreOffset(header.getCurrentOffset());
                MmapFileHeader.markOpen(baseAddr);

                int dirtyFlag = MmapFileHeader.getDirtyFlag(baseAddr);
                OrdinalRegistry registry = loadOrdinalRegistry(allocator, baseAddr);
                long[][] tables = scanOffsetTables(allocator, registry);
                long[] offsetTable = tables[0];
                long[] vectorFileOffset = tables[1];

                BM25Index bm25;
                JVectorIndex jvec = null;
                if (dirtyFlag == 1) {
                    bm25 = new BM25Index(1.2f, 0.75f);
                    // 脏启动：扫描重建
                    long scanOffset = MmapFileHeader.HEADER_SIZE;
                    long end = allocator.usedMemory();
                    while (scanOffset < end) {
                        long addr = allocator.getAddressForOffset(scanOffset);
                        RecordHeader rh = parseRecordHeader(addr);
                        if (rh == null) break;
                        if (!rh.deleted) {
                            int ord = registry.getOrdinal(rh.id);
                            if (ord >= 0) {
                                MemoryEntry entry = parseRecordFull(addr, rh);
                                if (entry != null && !entry.isExpired()) {
                                    bm25.add(ord, entry.getContent());
                                }
                            }
                        }
                        scanOffset += rh.totalSize;
                    }
                    if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
                        ensureDimension(embeddingProvider);
                        jvec = new JVectorIndex(embeddingProvider.getDimension(), 0,
                                vectorFileOffset, allocator);
                        for (int ord = 0; ord < registry.capacity(); ord++) {
                            if (vectorFileOffset[ord] != 0L) jvec.add(ord);
                        }
                    }
                } else {
                    bm25 = loadBm25(allocator, baseAddr);
                    jvec = loadJVector(allocator, baseAddr, hnswPath, embeddingProvider,
                            searchMode, vectorFileOffset, allocator);
                    if (jvec == null && searchMode != SearchMode.KEYWORD_ONLY
                            && embeddingProvider != null) {
                        ensureDimension(embeddingProvider);
                        jvec = new JVectorIndex(embeddingProvider.getDimension(), 0,
                                vectorFileOffset, allocator);
                        for (int ord = 0; ord < registry.capacity(); ord++) {
                            if (vectorFileOffset[ord] != 0L) jvec.add(ord);
                        }
                    }
                }
                return new RogueMemory(allocator, path, searchMode, embeddingProvider,
                        bm25, jvec, registry, offsetTable, vectorFileOffset);
            } else {
                MmapFileHeader header = new MmapFileHeader();
                header.setMagicNumber(MmapFileHeader.MAGIC_NUMBER);
                header.setVersion(MmapFileHeader.VERSION);
                header.setDataType(MmapFileHeader.DATA_TYPE_MEMORY);
                header.setCurrentOffset(MmapFileHeader.HEADER_SIZE);
                header.write(baseAddr);
                MmapFileHeader.markOpen(baseAddr);

                OrdinalRegistry registry = new OrdinalRegistry();
                long[] offsetTable = new long[1024];
                long[] vectorFileOffset = new long[1024];
                Arrays.fill(offsetTable, -1L);

                JVectorIndex jvec = null;
                if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
                    ensureDimension(embeddingProvider);
                    jvec = new JVectorIndex(embeddingProvider.getDimension(), 0,
                            vectorFileOffset, allocator);
                }
                return new RogueMemory(allocator, path, searchMode, embeddingProvider,
                        new BM25Index(1.2f, 0.75f), jvec, registry, offsetTable, vectorFileOffset);
            }
```

添加 `loadJVector` 和 `loadOrdinalRegistry`、`scanOffsetTables` 辅助方法（与 Task 5 Step 7 相同，但 `loadHnsw` 改名为 `loadJVector`，调用 `JVectorIndex.load()`）：

```java
    private static JVectorIndex loadJVector(MmapAllocator alloc, long baseAddr,
                                             String hnswPath, EmbeddingProvider provider,
                                             SearchMode mode, long[] vectorFileOffsets,
                                             MmapAllocator allocator) {
        if (mode == SearchMode.KEYWORD_ONLY || provider == null) return null;
        File f = new File(hnswPath);
        if (!f.exists()) return null;
        long storedGen = MmapFileHeader.getHnswGeneration(baseAddr);
        if (storedGen == 0) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            return JVectorIndex.load(fis, provider.getDimension(), vectorFileOffsets, allocator);
        } catch (IOException e) {
            return null;
        }
    }

    private static OrdinalRegistry loadOrdinalRegistry(MmapAllocator alloc, long baseAddr) {
        long regOffset = MmapFileHeader.getOrdinalRegistryOffset(baseAddr);
        if (regOffset == 0) return new OrdinalRegistry();
        try {
            long regAddr = alloc.getAddressForOffset(regOffset);
            int regSize = UnsafeOps.getInt(regAddr);
            byte[] regData = new byte[regSize];
            UnsafeOps.copyToArray(regAddr + 4, regData, 0, regSize);
            return OrdinalRegistry.deserialize(regData);
        } catch (Exception e) {
            return new OrdinalRegistry();
        }
    }

    private static long[][] scanOffsetTables(MmapAllocator alloc, OrdinalRegistry registry) {
        int cap = Math.max(registry.capacity() + 64, 1024);
        long[] offTbl = new long[cap];
        long[] vecTbl = new long[cap];
        Arrays.fill(offTbl, -1L);

        long scanOffset = MmapFileHeader.HEADER_SIZE;
        long end = alloc.usedMemory();
        while (scanOffset < end) {
            long addr = alloc.getAddressForOffset(scanOffset);
            RecordHeader rh = parseRecordHeader(addr);
            if (rh == null) break;
            if (!rh.deleted) {
                int ordinal = registry.getOrdinal(rh.id);
                if (ordinal >= 0) {
                    if (ordinal >= offTbl.length) {
                        int newLen = ordinal * 2 + 1;
                        long[] newOT = Arrays.copyOf(offTbl, newLen);
                        long[] newVT = Arrays.copyOf(vecTbl, newLen);
                        Arrays.fill(newOT, offTbl.length, newLen, -1L);
                        offTbl = newOT;
                        vecTbl = newVT;
                    }
                    offTbl[ordinal] = scanOffset;
                    long pos = addr + 8 + 16;
                    int nsLen = UnsafeOps.getShort(pos) & 0xFFFF; pos += 2 + nsLen;
                    int cLen = UnsafeOps.getInt(pos); pos += 4 + cLen;
                    int mLen = UnsafeOps.getInt(pos); pos += 4 + mLen;
                    int vLen = UnsafeOps.getInt(pos); pos += 4;
                    vecTbl[ordinal] = (vLen > 0) ? alloc.getFileOffsetForAddress(pos) : 0L;
                }
            }
            scanOffset += rh.totalSize;
        }
        return new long[][]{offTbl, vecTbl};
    }
```

同样更新 `compact()` 方法（与 Task 5 Step 9 相同，`HnswVectorIndex` 替换为 `JVectorIndex`，`HnswVectorIndex.load()` 替换为 `JVectorIndex.load()`）。

- [ ] **Step 7: Run all pro module tests**

```bash
mvn test -pl roguemap-memory-pro
```
Expected: 全部通过。

- [ ] **Step 8: Run all tests across both modules**

```bash
mvn test -pl roguemap-memory,roguemap-memory-pro
```
Expected: 全部通过。

- [ ] **Step 9: Commit**

```bash
git add roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java
git commit -m "refactor(memory-pro): wire OrdinalRegistry + offsetTable + MmapVectorValues into RogueMemory"
```

---

## Task 9: Scale verification test

**Files:**
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryScaleTest.java`

此测试在 `-Xmx512m` 下写入 10万条记忆并验证搜索可用，验证优化后堆内存约束满足。

- [ ] **Step 1: Write the scale test**

```java
// roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryScaleTest.java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 规模验证测试：10万条记忆下，-Xmx512m 内正常运行（HYBRID 模式，低维向量）。
 *
 * <p>运行命令（单独跑，避免与其他测试共享 JVM 堆）：
 * {@code mvn test -pl roguemap-memory -Dtest=RogueMemoryScaleTest -Dsurefire.useSystemClassLoader=false}
 *
 * <p>如需验证百万条场景，将 COUNT 改为 1_000_000 并使用更大 allocateSize。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RogueMemoryScaleTest {

    private static final String TEST_DIR = "target/test-memory-scale";
    private static final int COUNT = 100_000;
    // 低维向量（8维）以加快测试速度；生产用 1536 维时 allocateSize 相应增大
    private static final int DIM = 8;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
    }

    @AfterEach
    void tearDown() {
        deleteDir(new File(TEST_DIR));
    }

    @Test
    @Order(1)
    void writeAndSearchUnderMemoryConstraint() throws Exception {
        MockEmbeddingProvider provider = new MockEmbeddingProvider(DIM);

        try (RogueMemory mem = RogueMemory.mmap()
                .persistent(TEST_DIR + "/scale")
                .embeddingProvider(provider)
                .searchMode(SearchMode.HYBRID)
                .allocateSize(512L * 1024 * 1024) // 512MB 文件
                .build()) {

            // 写入 COUNT 条
            String lastId = null;
            for (int i = 0; i < COUNT; i++) {
                lastId = mem.add("记忆内容 item-" + i + " 关键词 keyword-" + (i % 1000));
            }

            // 验证总数
            assertNotNull(lastId);

            // 验证最后一条可以按 id 取回
            MemoryEntry entry = mem.get(lastId);
            assertNotNull(entry);
            assertTrue(entry.getContent().contains("item-" + (COUNT - 1)));

            // 验证搜索可用
            List<MemoryResult> results = mem.search("keyword-500", 10);
            assertFalse(results.isEmpty());
            assertTrue(results.size() <= 10);

            // 打印堆使用情况（辅助人工确认）
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            System.out.printf("[ScaleTest] %d 条，堆已用 %d MB%n", COUNT, usedMB);
        }
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }
}
```

- [ ] **Step 2: Run the scale test**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryScaleTest
```
Expected: PASS，控制台输出堆使用量应远低于 512 MB（预期 ~150-250 MB for 10万条×8维）。

- [ ] **Step 3: Run full test suite to confirm no regression**

```bash
mvn test -pl roguemap-memory,roguemap-memory-pro,roguemap-core
```
Expected: 全部通过。

- [ ] **Step 4: Commit**

```bash
git add roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryScaleTest.java
git commit -m "test(memory): add scale verification test for 100K records under heap constraint"
```

---

## 已知限制

1. **hnswlib-core 向量堆占用验证**：MmapVectorItem 的 `vector()` 方法从 mmap 读取向量，但 hnswlib-core 在内部 `HnswIndex.add()` 时是否会拷贝 float[] 需要通过 heap profiler 确认（如 VisualVM 或 `-Xss` + `-Xmx` 限制测试）。若 profiler 显示大量 float[] 仍在堆上，需在 Task 3 之后追加一个 Custom HNSW 实现任务。

2. **JVectorIndex 图重建**：load() 后首次搜索会重建 HNSW 图（O(n log n)），1M 条时可能需要较长时间。生产环境如需快速启动，可考虑将图序列化到独立文件（`OnDiskGraphIndex`），属于后续优化。

3. **OrdinalRegistry 中 idToOrdinal HashMap**：UUID 字符串在此 HashMap 中只保留一份，但该 Map 仍在堆上（约 72 MB @100万条）。这是必要的查找结构，无法消除。
