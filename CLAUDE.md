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

# Run performance comparison tests
mvn test -Dtest=*ComparisonTest
```

## Architecture Overview

RogueMap is a high-performance embedded key-value store using memory-mapped files for off-heap storage. The architecture follows a layered design:

```
RogueMap API (Builder pattern)
    ↓
Index Layer (key → address mapping)
    ↓
Storage Engine (read/write byte data)
    ↓
Memory Allocator (MmapAllocator)
    ↓
UnsafeOps (sun.misc.Unsafe for direct memory access)
    ↓
Memory-Mapped Files (persistent or temporary)
```

### Core Components

**RogueMap.java** - Main entry point with builder pattern:
- `RogueMap.mmap().temporary()` - Temporary file mode (auto-deleted on JVM exit)
- `RogueMap.mmap().persistent(path)` - Persistent file mode (data survives restart)

**Index Layer** (`com.yomahub.roguemap.index`):
- `HashIndex` - Basic ConcurrentHashMap-based index
- `SegmentedHashIndex` - 64 segments with StampedLock for high concurrency (default)
- `LongPrimitiveIndex` / `IntPrimitiveIndex` - Primitive array indexes for memory efficiency

**Storage Layer** (`com.yomahub.roguemap.storage`):
- `MmapStorage` - Storage engine using memory-mapped files
- `MmapFileHeader` - 4KB header with magic number, version, index type, entry count

**Memory Layer** (`com.yomahub.roguemap.memory`):
- `MmapAllocator` - Allocates space in memory-mapped files, supports files >2GB via segmentation
- `UnsafeOps` - Low-level Unsafe operations for direct memory access

**Serialization** (`com.yomahub.roguemap.serialization`):
- `Codec<T>` - Interface for encoding/decoding values
- `PrimitiveCodecs` - Zero-copy codecs for Long, Integer, Double, etc.
- `StringCodec` - UTF-8 string codec
- `KryoObjectCodec` - Object serialization via Kryo (optional dependency)

### Key Design Patterns

1. **Builder Pattern** - `MmapBuilder` for constructing RogueMap instances with fluent API
2. **Segmented Locking** - `SegmentedHashIndex` uses 64 independent StampedLocks for concurrency
3. **Linear Allocation** - MmapAllocator uses CAS-based offset allocation, no free list (append-only)
4. **Zero-Copy Primitives** - PrimitiveCodecs write directly to memory without intermediate objects

### Persistence Mechanism

On `close()`, persistent mode saves:
1. Current data offset to file header
2. Serialized index to end of file
3. File header metadata (magic, version, index type, entry count)

On reopening, `MmapBuilder` detects existing files and restores index from disk.

### File Structure

```
src/main/java/com/yomahub/roguemap/
├── RogueMap.java              # Main class + MmapBuilder
├── index/                     # Key → address mapping
├── storage/                   # MmapStorage + MmapFileHeader
├── memory/                    # MmapAllocator + UnsafeOps
├── serialization/             # Codec implementations
└── util/                      # TempFileManager

src/test/java/com/yomahub/roguemap/
├── mmap/                      # Mmap functional and performance tests
├── compare/                   # Comparison tests vs HashMap, Caffeine, FastUtil, MapDB
└── serialization/             # Codec tests
```

## Important Notes

- **Java 8+** - Uses `sun.misc.Unsafe` for direct memory operations
- **Thread Safety** - All operations are thread-safe via segmented locking
- **Resource Management** - Always use try-with-resources to ensure proper cleanup
- **File Pre-allocation** - Mmap mode pre-allocates disk space via `allocateSize()`
