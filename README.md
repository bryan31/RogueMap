<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/maven-central/v/com.yomahub/roguemap-core.svg)](https://central.sonatype.com/artifact/com.yomahub/roguemap-core)

[简体中文](README.zh-CN.md) | English

</div>

**RogueMap** is a high-performance embedded storage engine that breaks through the JVM memory wall. Based on memory-mapped files, it provides four off-heap data structures plus an AI memory layer with hybrid vector + keyword search.

## Why RogueMap?

| Feature | Traditional Collections | RogueMap |
|---------|------------------------|----------|
| **Capacity** | Limited by heap size | **Unlimited**, TB-scale |
| **Heap Memory** | 100% | **Only 15.3%** |
| **GC Impact** | Severe (Full GC pauses) | **Minimal** |
| **Persistence** | Not supported | **Supported** |
| **Transactions** | Not supported | **Atomic multi-key ops** |
| **AI Memory** | Not supported | **RogueMemory — hybrid vector + keyword search** |

Traditional Java collections and embedded databases focus solely on key-value or relational storage. RogueMap goes further by providing **RogueMemory** — a built-in AI memory layer with hybrid vector similarity search (ANN) and BM25 keyword retrieval, merged via Reciprocal Rank Fusion. All data is persisted through mmap, requiring no external vector database or search engine dependency.

**RogueMemory is ideal for:**
- **AI Agent long-term memory** — persistent conversation context and user preference recall across sessions
- **RAG (Retrieval-Augmented Generation)** — embedding-based document/knowledge retrieval for LLM applications
- **Semantic search** — "find similar" queries over text, code, or any embeddable content
- **Hybrid retrieval** — combining semantic understanding with exact keyword matching for higher recall accuracy

## Modules

| Module | Java | Description |
|--------|------|-------------|
| `roguemap-core` | 8+ | Core off-heap storage — RogueMap, RogueList, RogueSet, RogueQueue |
| `roguemap-embedding` | 8+ | `UniversalEmbeddingProvider` — zero-dep OpenAI-compatible embedding client |
| `roguemap-memory` | 8+ | AI memory layer with hybrid vector + BM25 search, mmap-backed persistence |

## Features

- **4 Data Structures** — RogueMap, RogueList, RogueSet, RogueQueue
- **Persistence** — Data survives process restarts with crash recovery (CRC32 + generation counter + dirty flag)
- **Auto-Expansion** — Files grow automatically when full
- **Transactions** — Atomic multi-key operations with Read Committed isolation
- **TTL** — Per-entry or default time-to-live on all four data structures
- **Compaction** — Reclaim fragmented space via copy-on-compact
- **Checkpointing** — Manual and automatic (time-interval or operation-count) checkpoint
- **Zero-Copy Serialization** — Direct memory layout for primitives
- **High Concurrency** — 64-segment locking with StampedLock
- **Zero Dependencies** — Core library has no mandatory dependencies
- **AI Memory Layer** — Hybrid vector + BM25 search backed by mmap storage

## Quick Start

### Maven

```xml
<!-- Core off-heap data structures -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-core</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Universal embedding client (zero extra deps) -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-embedding</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- AI memory layer -->
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap-memory</artifactId>
    <version>1.1.0</version>
</dependency>
```

---

## Core Data Structures

### RogueMap — Key-Value Store

```java
// Temporary mode (auto-deleted on JVM exit)
RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
    .temporary()
    .allocateSize(64 * 1024 * 1024L)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

map.put("alice", 100L);
map.get("alice");  // 100L

// Persistent mode with auto-expansion
RogueMap<String, Long> persistentMap = RogueMap.<String, Long>mmap()
    .persistent("data/mydata.db")
    .autoExpand(true)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// Low-heap String key mode (index + key bytes stored in mmap)
RogueMap<String, Long> lowHeapMap = RogueMap.<String, Long>mmap()
    .persistent("data/lowheap.db")
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .lowHeapIndex()
    .build();

// Transaction — atomic multi-key operations
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("key1", 1L);
    txn.put("key2", 2L);
    txn.commit();  // Atomic commit; close() without commit() auto-rolls back
}

// TTL — entry expires after 30 seconds
map.put("session", 42L, 30, TimeUnit.SECONDS);

// Iterate over all entries
map.forEach((key, value) -> System.out.println(key + " = " + value));
```

> `lowHeapIndex()` is String-key-only and does not support `beginTransaction()`.

### RogueList — Doubly-Linked List

```java
RogueList<String> list = RogueList.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

list.addLast("hello");   // O(1) — recommended
list.addLast("world");
list.get(0);             // "hello" — O(1) random access via position index
```

> `addFirst()` / `removeFirst()` are O(n) due to position index shift. Prefer `addLast()` / `removeLast()` for large lists.

### RogueSet — Concurrent Set

```java
RogueSet<String> set = RogueSet.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

set.add("apple");        // true
set.contains("apple");   // true
set.remove("apple");     // true
```

### RogueQueue — FIFO Queue

```java
// Linked mode (unbounded)
RogueQueue<String> queue = RogueQueue.<String>mmap()
    .temporary()
    .linked()
    .elementCodec(StringCodec.INSTANCE)
    .build();

queue.offer("task1");
queue.poll();            // "task1"

// Circular mode (bounded ring buffer)
RogueQueue<Long> circular = RogueQueue.<Long>mmap()
    .persistent("data/queue.db")
    .circular(1024, 64)  // capacity=1024, max element size=64 bytes
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

---

## TTL

All four data structures support time-to-live expiration.

```java
// Default TTL for all entries
RogueMap<String, String> map = RogueMap.<String, String>mmap()
    .temporary()
    .defaultTTL(60, TimeUnit.SECONDS)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(StringCodec.INSTANCE)
    .build();

// Per-entry TTL override (RogueMap only)
map.put("token", "abc123", 30, TimeUnit.SECONDS);
```

---

## Compaction

Append-only allocation accumulates dead bytes on updates/deletes. Use `StorageMetrics` to monitor and `compact()` to reclaim space.

```java
StorageMetrics metrics = map.getMetrics();
System.out.println("Fragmentation: " + metrics.getFragmentationRatio());

if (metrics.shouldCompact(0.5)) {
    map = map.compact(64 * 1024 * 1024L);  // Returns new instance; old is closed
}
```

> `compact()` is not supported in temporary mode or on `CircularQueue`.

---

## Checkpointing

```java
// Manual checkpoint — flush index/metadata to disk
map.checkpoint();

// Auto-checkpoint every 60 seconds
RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
    .persistent("data/mydata.db")
    .autoCheckpoint(60, TimeUnit.SECONDS)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// Auto-checkpoint every 1000 operations
RogueMap<String, Long> map2 = RogueMap.<String, Long>mmap()
    .persistent("data/mydata.db")
    .autoCheckpoint(1000)
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();
```

---

## AI Memory Layer

`roguemap-memory` provides a persistent AI memory store with hybrid vector + BM25 retrieval, backed by mmap storage. It is designed for building long-term memory in AI agents and LLM applications.

### Supported Embedding Services

`UniversalEmbeddingProvider` (from `roguemap-embedding`) works with any service that exposes an OpenAI-compatible `/v1/embeddings` endpoint, using only `HttpURLConnection` — zero extra dependencies.

| Provider | Base URL | Example Models |
|----------|----------|----------------|
| **OpenAI** | `https://api.openai.com/v1` | `text-embedding-3-small` (1536d), `text-embedding-3-large` (3072d), `text-embedding-ada-002` (1536d) |
| **Mistral** | `https://api.mistral.ai/v1` | `mistral-embed` (1024d) |
| **Jina AI** | `https://api.jina.ai/v1` | `jina-embeddings-v3` (1024d), `jina-embeddings-v2-base-en` (768d) |
| **Voyage AI** | `https://api.voyageai.com/v1` | `voyage-3` (1024d), `voyage-3-lite` (512d) |
| **Alibaba DashScope** | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `text-embedding-v3` (1024d), `text-embedding-v2` (1536d) |
| **Zhipu GLM** | `https://open.bigmodel.cn/api/paas/v4` | `embedding-3` (2048d), `embedding-2` (1024d) |
| **Ollama** (OpenAI-compat) | `http://localhost:11434/v1` | `nomic-embed-text` (768d), any local model |
| **vLLM / LocalAI / Together / Fireworks** | custom | any compatible model |

### Dimension Inference

You never need to look up or hard-code a dimension. `UniversalEmbeddingProvider` resolves it automatically in two stages:

1. **Built-in table** — for well-known models (all models in the table above), the dimension is pre-populated at construction time. No network call required.
2. **Auto-detection** — for any model not in the built-in table, the dimension is detected on the first `embed()` call by reading the length of the returned vector, then cached for all subsequent calls.

```java
// OpenAI (default: text-embedding-3-small, dimension resolved from built-in table)
EmbeddingProvider provider = new UniversalEmbeddingProvider(apiKey);

// OpenAI with a specific model
EmbeddingProvider provider = new UniversalEmbeddingProvider(apiKey, "text-embedding-3-large");

// Any compatible service — pass baseUrl + apiKey + model, dimension handled automatically
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "https://api.mistral.ai/v1", apiKey, "mistral-embed");

// Local Ollama with a custom model not in the built-in table — auto-detected on first call
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "http://localhost:11434/v1", "", "my-custom-model");

// Force a specific dimension (e.g. when the service supports truncation)
EmbeddingProvider provider = new UniversalEmbeddingProvider(
    "https://api.openai.com/v1", apiKey, "text-embedding-3-small", 512);

// Check the resolved dimension at any time
System.out.println(provider.getDimension());
```

### RogueMemory

```java
RogueMemory mem = RogueMemory.builder()
    .path("data/mem")
    .searchMode(SearchMode.HYBRID)          // HYBRID | VECTOR_ONLY | KEYWORD_ONLY
    .embeddingProvider(new UniversalEmbeddingProvider(apiKey))
    .build();

// Store a memory with optional metadata and namespace
String id = mem.add("User prefers dark mode", Map.of("source", "settings"), "user-123");

// Search
List<MemoryResult> results = mem.search(SearchOptions.builder()
    .query("user UI preferences")
    .topK(5)
    .namespace("user-123")
    .build());

for (MemoryResult r : results) {
    System.out.println(r.getContent() + " (score=" + r.getScore() + ")");
}

// Delete
mem.delete(id);

mem.close();
```

**Search modes:**
- `HYBRID` (default) — vector ANN + BM25 merged via Reciprocal Rank Fusion; requires `EmbeddingProvider`
- `VECTOR_ONLY` — ANN only; requires `EmbeddingProvider`
- `KEYWORD_ONLY` — BM25 only; no `EmbeddingProvider` needed

---

## Supported Data Types

**Primitives** (zero-copy): `Long`, `Integer`, `Double`, `Float`, `Short`, `Byte`, `Boolean`

**String**: `StringCodec.INSTANCE`

**Objects**: `KryoObjectCodec.create(YourClass.class)` (optional Kryo dependency)

**Complex generics**: `KryoObjectCodec.create(new TypeReference<List<User>>() {})` (optional Kryo dependency)

---

## Requirements

- Java 8+
- Maven 3.6+

## License

[Apache License 2.0](LICENSE)
