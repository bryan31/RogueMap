# RogueMemory 设计文档

**日期：** 2026-03-23
**状态：** 待实现

---

## 一、背景与目标

RogueMap 是一个基于内存映射文件的嵌入式高性能存储库。本次新增第五种数据结构 `RogueMemory`，专为 AI 场景中的记忆存储与语义检索设计。

**核心需求：**
- 支持语义搜索（「红衣服」能搜出「鲜艳的衣服」）
- 支持关键词精确匹配（人名、手机号、术语等）
- 支持按 namespace 和 metadata 过滤
- 嵌入式，无需外部服务（向量数据库服务器）
- 符合 RogueMap 现有使用风格（Builder、TTL、checkpoint、compact）

---

## 二、模块结构

现有单模块项目拆分为 Maven 多模块：

```
roguemap/                          (parent pom，聚合模块)
├── roguemap-core/                 (Java 8+，现有四种数据结构原样迁移)
├── roguemap-memory/               (Java 8+，向量索引用 jelmerk/hnswlib-core)
└── roguemap-memory-pro/         (Java 11+，向量索引用 datastax/jvector，其余与上模块完全一致)
```

**设计原则：两个 memory 模块公开 API 完全相同，用户换依赖无需改代码。**

差异仅在 VectorIndex 实现层：

| 模块 | 向量索引实现 | Java 要求 | 适用场景 |
|---|---|---|---|
| `roguemap-memory` | `HnswVectorIndex`（jelmerk/hnswlib-core） | Java 8+ | 兼容性优先 |
| `roguemap-memory-pro` | `JVectorIndex`（datastax/jvector） | Java 11+ | 性能优先，大规模向量场景 |

BM25Index、EmbeddingProvider、RogueMemory、Tokenizer、SearchOptions、MemoryResult 等所有非向量索引代码在两个模块中保持完全一致。

**parent pom 结构：**
```xml
<modules>
    <module>roguemap-core</module>
    <module>roguemap-memory</module>
    <module>roguemap-memory-pro</module>
</modules>
```

`roguemap-core` 与 `roguemap-memory` 保持 `<source>8</source> <target>8</target>`。
`roguemap-memory-pro` 设置 `<source>11</source> <target>11</target>`，并激活 `--add-opens` profile。

**用户引入方式：**
```xml
<!-- 只用核心功能，Java 8 完全兼容 -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-core</artifactId>
</dependency>

<!-- AI 记忆功能，Java 8+（兼容性优先） -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-memory</artifactId>
</dependency>

<!-- AI 记忆功能，Java 11+（性能优先） -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-memory-pro</artifactId>
</dependency>
```

---

## 三、新增文件结构

两个 memory 模块目录结构完全对称，差异仅在 `index/` 下的向量索引实现：

```
roguemap-memory/src/main/java/com/yomahub/roguemap/memory/
├── RogueMemory.java                   # 主类 + MmapBuilder 内部类
├── MemoryEntry.java                   # 内部数据模型
├── MemoryResult.java                  # 搜索结果（id、content、metadata、namespace、score）
├── SearchOptions.java                 # 搜索过滤参数（namespace、metadata filter、RRF 常数）
├── SearchMode.java                    # 枚举：HYBRID、VECTOR_ONLY、KEYWORD_ONLY
├── embedding/
│   ├── EmbeddingProvider.java
│   ├── OpenAIEmbeddingProvider.java
│   └── OllamaEmbeddingProvider.java
├── index/
│   ├── VectorIndex.java               # 接口（两模块共用相同定义）
│   ├── HnswVectorIndex.java           # ← roguemap-memory 专属：jelmerk/hnswlib-core 实现
│   └── BM25Index.java
└── util/
    └── Tokenizer.java

roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/
├── RogueMemory.java                   # 与 roguemap-memory 相同
├── MemoryEntry.java
├── MemoryResult.java
├── SearchOptions.java
├── SearchMode.java
├── embedding/
│   ├── EmbeddingProvider.java
│   ├── OpenAIEmbeddingProvider.java
│   └── OllamaEmbeddingProvider.java
├── index/
│   ├── VectorIndex.java
│   ├── JVectorIndex.java              # ← roguemap-memory-pro 专属：datastax/jvector 实现
│   └── BM25Index.java
└── util/
    └── Tokenizer.java
```

---

## 四、存储文件结构

每个 `RogueMemory` 实例对应**两个文件**：

```
{name}.mem    mmap 文件，存储所有 document 记录（含原始向量）
              close() 时将 BM25 倒排索引序列化到 allocate() 分配的区域
              文件头使用 MmapFileHeader（dataType = MEMORY(5)）
              文件头新增字段：bm25IndexOffset（8 bytes）

{name}.hnsw   jvector 原生序列化格式，存储 HNSW 图结构（可重建的缓存）
              close() / checkpoint() 时写入
```

**document 记录二进制格式（mmap 内）：**

```
[expireTime:    8 bytes  long，绝对时间戳，0 = 永不过期]
[id:           16 bytes  UUID raw bytes（两个 long，节省空间）]
[ns_len:        2 bytes  short（namespace 最大 32,767 字节，文档中明确此上限）]
[namespace:    ns_len bytes  UTF-8]
[content_len:   4 bytes  int]
[content:      content_len bytes  UTF-8]
[meta_len:      4 bytes  int]
[metadata:     meta_len bytes  自定义简单编码，见下文]
[vector_len:    4 bytes  int（float 数组元素个数）]
[vector:       vector_len × 4 bytes  float[]，小端序]
[deleted:       1 byte   0 = 正常，1 = 已删除（tombstone）]
```

**向量存储在记录内的设计原因：**
向量是 HNSW 索引的源数据。将向量存进 mmap 文件，使得 HNSW 图损坏或 `.hnsw` 文件丢失时，可从 mmap 数据区扫描重建，无需重新调用 EmbeddingProvider，保证了 crash recovery 的完整性。

**metadata 编码（零依赖，不引入 JSON 库）：**
仅支持 `Map<String, String>`，使用简单的键值对编码：
```
[pair_count: 2 bytes short]
[key_len: 2 bytes][key: UTF-8][val_len: 2 bytes][val: UTF-8]
...
```

---

## 五、内存索引结构

```
ConcurrentHashMap<String, Long>    id → mmap 地址（按 id 快速定位记录）
ConcurrentHashMap<String, Integer> id → jvector nodeId（字符串 id 与向量索引的桥接）
ConcurrentHashSet<Integer>         deletedNodeIds（已删除节点的 tombstone 集合）
HnswVectorIndex                    封装 jvector GraphIndex，提供 search / add
BM25Index                          倒排索引，内存维护，close() 时持久化
```

**删除与更新的 HNSW 处理：**
jvector 的 `GraphIndex` 不原生支持删除。采用 tombstone 方案：
- `delete(id)` / `update(id, ...)` 将对应 nodeId 加入 `deletedNodeIds`，并在 mmap 记录中设 `deleted = 1`
- `search()` 时对 HNSW 返回的候选集做后过滤，跳过 `deletedNodeIds` 中的节点
- `compact()` 时重建 HNSW 图，仅包含存活节点，清空 tombstone 集合

---

## 六、EmbeddingProvider 接口

```java
public interface EmbeddingProvider {
    float[] embed(String text);
    int getDimension();
}
```

`RogueMemory` 在 `build()` 时调用 `provider.getDimension()` 自动获取向量维度，用户无需手动指定。

`SearchMode.KEYWORD_ONLY` 模式下，`embeddingProvider` 为可选配置，不传则跳过向量相关逻辑。

**内置实现（均使用 Java 原生 `HttpURLConnection`，不引入额外 HTTP 依赖）：**

| 实现类 | 说明 |
|---|---|
| `OpenAIEmbeddingProvider(apiKey, model)` | 调用 OpenAI Embeddings API，`baseUrl` 可自定义（支持 Azure、本地代理） |
| `OllamaEmbeddingProvider(baseUrl, model)` | 调用本地 Ollama API，完全离线 |

---

## 七、BM25 与中文分词

**分词策略（`Tokenizer`）：**
- 检测文本中 CJK Unicode 字符（\u4E00–\u9FFF 等区段）个数占总字符数 > 50%，使用**字符 Bigram**（双字滑动窗口）
- 否则使用空格分词并转小写（英文）
- 无外部依赖

**示例：**
```
"我有一件红衣服" → ["我有", "有一", "一件", "件红", "红衣", "衣服"]
"red dress"     → ["red", "dress"]
```

**BM25 参数（有默认值，可通过 builder 覆盖）：**
- `k1 = 1.2`（词频饱和系数，适合短文本记忆片段）
- `b = 0.75`（文档长度归一化系数）

**BM25Index 持久化：**
- `close()` / `checkpoint()` 时：调用 `allocate(bm25Size)` 获取物理地址，将 BM25 序列化写入该地址，将文件偏移量存入 `MmapFileHeader.bm25IndexOffset` 字段
- 重新打开时：从 `header.bm25IndexOffset` 读取并反序列化
- 与现有 `saveMmapIndex()` / `restoreIndex()` 机制完全一致

---

## 八、双文件一致性与 crash recovery

**正常 close() / checkpoint() 顺序：**
1. 设置 `.mem` 文件头 `dirtyFlag = 1`、`writeGen` 奇数（写入开始）
2. 将 BM25 序列化写入 `.mem`，更新 `bm25IndexOffset`
3. 将 HNSW 图序列化写入 `.hnsw`（文件内写入 generation 号，与 `.mem` 头中存储的 `hnswGeneration` 字段一致）
4. 设置 `.mem` 文件头 `dirtyFlag = 0`、`writeGen` 偶数（写入完成）
5. `MappedByteBuffer.force()` 强制刷盘

**crash recovery 策略（打开时检测）：**

| `.mem` dirtyFlag | `.hnsw` 状态 | 恢复动作 |
|---|---|---|
| 0（正常关闭） | generation 匹配 | 直接加载，无需重建 |
| 0（正常关闭） | generation 不匹配 / 文件损坏 | 从 `.mem` 向量数据区重建 HNSW |
| 1（异常退出） | 任意 | 扫描 `.mem` 数据区重建全部内存索引（id→address、id→nodeId、BM25、HNSW） |

**关键保证：** 向量数据存储在 `.mem` 文件中，HNSW 图始终可从向量重建，无需调用 EmbeddingProvider。

---

## 九、混合搜索流程

```
查询文本
  │
  ├─→ EmbeddingProvider.embed(query) → float[]
  │     └─→ HnswVectorIndex.search(vector, k×2)
  │           → 后过滤 deletedNodeIds
  │           → List<(id, 向量排名)>
  │
  ├─→ Tokenizer.tokenize(query) → List<String>
  │     └─→ BM25Index.search(tokens, k×2) → List<(id, BM25 排名)>
  │
  ├─→ RRF 合并
  │     score = 1/(rank_vector + C) + 1/(rank_bm25 + C)
  │     C 默认 60，可通过 SearchOptions.rrfConstant(int) 覆盖
  │
  ├─→ 按 namespace 过滤
  ├─→ 按 metadata filter 过滤（精确匹配 key=value）
  ├─→ 按 TTL 过滤（跳过已过期条目）
  │
  └─→ 返回 top-k List<MemoryResult>
```

`VECTOR_ONLY` / `KEYWORD_ONLY` 模式跳过对应分支。

---

## 十、线程安全

- `id→address` / `id→nodeId` 使用 `ConcurrentHashMap`，线程安全
- `deletedNodeIds` 使用 `ConcurrentHashSet`（`Collections.newSetFromMap(new ConcurrentHashMap<>())`）
- jvector `GraphIndexBuilder` 的 `addGraphNode()` 是线程安全的，支持并发写入
- jvector `GraphIndex.search()` 是线程安全的，支持并发读取
- BM25Index 的写操作（add/delete）通过 `ReentrantReadWriteLock` 保护

---

## 十一、API 设计

**构建：**
```java
// HYBRID 模式（默认，需要 EmbeddingProvider）
RogueMemory memory = RogueMemory.mmap()
    .persistent("/data/memory")
    .embeddingProvider(new OpenAIEmbeddingProvider(apiKey, "text-embedding-3-small"))
    .searchMode(SearchMode.HYBRID)          // 默认 HYBRID
    .bm25k1(1.2).bm25b(0.75)               // 可选，覆盖 BM25 默认参数
    .defaultTTL(30, TimeUnit.DAYS)
    .autoExpand(true)
    .autoCheckpoint(5, TimeUnit.MINUTES)
    .build();

// KEYWORD_ONLY 模式（不需要 EmbeddingProvider）
RogueMemory memory = RogueMemory.mmap()
    .persistent("/data/memory")
    .searchMode(SearchMode.KEYWORD_ONLY)
    .build();
```

**写入（三种重载）：**
```java
String id = memory.add("我有一件红衣服");
String id = memory.add("我有一件红衣服", Map.of("userId", "u123"));
String id = memory.add("我有一件红衣服", Map.of("userId", "u123"), "session-42");
// 返回自动生成的 UUID 字符串，供后续 delete/update 使用
```

**搜索（两种重载）：**
```java
List<MemoryResult> results = memory.search("鲜艳的衣服", 5);

List<MemoryResult> results = memory.search("鲜艳的衣服", 5,
    SearchOptions.builder()
        .namespace("session-42")           // 仅搜索该 namespace
        .filter("userId", "u123")          // metadata 精确过滤
        .rrfConstant(60)                   // 可选，RRF 调参
        .build());
```

**其他操作：**
```java
MemoryEntry entry  = memory.get(id);        // 按 id 精确查询
memory.update(id, "我有两件红衣服");           // 重新 embed，保留 metadata 和 namespace
memory.delete(id);                           // tombstone 标记，compact() 时物理清除
memory.checkpoint();
memory.compact(allocSize);                   // 重建 .mem（清除 tombstone）+ 重建 .hnsw
StorageMetrics metrics = memory.getMetrics();
memory.close();  // AutoCloseable
```

**MemoryResult 字段：**
```java
public class MemoryResult {
    String id;
    String content;
    Map<String, String> metadata;
    String namespace;
    float score;       // RRF 合并分数（或单路分数）
    long createdAt;    // 写入时间戳（毫秒）
    long expireTime;   // 0 = 永不过期
}
```

---

## 十二、持久化与恢复汇总

| 时机 | 操作 |
|---|---|
| `close()` / `checkpoint()` | 按 §八 顺序写入 BM25 + HNSW，更新文件头 |
| 正常重新打开 | 从 `bm25IndexOffset` 加载 BM25；加载 `.hnsw`；重建 id→address、id→nodeId |
| `.hnsw` 损坏 / generation 不匹配 | 从 `.mem` 向量字段重建 HNSW（无需调用 EmbeddingProvider） |
| dirty flag = 1 | 全量扫描 `.mem` 数据区重建所有索引 |

---

## 十三、依赖说明

**`roguemap-memory`（Java 8+）：**

| 依赖 | 作用 |
|---|---|
| `com.github.jelmerk:hnswlib-core` | HNSW 向量索引（Java 8 兼容） |
| `roguemap-core` | 存储引擎、mmap 分配器 |

**`roguemap-memory-pro`（Java 11+）：**

| 依赖 | 作用 |
|---|---|
| `io.github.jbellis:jvector` | HNSW+DiskANN 向量索引（Panama SIMD 加速） |
| `roguemap-core` | 存储引擎、mmap 分配器 |

`roguemap-core` 依赖不变，零强制依赖。

---

## 十四、不在本期实现的内容

- 多向量支持（一条记忆对应多个 embedding）
- 中文分词使用 jieba 等专业分词库（可作为可选依赖后续支持）
- CircularQueue 式的固定容量记忆窗口
- 向量量化压缩（jvector 支持 PQ，可后续开启）
