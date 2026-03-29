# RogueMemory 低堆内存优化设计

**日期**：2026-03-29
**目标**：在保持现有 API 不变的前提下，将 RogueMemory 百万条记忆场景下的堆内存从 ~20 GB+ 降至 ~580 MB。
**范围**：`roguemap-memory`（hnswlib-core）和 `roguemap-memory-pro`（jvector）两个模块同步优化。

---

## 问题分析

当前堆内存主要消耗（@100万条，1536维向量）：

| 组件 | 数据结构 | 堆占用 |
|---|---|---|
| BM25 `invertedIndex` | `Map<String(UUID), Map<String(UUID), Integer>>` | ~14 GB |
| HNSW 向量 | hnswlib-core/jvector 内部 float[] | ~6 GB |
| HNSW 图结构（邻居列表） | String UUID 节点 ID | ~300 MB |
| `idToFileOffset` | `ConcurrentHashMap<String, Long>` | ~150 MB |
| BM25 `docLengths` | `HashMap<String, Integer>` | ~120 MB |

根本原因：
1. BM25 每条 posting 存完整 UUID 字符串（36 字符，Java 对象 ~96B），在百万级场景无限膨胀。
2. HNSW 把所有向量完整加载到堆内，而向量数据已经存在 mmap 文件中，形成冗余。

---

## 优化方案

### 核心思路

1. **UUID → int ordinal**：引入全局序号注册表，所有内部索引结构改用 4 字节 int 序号代替 36 字符 UUID 字符串。
2. **向量不进堆**：HNSW 搜索时直接从 mmap 读取向量，堆上只保留图的邻居结构。

### 优化后堆内存估算（@100万条）

| 组件 | 优化后 |
|---|---|
| BM25 invertedIndex（int[]） | ~200 MB |
| HNSW 图结构（邻居列表） | ~300 MB（不变） |
| HNSW 向量 | **0**（读 mmap） |
| OrdinalRegistry | ~72 MB（UUID 字符串集中存一份） |
| offsetTable（long[]） | ~8 MB |
| **合计** | **~580 MB** |

---

## 详细设计

### 1. OrdinalRegistry

新增类，是所有优化的基础。负责维护 UUID ↔ int ordinal 双向映射。

```java
class OrdinalRegistry {
    private String[] idTable;                     // ordinal → UUID（数组，O(1) 反查）
    private final Map<String, Integer> idToOrdinal; // UUID → ordinal
    private final IntDeque freeList;               // 已删除 ordinal 待复用
    private int nextOrdinal;
    private final ReentrantReadWriteLock lock;
}
```

**关键操作：**
- `register(uuid)` → 优先从 freeList 复用 ordinal，否则 nextOrdinal++
- `release(uuid)` → 从 idToOrdinal 移除，idTable[ordinal] = null，ordinal 入 freeList
- `getOrdinal(uuid)` → O(1) 正查
- `getId(ordinal)` → O(1) 反查

**持久化格式**（紧接 BM25 数据之后写入 mmap 文件尾）：

```
[count: 4B][ordinal: 4B][uuid_msb: 8B][uuid_lsb: 8B]...
```

UUID 用 16 字节 binary 存储（msb + lsb），而非 36 字节字符串，节省 55% 空间。

**线程安全**：读多写少，`register/release` 持写锁，`getOrdinal/getId` 持读锁。

---

### 2. BM25Index 重构

**核心变化**：所有 `String docId` 替换为 `int ordinal`。

```java
class BM25Index {
    private final Map<String, int[]> ordinalIndex; // term → ordinal 数组
    private final Map<String, int[]> tfIndex;      // term → tf 数组（与 ordinalIndex 并行）
    private int[] docLengths;                      // ordinal → 文档词数
    private final Map<String, Integer> docFreqs;   // term → df（不变）
    private int docCount;
}
```

**内存节省来源**：
- 每条 posting 从 String UUID（~96B）缩减为 int（4B），节省 **24 倍**
- `docLengths` 从 `HashMap<String, Integer>`（~120 MB @100万）变为 `int[]`（~4 MB）

**删除处理**：`docLengths[ordinal] = -1` 标记已删，搜索时跳过；posting list 里的失效 ordinal 在 `compact()` 时清理。

**序列化格式**：改为自定义二进制格式（int[] 写入），替代原有 Java ObjectOutputStream（HashMap<String,...>），反序列化更快，体积更小。

---

### 3. idToFileOffset 压缩

```java
// 原来：
ConcurrentHashMap<String, Long> idToFileOffset;  // ~150 MB @100万

// 优化后：
long[] offsetTable;  // ordinal → file offset，~8 MB @100万
// offsetTable[ordinal] = -1L 表示未分配或已删除
```

访问路径：`get(uuid)` → `registry.getOrdinal(uuid)` → `offsetTable[ordinal]`，仍是 O(1)。

---

### 4. 向量堆外化 — roguemap-memory（hnswlib-core）

**MmapVectorItem**：替换现有 `VectorItem`，不存 `float[]`，只存 ordinal。

```java
class MmapVectorItem implements Item<Integer, float[]>, Serializable {
    private final int ordinal;
    private final long[] offsetTable;    // 共享引用
    private final MmapAllocator allocator;

    @Override
    public Integer id() { return ordinal; }

    @Override
    public float[] vector() {
        long fileOffset = offsetTable[ordinal];
        long addr = allocator.getAddressForOffset(fileOffset);
        return readVectorFromAddr(addr);  // 跳过 record header，读取 vector 段
    }
}
```

hnswlib-core 每次计算距离时调用 `item.vector()`，向量惰性从 mmap 读取。

**风险与退路**：若 hnswlib-core 在 `add()` 时内部拷贝 float[]（而非持有 Item 引用），则退路是自实现轻量 HNSW：
- 图结构：`int[][] neighbors`（按层存储邻居 ordinal，紧凑 int 数组）
- 向量：全从 mmap 读，无堆占用
- 工作量约 500 行

**验证方式**：实现后用 JVM heap profiler 确认 HNSW 内部 `float[]` 占用是否消失。

**其他变化**：
- HNSW 节点 ID 类型 `String` → `Integer`（ordinal）
- `deletedIds: Set<String>` → `BitSet deletedOrdinals`（100万条 ~125 KB vs ~100 MB）

---

### 5. 向量堆外化 — roguemap-memory-pro（jvector）

jvector 原生支持 `RandomAccessVectorValues` 接口，天然适配此方案，**无需修改 jvector 内部**。

```java
class MmapVectorValues implements RandomAccessVectorValues {
    private final long[] offsetTable;
    private final MmapAllocator allocator;
    private final int dimension;

    @Override
    public float[] getVector(int ordinal) {
        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        return readVectorFromAddr(addr);
    }

    @Override
    public void getVectorInto(int ordinal, float[] buffer, int offset) {
        // 直接写入调用方 buffer，零分配——jvector 搜索热路径走此方法
        long addr = allocator.getAddressForOffset(offsetTable[ordinal]);
        readVectorIntoBuffer(addr, buffer, offset);
    }
}
```

`GraphIndexBuilder` 构造时传入 `MmapVectorValues`，搜索时按需回调读取。

---

### 6. 持久化格式变更

**文件头扩展字段**（复用现有 Reserved 区域，offset 96-4095）：

```
offset  96: bm25IndexOffset        (8B long) — 已有
offset 104: hnswGeneration         (8B long) — 已有
offset 112: ordinalRegistryOffset  (8B long) — 新增
```

**saveIndexes() 保存顺序**：
1. 序列化 BM25（新格式 int[]）→ allocate → 写入 mmap → 记录 bm25Offset
2. 序列化 OrdinalRegistry → allocate → 写入 mmap → 记录 registryOffset
3. 序列化 HNSW → 写 `.hnsw` 文件（格式变为 Integer ID，不再含 String）
4. 更新文件头

**兼容性**：不兼容旧格式文件，新版本直接只支持新格式。

---

### 7. 数据流变化（add 为例）

```
add(content, metadata, namespace)
  1. uuid = UUID.randomUUID()
  2. ordinal = registry.register(uuid)
  3. vector = embeddingProvider.embed(content)
  4. fileOffset = writeRecord(uuid, content, metadata, namespace, vector)
  5. offsetTable[ordinal] = fileOffset
  6. bm25Index.add(ordinal, content)
  7. vectorIndex.add(ordinal, new MmapVectorItem(ordinal, offsetTable, allocator))
```

---

## 测试策略

| 测试类 | 覆盖点 |
|---|---|
| `OrdinalRegistryTest` | register / release / ordinal 复用 / 序列化反序列化 |
| `BM25IndexRefactorTest` | int 编号下 add/delete/search 结果与原实现语义一致 |
| `MmapVectorItemTest` | vector() 读取正确性；heap profiler 确认无 float[] 堆积 |
| `RogueMemoryScaleTest` | 10万条下堆内存基准（JVM `-Xmx` 限制验证） |
| `RogueMemoryFunctionalTest` | 现有功能回归（search/add/delete/compact/checkpoint） |

---

## 约束与注意事项

- `OrdinalRegistry` 中的 `idToOrdinal`（HashMap）仍在堆上，但 UUID 字符串只存一份（不再在 BM25 每条 posting 重复），这是无法消除的必要开销。
- 向量从 mmap 读取依赖 OS page cache 命中，冷启动首次搜索会有缺页开销；生产环境建议预热。
- HNSW 图结构（邻居列表）仍在堆上（~300 MB @100万），这是 ANN 算法的必要代价，无法堆外化。
- `offsetTable` 按 ordinal 顺序索引，ordinal 复用（freeList）时需保证 offsetTable[ordinal] 被正确重置。
