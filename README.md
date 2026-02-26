<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/maven-central/v/com.yomahub/roguemap.svg)](https://central.sonatype.com/artifact/com.yomahub/roguemap)

[简体中文](README.zh-CN.md) | English

</div>

**RogueMap** is a high-performance embedded storage engine that breaks through the JVM memory wall. Based on memory-mapped files, it provides four data structures: **RogueMap** (key-value store), **RogueList** (doubly-linked list), **RogueSet** (concurrent set), and **RogueQueue** (FIFO queue).

## Why RogueMap?

| Feature | Traditional Collections | RogueMap |
|---------|------------------------|----------|
| **Capacity** | Limited by heap size | **Unlimited**, TB-scale |
| **Heap Memory** | 100% | **Only 15.3%** |
| **GC Impact** | Severe (Full GC pauses) | **Minimal** |
| **Persistence** | Not supported | **Supported** |
| **Transactions** | Not supported | **Atomic multi-key ops** |

## Features

- **4 Data Structures** - RogueMap, RogueList, RogueSet, RogueQueue
- **Persistence** - Data survives process restarts
- **Auto-Expansion** - Files grow automatically when full
- **Transactions** - Atomic multi-key operations with Read Committed isolation
- **Crash Recovery** - CRC32 checksum + generation counter + dirty flag
- **Zero-Copy Serialization** - Direct memory layout for primitives
- **High Concurrency** - 64-segment locking with StampedLock
- **Zero Dependencies** - Core library has no mandatory dependencies

## Quick Start

### Maven

```xml
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap</artifactId>
    <version>1.0.1</version>
</dependency>
```

### RogueMap - Key-Value Store

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

// Transaction
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("key1", 1L);
    txn.put("key2", 2L);
    txn.commit();  // Atomic commit
}

// Iterate over all entries
map.forEach((key, value) -> System.out.println(key + " = " + value));
```

### RogueList - Doubly-Linked List

```java
// Temporary mode
RogueList<String> list = RogueList.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

list.addLast("hello");   // O(1) - recommended
list.addLast("world");
list.get(0);             // "hello" - O(1) random access

// Persistent mode
RogueList<Long> persistentList = RogueList.<Long>mmap()
    .persistent("data/mylist.db")
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

### RogueSet - Concurrent Set

```java
// Temporary mode
RogueSet<String> set = RogueSet.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

set.add("apple");        // true
set.contains("apple");   // true
set.remove("apple");     // true

// Persistent mode
RogueSet<Long> persistentSet = RogueSet.<Long>mmap()
    .persistent("data/myset.db")
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

### RogueQueue - FIFO Queue

```java
// Linked mode (unbounded)
RogueQueue<String> queue = RogueQueue.<String>mmap()
    .temporary()
    .linked()
    .elementCodec(StringCodec.INSTANCE)
    .build();

queue.offer("task1");
queue.poll();            // "task1"

// Circular mode (bounded)
RogueQueue<Long> circular = RogueQueue.<Long>mmap()
    .persistent("data/queue.db")
    .circular(1024, 64)  // capacity=1024, max element size=64 bytes
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

## Supported Data Types

**Primitives** (zero-copy): `Long`, `Integer`, `Double`, `Float`, `Short`, `Byte`, `Boolean`

**String**: `StringCodec.INSTANCE`

**Objects**: `KryoObjectCodec.create(YourClass.class)` (optional dependency)

**Complex generics**: `KryoObjectCodec.create(new TypeReference<List<User>>() {})` (optional dependency)

`create(Class<T>)` keeps the existing encoding/decoding behavior for backward compatibility.

## Documentation

For complete documentation, performance benchmarks, and advanced usage, please visit the official website.

## Requirements

- Java 8+
- Maven 3.6+

## License

[Apache License 2.0](LICENSE)
