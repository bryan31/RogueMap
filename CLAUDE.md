# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Compile the project (all modules)
mvn clean compile

# Run all tests (all modules)
mvn test

# Run tests for a specific module
mvn test -pl roguemap-core
mvn test -pl roguemap-memory
mvn test -pl roguemap-memory-pro
mvn test -pl roguemap-embedding

# Run a specific test class
mvn test -Dtest=MapFunctionalTest

# Run multiple test classes
mvn test -Dtest=LinkedQueueFreeListTest,QueueCrashRecoveryTest

# Run tests by pattern
mvn test -Dtest=*ComparisonTest

# Release build (GPG signing + publish to Maven Central)
mvn clean deploy -P release
```

## Module Structure

This is a multi-module Maven project:

| Module | Java | Description |
|---|---|---|
| `roguemap-core` | 8+ | Core off-heap storage library (zero mandatory deps) |
| `roguemap-embedding` | 8+ | Universal `EmbeddingProvider` implementation; zero extra deps |
| `roguemap-memory` | 8+ | AI memory layer; HNSW vector index via `jelmerk/hnswlib-core` |
| `roguemap-memory-pro` | 11+ | AI memory layer; higher-performance HNSW via `datastax/jvector` |

`roguemap-memory` and `roguemap-memory-pro` are structurally identical except for the vector index backend (`HnswVectorIndex` vs `JVectorIndex`). Both depend on `roguemap-core`.

`roguemap-embedding` provides `UniversalEmbeddingProvider` — a single class that works with any OpenAI `/v1/embeddings`-compatible service (OpenAI, Mistral, Jina, Voyage, Ollama in OpenAI-compat mode, Alibaba DashScope, Zhipu GLM, etc.) using only `HttpURLConnection`. The older `OpenAIEmbeddingProvider` and `OllamaEmbeddingProvider` in the memory modules are `@deprecated` in favor of this class.

## Architecture Overview

RogueMap is a high-performance embedded storage library using memory-mapped files for off-heap storage. Java 8+, zero mandatory dependencies. Provides four data structures: RogueMap (key-value store), RogueList (doubly-linked list), RogueSet (concurrent set), and RogueQueue (FIFO queue with linked/circular modes).

### Layered Design

```
API Layer (RogueMap, RogueList, RogueSet, RogueQueue)
    ↓
Index Layer (key → address mapping, or position tracking)
    ↓
Storage Engine (read/write byte data)
    ↓
Memory Allocator (MmapAllocator)
    ↓
UnsafeOps (sun.misc.Unsafe for direct memory access)
    ↓
Memory-Mapped Files (persistent or temporary)
```

### Data Structures

**RogueMap<K,V>** - Key-value store:
- `RogueMap.mmap().temporary()` - Temporary file mode (auto-deleted on JVM exit)
- `RogueMap.mmap().persistent(path)` - Persistent file mode (data survives restart)
- Index options: `basicIndex()`, `segmentedIndex(64)`, `primitiveIndex()`, `lowHeapIndex()`
- `forEach(BiConsumer<K,V>)` - Iterate over all key-value pairs
- TTL support: `defaultTTL(ttl, unit)` in builder; data stored as `[expireTime(8 bytes)][actual data]`
- Transactions: `beginTransaction()` returns AutoCloseable `Transaction<K,V>`

**RogueList<E>** - Doubly-linked list with O(1) random access:
- Maintains position index array for fast random access via `get(index)`
- Head/tail operations: `addFirst()`, `addLast()`, `removeFirst()`, `removeLast()`
- **Warning**: `addFirst()` and `removeFirst()` are O(n) due to position index shift; prefer `addLast()`/`removeLast()` for large lists
- Supports bidirectional iteration via `ListIterator<E>`

**RogueSet<E>** - Concurrent set:
- 64-segment design with StampedLock for high concurrency
- Optimistic read support for improved read performance
- Standard operations: `add()`, `contains()`, `remove()`
- `SetIterator` uses lazy segment loading (O(N/64) heap peak instead of O(N))
- Low-heap mode: `lowHeapIndex()` for String-key-only off-heap index

**RogueQueue<E>** - FIFO queue with two storage modes:
- **Linked mode** (unbounded): `RogueQueue.mmap().linked()`
- **Circular mode** (bounded): `RogueQueue.mmap().circular(capacity, maxElementSize)`
- Standard operations: `offer()`, `poll()`, `peek()`, `isFull()`
- LinkedQueue: snapshots head/tail/size to header on every offer/poll for crash recovery
- CircularQueue: recalculates count from headIdx/tailIdx on recovery

### Operations & Maintenance

**StorageMetrics** - Monitoring storage health:
- `getMetrics()` returns fragmentation ratio, used/available bytes, entry count, dead bytes
- `shouldCompact(threshold)` indicates when compaction is needed
- All four data structures support this API

**compact(allocSize)** - Space reclamation for persistent mode:
- Creates new file with only live data, eliminating fragmentation
- Returns new instance; old instance is closed
- Supported by RogueMap, RogueList, RogueSet, RogueQueue(linked)
- **Not supported**: temporary mode, CircularQueue

**checkpoint()** - Explicit crash recovery point:
- Forces index/metadata to disk for durable recovery
- Use when you need guaranteed recoverability between close() calls
- All four data structures support this in persistent mode

**AutoCheckpointManager** - Automatic checkpoint triggering:
- Time-interval mode: `autoCheckpoint(long interval, TimeUnit unit)` in builder
- Operation-count mode: `autoCheckpoint(int operationCount)` in builder
- Both modes can be enabled simultaneously; either condition triggers checkpoint
- Uses scheduled daemon thread pool; CAS-based operation counter to avoid duplicate triggers
- All four data structures support auto-checkpoint via builder

**Fail-fast Iterators**:
- RogueSet and RogueList iterators throw `ConcurrentModificationException` if collection is modified during iteration
- Tracks modification count; detects structural changes (add/remove/clear)

**Auto-Expansion** - Dynamic file growth:
- `autoExpand(true)` in builder enables automatic file growth when space runs out
- `expandFactor(double)` controls growth multiplier (default 2.0); `maxFileSize(long)` sets optional cap
- Expansion only maps new region; existing segment base addresses are unchanged
- Thread-safe: normal `allocate()` holds read lock (CAS), `expand()` holds exclusive write lock
- `tryAllocate()` skips segment tail bytes to avoid cross-segment allocations (SIGSEGV prevention)
- `saveMmapIndex()` uses `allocate()` for index placement; `getFileOffsetForAddress()` converts to file offset for header

**Transactions** - Atomic multi-key operations for RogueMap:
- `map.beginTransaction()` returns `Transaction<K,V>` (AutoCloseable)
- `txn.put(key, val)` / `txn.remove(key)` buffer operations; `txn.commit()` applies atomically
- `close()` without `commit()` auto-rolls back; `rollback()` also explicit
- Isolation: Read Committed (reads see committed data, not own pending writes)
- Deadlock prevention: locks acquired in ascending segment-index order
- **Not supported** with `lowHeapIndex()`

**TTL (Time-To-Live)** - Data expiration (all four data structures):
- Builder: `.defaultTTL(ttl, TimeUnit)` sets default TTL for all entries
- RogueMap also supports per-entry TTL: `put(key, value, ttl, TimeUnit)`
- Storage format: `[expireTime(8 bytes)][actual data]` — expiration timestamp prefix in mmap
- `TTLUtils` helper: `calculateExpireTime()`, `isExpired()`, `readExpireTime()`, `writeExpireTime()`
- TTL header size is 8 bytes; `getDataAddress()` skips header to reach actual data

### Core Packages

**index/** - Map indexing:
- `HashIndex` - Basic ConcurrentHashMap-based index
- `SegmentedHashIndex` - 64 segments with StampedLock (default for RogueMap)
- `LongPrimitiveIndex` / `IntPrimitiveIndex` - Primitive array indexes
- `LowHeapStringIndex` - Ultra-low heap String-only index (slot table + key bytes stored off-heap in mmap, only segment metadata/locks on JVM heap; 32-byte slots with EMPTY/USED/DELETED states; configured via `LowHeapOptions`)
- `BatchEntry` - Transaction batch operation entry

**list/** - List-specific components:
- `ListIndex` - Manages head/tail pointers + position index array
- `RogueListIterator` - Bidirectional ListIterator implementation

**set/** - Set-specific components:
- `SetIndex` - Segmented hash set index (64 segments)
- `LowHeapStringSetIndex` - Low-heap variant using `LowHeapStringIndex` as delegate
- `SetIterator` - Iterator implementation

**queue/** - Queue storage implementations:
- `LinkedQueueStorage` - Unbounded linked queue with free list for node recycling
- `CircularQueueStorage` - Bounded ring buffer queue

**storage/** - Storage engine:
- `MmapStorage` - Memory-mapped file storage
- `MmapFileHeader` - 4KB header with metadata, supports data types: MAP(0), LIST(1), SET(2), QUEUE_LINKED(3), QUEUE_CIRCULAR(4)

**memory/** - Memory management:
- `MmapAllocator` - Allocates space in mmap files, supports >2GB via segmentation
- `UnsafeOps` - Low-level Unsafe operations

**serialization/** - Codec implementations:
- `Codec<T>` - Interface for encoding/decoding values
- `PrimitiveCodecs` - Zero-copy codecs for Long, Integer, Double, Float, Short, Byte, Boolean
- `StringCodec` - UTF-8 string codec
- `KryoObjectCodec` - Object serialization via Kryo (optional dependency)
- `TypeReference<T>` - Preserves complex generic type info at runtime for Kryo (e.g., `new TypeReference<List<User>>() {}`)

**util/** - Utilities:
- `TempFileManager` - Temporary file management with `forceUnmap()` (tries Java 9+ `invokeCleaner` first)
- `TTLUtils` - TTL header read/write, expiration calculation

### Key Design Patterns

1. **Builder Pattern** - All four data structures use fluent builders (`MmapBuilder`)
2. **Segmented Locking** - 64 independent StampedLocks minimize contention
3. **Linear Allocation** - CAS-based offset allocation, append-only (no free list except LinkedQueue)
4. **Zero-Copy Primitives** - PrimitiveCodecs write directly to memory
5. **Copy-on-Compact** - `compact()` creates new file with live data only (append-only creates fragmentation over time)

### Persistence Mechanism

On `close()` or `checkpoint()`, persistent mode saves:
1. Current data offset to file header
2. Serialized index/metadata to end of file
3. File header metadata (magic, version, data type, entry count)

On reopening, builders detect existing files and restore state from disk. Use `checkpoint()` for explicit durability between close() calls.

### File Structure

```
src/main/java/com/yomahub/roguemap/
├── RogueMap.java              # Map class + MmapBuilder + Transaction inner class
├── RogueList.java             # Doubly-linked list
├── RogueSet.java              # Concurrent set
├── RogueQueue.java            # FIFO queue
├── RogueMapTransaction.java   # Transaction implementation (commit/rollback)
├── AutoCheckpointManager.java # Time/operation-count auto-checkpoint
├── StorageMetrics.java        # Storage health metrics (fragmentation, usage)
├── index/                     # Map index implementations (Hash, Segmented, Primitive, LowHeap)
├── list/                      # List index + iterator
├── set/                       # Set index + iterator (including LowHeapStringSetIndex)
├── queue/                     # Queue storage implementations
├── storage/                   # MmapStorage + MmapFileHeader
├── memory/                    # MmapAllocator + UnsafeOps
├── serialization/             # Codec implementations + TypeReference
├── util/                      # TempFileManager + TTLUtils
└── btree/                     # Placeholder (future B-tree implementation)
```

### Test Structure

```
src/test/java/com/yomahub/roguemap/
├── map/            # RogueMap tests (functional, temporary, TTL, transaction, expansion, concurrency, low-heap index)
├── list/           # RogueList tests (functional, concurrent)
├── set/            # RogueSet tests (functional, concurrent, low-heap)
├── queue/          # RogueQueue tests (functional, concurrent, crash recovery, free list)
├── common/         # Cross-structure tests (checkpoint, compaction, metrics, fail-fast iterators, P0 fixes)
├── memory/         # UnsafeOps tests
├── serialization/  # KryoObjectCodec tests
└── benchmark/      # Performance comparison tests + TestValueObject fixture
```

## roguemap-memory / roguemap-memory-pro

### RogueMemory

AI memory layer built on `roguemap-core`. Supports hybrid retrieval (vector + BM25) with mmap-backed persistence.

```java
RogueMemory mem = RogueMemory.builder()
    .path("data/mem")
    .searchMode(SearchMode.HYBRID)         // HYBRID | VECTOR_ONLY | KEYWORD_ONLY
    .embeddingProvider(new UniversalEmbeddingProvider(apiKey))
    .build();

String id = mem.add("content", metadata, "namespace");
List<MemoryResult> results = mem.search(SearchOptions.builder()
    .query("query text").topK(10).namespace("namespace").build());
mem.delete(id);
mem.close();
```

**SearchMode:**
- `HYBRID` (default) — vector search + BM25, merged via RRF; requires `EmbeddingProvider`
- `VECTOR_ONLY` — ANN only; requires `EmbeddingProvider`
- `KEYWORD_ONLY` — BM25 only; no `EmbeddingProvider` needed

### Vector Index Backends

- **`roguemap-memory` (`HnswVectorIndex`)** — `jelmerk/hnswlib-core 1.2.1`; Java 8+; cosine similarity; M=16, efConstruction=200, ef=50
- **`roguemap-memory-pro` (`JVectorIndex`)** — `datastax/jvector 3.0.1`; Java 11+; uses `GraphIndexBuilder` with ordinal→id mapping for ANN

Both implement `VectorIndex`: `add(id, vector)`, `search(vector, topK)`, `markDeleted(id)`, `serialize(DataOutput)`, `deserialize(DataInput)`.

### EmbeddingProvider SPI

Implement `EmbeddingProvider` to plug in any embedding source. **Preferred**: `UniversalEmbeddingProvider` from `roguemap-embedding`:

```java
// OpenAI (default model text-embedding-3-small)
new UniversalEmbeddingProvider(apiKey)

// Any OpenAI-compatible service (Mistral, Jina, Voyage, Ollama, DashScope, etc.)
new UniversalEmbeddingProvider(baseUrl, apiKey, model, dimension)
// Pass dimension=0 to auto-detect on first embed() call
```

Known models (dimension auto-populated): `text-embedding-3-small` (1536), `text-embedding-3-large` (3072), `mistral-embed` (1024), `nomic-embed-text` (768), `jina-embeddings-v3` (1024), and others — see `KNOWN_MODELS` map in the class.

`OpenAIEmbeddingProvider` and `OllamaEmbeddingProvider` in the memory modules are `@deprecated`; use `UniversalEmbeddingProvider` instead.

### mmap Record Format

```
[expireTime: 8B][id: 16B UUID][ns_len: 2B][namespace bytes]
[content_len: 4B][content bytes][meta_len: 4B][metadata bytes]
[vector_len: 4B][vector floats (4B each)][deleted: 1B][createdAt: 8B]
```

Metadata encoding: `[pair_count: 2B][key_len: 2B][key bytes][val_len: 2B][val bytes]...`

### Package Layout (both memory modules)

```
com.yomahub.roguemap.memory/
├── RogueMemory.java            # Main API (Builder, add/search/delete/compact/close)
├── OrdinalRegistry.java        # int-ordinal → UUID mapping for vector index entries
├── SearchMode.java             # HYBRID | VECTOR_ONLY | KEYWORD_ONLY
├── SearchOptions.java          # Query builder (query, topK, namespace, filter, minScore)
├── MemoryResult.java           # Search result (id, content, score, metadata)
├── MemoryEntry.java            # Internal entry model
├── embedding/
│   ├── EmbeddingProvider.java  # SPI interface
│   ├── OpenAIEmbeddingProvider.java   # @deprecated — use UniversalEmbeddingProvider
│   └── OllamaEmbeddingProvider.java   # @deprecated — use UniversalEmbeddingProvider
├── index/
│   ├── VectorIndex.java        # ANN index interface
│   ├── ScoredOrdinal.java      # (ordinal, score) pair for index results
│   ├── HnswVectorIndex.java    # (roguemap-memory only)
│   ├── JVectorIndex.java       # (roguemap-memory-pro only)
│   └── BM25Index.java          # BM25 keyword index (shared pattern)
└── util/
    └── Tokenizer.java          # Simple whitespace/punctuation tokenizer for BM25
```

## Important Notes

- **Java 8+** - Uses `sun.misc.Unsafe` for direct memory operations; Java 9+ tests use `--add-opens` (auto-activated via Maven profile)
- **Thread Safety** - All operations are thread-safe via segmented locking
- **Resource Management** - Always use try-with-resources to ensure proper cleanup
- **File Pre-allocation** - Mmap mode pre-allocates disk space via `allocateSize()`
- **Close Ordering** - `storage.close()` internally calls `allocator.close()`. Never call `allocator.close()` separately after `storage.close()` (double-close bug)
- **Optional Dependencies** - Kryo (`KryoObjectCodec`) and SLF4J are optional. Core library has zero mandatory dependencies
- **Fragmentation** - Append-only allocator creates dead bytes on updates/deletes; use `getMetrics()` to monitor and `compact()` when fragmentation ratio > 0.5
- **Auto-Expansion** - `autoExpand(true)` in builder allows file to grow; `tryAllocate()` skips segment tail bytes to avoid cross-boundary writes; use `getAddressForOffset()` / `getFileOffsetForAddress()` for safe multi-segment address translation
- **Transaction** - `map.beginTransaction()` returns AutoCloseable `Transaction<K,V>`; commit() is atomic; close() without commit() auto-rolls back; deadlock prevented by always locking segments in ascending index order
- **LowHeapIndex** - `lowHeapIndex()` is String-key-only; does not support `beginTransaction()`; does not auto-migrate legacy index formats
- **Iterator Safety** - Set/List iterators are fail-fast; do not modify collection during iteration
- **Test File Cleanup** - After JVM crash, @AfterEach doesn't run. Clean test directories in @BeforeEach to avoid corrupt leftover files crashing subsequent test runs
- **Keys on heap, values off-heap** - For expansion tests, value bytes (not key count) must exceed initial file size to trigger growth

## Critical Implementation Details

### MmapFileHeader Format (4KB)
```
offset  0-47:  9 data fields (magic, version, dataType, entryCount, etc.)
offset 48-51:  CRC32 checksum of bytes 0-47
offset 52-55:  writeGen (odd=writing, even=complete)
offset 56-59:  dirtyFlag (1=unclean close, 0=clean close)
offset 60-63:  Reserved
offset 64-95:  Queue snapshot area (headOffset, tailOffset, size, valid)
offset 96-4095: Reserved
```

### Memory Allocation
- `MmapAllocator.allocate()` rejects sizes > 512MB (defensive check)
- `MmapAllocator.free()` is a no-op (append-only allocator)
- LinkedQueueStorage maintains its own free list for node recycling
- `getAddressForOffset(fileOffset)` — file offset to physical address via segment table
- `getFileOffsetForAddress(physAddr)` — physical address to file offset (reverse lookup)

### TTL Data Layout
```
[expireTime: 8 bytes (long)][actual serialized data]
```
- `TTLUtils.TTL_HEADER_SIZE = 8`; `DEFAULT_TTL = 0` (never expires)
- Expiration stored as absolute timestamp from `System.currentTimeMillis() + ttlMillis`
