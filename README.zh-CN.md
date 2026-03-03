<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/maven-central/v/com.yomahub/roguemap.svg)](https://central.sonatype.com/artifact/com.yomahub/roguemap)

简体中文 | [English](README.md)

</div>

**RogueMap** 是一个高性能的嵌入式存储引擎库，突破 JVM 内存墙。基于内存映射文件，提供四种数据结构：**RogueMap**（键值存储）、**RogueList**（双向链表）、**RogueSet**（并发集合）、**RogueQueue**（FIFO 队列）。

## 为什么选择 RogueMap？

| 特性 | 传统集合 | RogueMap |
|------|---------|----------|
| **数据容量** | 受限于堆大小 | **无限制**，可达 TB 级 |
| **堆内存占用** | 100% | **仅 15.3%** |
| **GC 影响** | 严重（Full GC 停顿） | **几乎无影响** |
| **持久化** | 不支持 | **支持** |
| **事务** | 不支持 | **原子多键操作** |

## 特性

- **四种数据结构** - RogueMap、RogueList、RogueSet、RogueQueue
- **持久化支持** - 进程重启后数据自动恢复
- **自动扩容** - 文件写满自动增长
- **事务支持** - 原子多键操作，Read Committed 隔离级别
- **崩溃恢复** - CRC32 校验 + 写入代数 + 脏标志
- **零拷贝序列化** - 原始类型直接内存布局
- **高并发支持** - 64 段分段锁，StampedLock 乐观读
- **零依赖** - 核心库无第三方依赖

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap</artifactId>
    <version>1.0.1</version>
</dependency>
```

### RogueMap - 键值存储

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

// 事务
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("key1", 1L);
    txn.put("key2", 2L);
    txn.commit();  // 原子提交
}

// 遍历所有键值对
map.forEach((key, value) -> System.out.println(key + " = " + value));
```

> 注意：`lowHeapIndex()` 首版仅支持 `StringCodec.INSTANCE`，不支持 `beginTransaction()`，
> 且不会自动迁移旧索引格式。

### RogueList - 双向链表

```java
// 临时文件模式
RogueList<String> list = RogueList.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

list.addLast("hello");   // O(1) - 推荐
list.addLast("world");
list.get(0);             // "hello" - O(1) 随机访问

// 持久化模式
RogueList<Long> persistentList = RogueList.<Long>mmap()
    .persistent("data/mylist.db")
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

### RogueSet - 并发集合

```java
// 临时文件模式
RogueSet<String> set = RogueSet.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

set.add("apple");        // true
set.contains("apple");   // true
set.remove("apple");     // true

// 持久化模式
RogueSet<Long> persistentSet = RogueSet.<Long>mmap()
    .persistent("data/myset.db")
    .elementCodec(PrimitiveCodecs.LONG)
    .build();
```

### RogueQueue - FIFO 队列

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

## 支持的数据类型

**原始类型**（零拷贝）：`Long`、`Integer`、`Double`、`Float`、`Short`、`Byte`、`Boolean`

**字符串**：`StringCodec.INSTANCE`

**对象**：`KryoObjectCodec.create(YourClass.class)`（可选依赖）

**复杂泛型**：`KryoObjectCodec.create(new TypeReference<List<User>>() {})`（可选依赖）

`create(Class<T>)` 保持现有编解码行为不变，确保向后兼容。

## 文档

完整文档、性能测试报告和高级用法，请访问官网。

## 系统要求

- Java 8+
- Maven 3.6+

## 许可证

[Apache License 2.0](LICENSE)
