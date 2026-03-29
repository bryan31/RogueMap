<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/maven-central/v/com.yomahub/roguemap-core.svg)](https://central.sonatype.com/artifact/com.yomahub/roguemap-core)

简体中文 | [English](README.md)

</div>

**RogueMap** 是一个高性能的嵌入式存储引擎库，突破 JVM 内存墙。基于内存映射文件，提供四种堆外数据结构，同时配备支持向量 + 关键词混合检索的 AI 记忆层。

## 为什么选择 RogueMap？

| 特性 | 传统集合 | RogueMap |
|------|---------|----------|
| **数据容量** | 受限于堆大小 | **无限制**，可达 TB 级 |
| **堆内存占用** | 100% | **仅 15.3%** |
| **GC 影响** | 严重（Full GC 停顿） | **几乎无影响** |
| **持久化** | 不支持 | **支持** |
| **事务** | 不支持 | **原子多键操作** |

## 模块说明

| 模块 | Java 版本 | 说明 |
|------|-----------|------|
| `roguemap-core` | 8+ | 核心堆外存储 — RogueMap、RogueList、RogueSet、RogueQueue |
| `roguemap-embedding` | 8+ | `UniversalEmbeddingProvider` — 零依赖 OpenAI 兼容 Embedding 客户端 |
| `roguemap-memory` | 8+ | AI 记忆层，向量 + BM25 混合检索，基于 mmap 持久化 |

## 特性

- **四种数据结构** — RogueMap、RogueList、RogueSet、RogueQueue
- **持久化支持** — 进程重启后数据自动恢复，支持崩溃恢复（CRC32 校验 + 写入代数 + 脏标志）
- **自动扩容** — 文件写满自动增长
- **事务支持** — 原子多键操作，Read Committed 隔离级别
- **TTL 过期** — 所有四种数据结构均支持默认或单条 TTL
- **碎片整理** — Copy-on-Compact 方式回收碎片空间
- **检查点** — 手动及自动（时间间隔或操作次数）触发检查点
- **零拷贝序列化** — 原始类型直接内存布局
- **高并发支持** — 64 段分段锁，StampedLock 乐观读
- **零依赖** — 核心库无第三方依赖
- **AI 记忆层** — 向量 + BM25 混合检索，基于 mmap 持久化存储

## 快速开始

### Maven 依赖

```xml
<!-- 核心堆外数据结构 -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-core</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- 通用 Embedding 客户端（零额外依赖） -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-embedding</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- AI 记忆层 -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-memory</artifactId>
    <version>1.1.0</version>
</dependency>
```

---

## 核心数据结构

### RogueMap — 键值存储

```java
// 临时文件模式（JVM 关闭后自动删除）
RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
    .temporary()
    .allocateSize(64 * 1024 * 1024L)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

map.put("alice", 100L);
map.get("alice");  // 100L

// 持久化模式 + 自动扩容
RogueMap<String, Long> persistentMap = RogueMap.<String, Long>mmap()
    .persistent("data/mydata.db")
    .autoExpand(true)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// 超低堆 String Key 模式（索引与 key 字节放在 mmap）
RogueMap<String, Long> lowHeapMap = RogueMap.<String, Long>mmap()
    .persistent("data/lowheap.db")
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .lowHeapIndex()
    .build();

// 事务 — 原子多键操作
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("key1", 1L);
    txn.put("key2", 2L);
    txn.commit();  // 原子提交；close() 未 commit 自动回滚
}

// TTL — 30 秒后过期
map.put("session", 42L, 30, TimeUnit.SECONDS);

// 遍历所有键值对
map.forEach((key, value) -> System.out.println(key + " = " + value));
```

> `lowHeapIndex()` 仅支持 `StringCodec.INSTANCE`，不支持 `beginTransaction()`。

### RogueList — 双向链表

```java
RogueList<String> list = RogueList.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

list.addLast("hello");   // O(1) — 推荐
list.addLast("world");
list.get(0);             // "hello" — O(1) 随机访问（位置索引）
```

> `addFirst()` / `removeFirst()` 因位置索引移位为 O(n)，大列表请优先使用 `addLast()` / `removeLast()`。

### RogueSet — 并发集合

```java
RogueSet<String> set = RogueSet.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

set.add("apple");        // true
set.contains("apple");   // true
set.remove("apple");     // true
```

### RogueQueue — FIFO 队列

```java
// 链表模式（无界）
RogueQueue<String> queue = RogueQueue.<String>mmap()
    .temporary()
    .linked()
    .elementCodec(StringCodec.INSTANCE)
    .build();

queue.offer("task1");
queue.poll();            // "task1"

// 环形缓冲区模式（有界）
RogueQueue<Long> circular = RogueQueue.<Long>mmap()
    .persistent("data/queue.db")
    .circular(1024, 64)  // 容量=1024，最大元素=64字节
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

---

## TTL 过期

所有四种数据结构均支持 TTL。

```java
// 全局默认 TTL
RogueMap<String, String> map = RogueMap.<String, String>mmap()
    .temporary()
    .defaultTTL(60, TimeUnit.SECONDS)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(StringCodec.INSTANCE)
    .build();

// 单条 TTL（仅 RogueMap 支持）
map.put("token", "abc123", 30, TimeUnit.SECONDS);
```

---

## 碎片整理

追加写入会在更新/删除后积累死字节。使用 `StorageMetrics` 监控碎片率，使用 `compact()` 回收空间。

```java
StorageMetrics metrics = map.getMetrics();
System.out.println("碎片率: " + metrics.getFragmentationRatio());

if (metrics.shouldCompact(0.5)) {
    map = map.compact(64 * 1024 * 1024L);  // 返回新实例，旧实例自动关闭
}
```

> `compact()` 不支持临时模式和环形队列（`CircularQueue`）。

---

## 检查点

```java
// 手动检查点 — 将索引/元数据强制刷盘
map.checkpoint();

// 自动检查点 — 每 60 秒触发一次
RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
    .persistent("data/mydata.db")
    .autoCheckpoint(60, TimeUnit.SECONDS)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// 自动检查点 — 每 1000 次操作触发一次
RogueMap<String, Long> map2 = RogueMap.<String, Long>mmap()
    .persistent("data/mydata.db")
    .autoCheckpoint(1000)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();
```

---

## AI 记忆层

`roguemap-memory` 提供持久化 AI 记忆存储，支持向量 + BM25 混合检索，底层基于 mmap 存储。专为 AI Agent 和 LLM 应用的长期记忆场景设计。

### 支持的 Embedding 服务与模型

`UniversalEmbeddingProvider`（来自 `roguemap-embedding`）兼容所有实现了 OpenAI `/v1/embeddings` 协议的服务，仅使用 `HttpURLConnection`，零额外依赖。

| 服务商 | Base URL | 常用模型 |
|--------|----------|----------|
| **OpenAI** | `https://api.openai.com/v1` | `text-embedding-3-small`（1536维）、`text-embedding-3-large`（3072维）、`text-embedding-ada-002`（1536维） |
| **Mistral** | `https://api.mistral.ai/v1` | `mistral-embed`（1024维） |
| **Jina AI** | `https://api.jina.ai/v1` | `jina-embeddings-v3`（1024维）、`jina-embeddings-v2-base-en`（768维） |
| **Voyage AI** | `https://api.voyageai.com/v1` | `voyage-3`（1024维）、`voyage-3-lite`（512维） |
| **阿里云百炼（DashScope）** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `text-embedding-v3`（1024维）、`text-embedding-v2`（1536维） |
| **智谱 GLM** | `https://open.bigmodel.cn/api/paas/v4` | `embedding-3`（2048维）、`embedding-2`（1024维） |
| **Ollama**（OpenAI 兼容模式） | `http://localhost:11434/v1` | `nomic-embed-text`（768维）及任意本地模型 |
| **vLLM / LocalAI / Together / Fireworks** | 自定义 | 任意兼容模型 |

### 维度推断机制

你不需要自己查阅或硬编码任何维度数值。`UniversalEmbeddingProvider` 会通过以下两个阶段自动解决：

1. **内置表** — 对上表中所有已知模型，维度在构造时直接从内置表读取，无需任何网络请求。
2. **自动探测** — 对不在内置表中的模型，首次 `embed()` 调用时通过读取返回向量的长度自动确定维度，并缓存供后续使用。

```java
// OpenAI（默认：text-embedding-3-small，维度从内置表读取）
EmbeddingProvider provider = new UniversalEmbeddingProvider(apiKey);

// OpenAI 指定模型
EmbeddingProvider provider = new UniversalEmbeddingProvider(apiKey, "text-embedding-3-large");

// 任意兼容服务 — 只需传 baseUrl + apiKey + model，维度自动处理
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "https://api.mistral.ai/v1", apiKey, "mistral-embed");

// 本地 Ollama 自定义模型（不在内置表中）— 首次调用时自动探测
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "http://localhost:11434/v1", "", "my-custom-model");

// 强制指定维度（例如服务支持截断时）
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "https://api.openai.com/v1", apiKey, "text-embedding-3-small", 512);

// 随时查看已解析的维度
System.out.println(provider.getDimension());
```

### RogueMemory

```java
RogueMemory mem = RogueMemory.builder()
    .path("data/mem")
    .searchMode(SearchMode.HYBRID)          // HYBRID | VECTOR_ONLY | KEYWORD_ONLY
    .embeddingProvider(new UniversalEmbeddingProvider(apiKey))
    .build();

// 存入记忆（支持元数据与命名空间）
String id = mem.add("用户偏好深色模式", Map.of("source", "settings"), "user-123");

// 检索
List<MemoryResult> results = mem.search(SearchOptions.builder()
    .query("用户界面偏好")
    .topK(5)
    .namespace("user-123")
    .build());

for (MemoryResult r : results) {
    System.out.println(r.getContent() + " (score=" + r.getScore() + ")");
}

// 删除
mem.delete(id);

mem.close();
```

**检索模式：**
- `HYBRID`（默认）— 向量 ANN + BM25，通过 RRF 合并结果；需要 `EmbeddingProvider`
- `VECTOR_ONLY` — 纯 ANN 检索；需要 `EmbeddingProvider`
- `KEYWORD_ONLY` — 纯 BM25 检索；无需 `EmbeddingProvider`

---

## 支持的数据类型

**原始类型**（零拷贝）：`Long`、`Integer`、`Double`、`Float`、`Short`、`Byte`、`Boolean`

**字符串**：`StringCodec.INSTANCE`

**对象**：`KryoObjectCodec.create(YourClass.class)`（可选 Kryo 依赖）

**复杂泛型**：`KryoObjectCodec.create(new TypeReference<List<User>>() {})`（可选 Kryo 依赖）

---

## 系统要求

- Java 8+
- Maven 3.6+

## 许可证

[Apache License 2.0](LICENSE)
