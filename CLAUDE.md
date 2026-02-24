# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Compile the project
mvn clean compile

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=MmapFunctionalTest
mvn test -Dtest=ConcurrentSafetyTest
mvn test -Dtest=ListFunctionalTest
mvn test -Dtest=SetFunctionalTest
mvn test -Dtest=QueueFunctionalTest

# Run concurrent tests
mvn test -Dtest=ListConcurrentTest
mvn test -Dtest=SetConcurrentTest
mvn test -Dtest=QueueConcurrentTest

# Run performance comparison tests
mvn test -Dtest=*ComparisonTest

# Run new queue tests (free list + crash recovery)
mvn test -Dtest=LinkedQueueFreeListTest,QueueCrashRecoveryTest

# Run feature-specific tests
mvn test -Dtest=CompactionTest
mvn test -Dtest=CheckpointRecoveryTest
mvn test -Dtest=FailFastIteratorTest
mvn test -Dtest=StorageMetricsTest
mvn test -Dtest=AutoExpansionTest
mvn test -Dtest=TransactionTest
mvn test -Dtest=MmapFunctionalTest  # includes forEach tests

# Release build (GPG signing + publish to Maven Central)
mvn clean deploy -P release
```

## Architecture Overview

RogueMap is a high-performance embedded storage library using memory-mapped files for off-heap storage. It provides four data structures: RogueMap (key-value store), RogueList (doubly-linked list), RogueSet (concurrent set), and RogueQueue (FIFO queue with linked/circular modes).

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
- Index options: `basicIndex()`, `segmentedIndex(64)`, `primitiveIndex()`
- `forEach(BiConsumer<K,V>)` - Iterate over all key-value pairs

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

**Fail-fast Iterators**:
- RogueSet and RogueList iterators throw `ConcurrentModificationException` if collection is modified during iteration
- Tracks modification count; detects structural changes (add/remove/clear)

**Auto-Expansion** - Dynamic file growth for RogueMap (and other structures via builder):
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

### Core Packages

**index/** - Map indexing:
- `HashIndex` - Basic ConcurrentHashMap-based index
- `SegmentedHashIndex` - 64 segments with StampedLock (default for RogueMap)
- `LongPrimitiveIndex` / `IntPrimitiveIndex` - Primitive array indexes

**list/** - List-specific components:
- `ListIndex` - Manages head/tail pointers + position index array
- `RogueListIterator` - Bidirectional ListIterator implementation

**set/** - Set-specific components:
- `SetIndex` - Segmented hash set index (64 segments)
- `SetIterator` - Iterator implementation

**queue/** - Queue storage implementations:
- `LinkedQueueStorage` - Unbounded linked queue
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

### Key Design Patterns

1. **Builder Pattern** - All four data structures use fluent builders (`MmapBuilder`)
2. **Segmented Locking** - 64 independent StampedLocks minimize contention
3. **Linear Allocation** - CAS-based offset allocation, append-only (no free list)
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
├── StorageMetrics.java        # Storage health metrics (fragmentation, usage)
├── index/                     # Map index implementations
│   └── BatchEntry.java        # Transaction batch operation entry
├── list/                      # List index + iterator
├── set/                       # Set index + iterator
├── queue/                     # Queue storage implementations
├── storage/                   # MmapStorage + MmapFileHeader
├── memory/                    # MmapAllocator + UnsafeOps
├── serialization/             # Codec implementations
└── util/                      # TempFileManager

src/test/java/com/yomahub/roguemap/
├── mmap/                      # Map functional and performance tests
├── compare/                   # Comparison tests vs HashMap, Caffeine, FastUtil, MapDB
├── list/                      # List tests
├── set/                       # Set tests
├── queue/                     # Queue tests
├── memory/                    # UnsafeOps tests
├── serialization/             # Codec tests
├── CompactionTest.java        # Space reclamation tests
├── CheckpointRecoveryTest.java # Crash recovery tests
├── FailFastIteratorTest.java  # Iterator concurrent modification tests
├── StorageMetricsTest.java    # Metrics API tests
├── AutoExpansionTest.java     # Auto-expansion tests
└── TransactionTest.java       # Transaction atomicity/isolation tests
```

## Important Notes

- **Java 8+** - Uses `sun.misc.Unsafe` for direct memory operations
- **Thread Safety** - All operations are thread-safe via segmented locking
- **Resource Management** - Always use try-with-resources to ensure proper cleanup
- **File Pre-allocation** - Mmap mode pre-allocates disk space via `allocateSize()`
- **Close Ordering** - `storage.close()` internally calls `allocator.close()`. Never call `allocator.close()` separately after `storage.close()` (double-close bug)
- **Optional Dependencies** - Kryo (`KryoObjectCodec`) and SLF4J are optional. Core library has zero mandatory dependencies
- **Fragmentation** - Append-only allocator creates dead bytes on updates/deletes; use `getMetrics()` to monitor and `compact()` when fragmentation ratio > 0.5
- **Auto-Expansion** - `autoExpand(true)` in builder allows file to grow; `tryAllocate()` skips segment tail bytes to avoid cross-boundary writes; use `getAddressForOffset()` / `getFileOffsetForAddress()` for safe multi-segment address translation
- **Transaction** - `map.beginTransaction()` returns AutoCloseable `Transaction<K,V>`; commit() is atomic; close() without commit() auto-rolls back; deadlock prevented by always locking segments in ascending index order
- **Iterator Safety** - Set/List iterators are fail-fast; do not modify collection during iteration
- **Test File Cleanup** - After JVM crash, @AfterEach doesn't run. Clean test directories in @BeforeEach to avoid corrupt leftover files crashing subsequent test runs

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
