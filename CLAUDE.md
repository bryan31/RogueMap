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

**RogueList<E>** - Doubly-linked list with O(1) random access:
- Maintains position index array for fast random access via `get(index)`
- Head/tail operations: `addFirst()`, `addLast()`, `removeFirst()`, `removeLast()`
- Supports bidirectional iteration via `ListIterator<E>`

**RogueSet<E>** - Concurrent set:
- 64-segment design with StampedLock for high concurrency
- Optimistic read support for improved read performance
- Standard operations: `add()`, `contains()`, `remove()`

**RogueQueue<E>** - FIFO queue with two storage modes:
- **Linked mode** (unbounded): `RogueQueue.mmap().linked()`
- **Circular mode** (bounded): `RogueQueue.mmap().circular(capacity, maxElementSize)`
- Standard operations: `offer()`, `poll()`, `peek()`, `isFull()`

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

### Persistence Mechanism

On `close()`, persistent mode saves:
1. Current data offset to file header
2. Serialized index/metadata to end of file
3. File header metadata (magic, version, data type, entry count)

On reopening, builders detect existing files and restore state from disk.

### File Structure

```
src/main/java/com/yomahub/roguemap/
├── RogueMap.java              # Map class + MmapBuilder
├── RogueList.java             # Doubly-linked list
├── RogueSet.java              # Concurrent set
├── RogueQueue.java            # FIFO queue
├── index/                     # Map index implementations
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
└── serialization/             # Codec tests
```

## Important Notes

- **Java 8+** - Uses `sun.misc.Unsafe` for direct memory operations
- **Thread Safety** - All operations are thread-safe via segmented locking
- **Resource Management** - Always use try-with-resources to ensure proper cleanup
- **File Pre-allocation** - Mmap mode pre-allocates disk space via `allocateSize()`
