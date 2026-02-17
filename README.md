<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Version](https://img.shields.io/badge/version-1.0.0--BETA2-green.svg)](https://github.com/bryan31/RogueMap)

</div>

**RogueMap** 是一个高性能的嵌入式存储引擎库，突破 JVM 内存墙，基于内存映射文件提供四种数据结构：**RogueMap**（键值存储）、**RogueList**（双向链表）、**RogueSet**（并发集合）、**RogueQueue**（FIFO 队列）。

## 🎯 为什么选择 RogueMap？

### 传统数据结构的困境

在处理大规模数据时，传统的 Java 集合面临诸多限制：

- ❌ **内存瓶颈** - 所有数据必须存储在堆内存，受 JVM 堆大小限制
- ❌ **GC 压力** - 百万级对象导致 Full GC 频繁，影响应用稳定性
- ❌ **数据易失** - 进程重启后数据全部丢失，无持久化能力
- ❌ **容量受限** - 超大数据集（10GB+）无法处理，OutOfMemoryError 噩梦
- ❌ **冷启动慢** - 每次启动都需要重新加载数据，耗时数分钟甚至更久

### RogueMap 的突破

RogueMap 将数据存储在 **内存映射文件** 中，让你享受简单 API，同时获得超越 JVM 限制的能力：

- ✅ **无限容量** - 突破 JVM 堆限制，轻松处理 100GB+ 数据集
- ✅ **零 GC 压力** - 堆内存占用减少 **84.7%**，告别 Full GC 噩梦
- ✅ **数据持久化** - 进程重启后数据自动恢复，零成本持久化
- ✅ **即开即用** - Mmap 模式秒级启动，无需预热加载
- ✅ **写入更快** - 写入性能提升 **1.45 倍**，仅写入索引，延迟序列化
- ✅ **临时存储** - 支持自动清理的临时文件模式，完美替代磁盘缓存

### 核心优势

| 特性 | 传统集合 | RogueMap |
|------|---------|----------|
| **数据容量** | 受限于堆大小（通常 < 10GB） | **无限制**，可达 TB 级 |
| **堆内存占用** | 100% | **仅 15.3%** |
| **GC 影响** | 严重（Full GC 秒级） | **几乎无影响** |
| **持久化** | ❌ 不支持 | ✅ 支持 |
| **进程重启** | 数据全部丢失 | **数据自动恢复** |
| **写性能** | 基准 | **1.45 倍提升** |
| **读性能** | 基准 | 约 1/4（反序列化开销） |
| **临时文件** | ❌ 不支持 | ✅ 自动清理 |

### 适用场景

**RogueMap 适合这些场景**：
- ✅ **写多读少** - 数据采集、日志聚合、指标统计
- ✅ **需要持久化** - 用户会话、应用状态、缓存数据
- ✅ **大数据集** - 数据量超过 JVM 堆大小限制
- ✅ **GC 敏感** - 对 Full GC 停顿零容忍的实时系统
- ✅ **临时数据处理** - 海量临时数据暂存，自动清理避免泄露

**RogueMap 不适合这些场景**：
- ❌ **读密集型** - 如果你的应用是读多写少，HashMap 或 Caffeine 更合适
- ❌ **微秒级延迟** - 如果需要极致的读取性能，纯内存方案更好
- ❌ **小数据集** - 数据量 < 1GB 时，HashMap 的简单性更有优势

## ✨ 特性

- ✅ **四种数据结构** - RogueMap（键值）、RogueList（链表）、RogueSet（集合）、RogueQueue（队列）
- ✅ **多种存储模式** - 支持 内存映射文件持久化、内存映射临时文件 两种模式
- ✅ **持久化支持** - Mmap 模式支持数据持久化到磁盘，支持自动恢复
- ✅ **临时文件模式** - 支持自动清理的临时文件存储
- ✅ **零拷贝序列化** - 原始类型直接内存布局，无序列化开销
- ✅ **高并发支持** - 分段锁设计（64 个段），StampedLock 乐观锁优化
- ✅ **多种索引结构** - 支持 HashIndex、SegmentedHashIndex、LongPrimitiveIndex、IntPrimitiveIndex
- ✅ **类型安全** - 泛型支持，编译时类型检查
- ✅ **零依赖** - 核心库无第三方依赖

## 🚀 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap</artifactId>
    <version>1.0.0-BETA2</version>
</dependency>
```

### RogueMap - 键值存储

#### Mmap 临时文件模式

```java
// 自动创建临时文件，JVM 关闭后自动删除
RogueMap<Long, Long> tempMap = RogueMap.<Long, Long>mmap()
    .temporary()
    .allocateSize(500 * 1024 * 1024L)
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();
```

#### Mmap 模式（持久化存储）

```java
// 第一次：创建并写入数据
RogueMap<String, Long> map1 = RogueMap.<String, Long>mmap()
    .persistent("data/scores.db")
    .allocateSize(1024 * 1024 * 1024L)  // 1GB
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

map1.put("alice", 100L);
map1.put("bob", 200L);
map1.flush();  // 刷新到磁盘
map1.close();

// 第二次：重新打开并恢复数据
RogueMap<String, Long> map2 = RogueMap.<String, Long>mmap()
    .persistent("data/scores.db")
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

long score = map2.get("alice");  // 100L（从磁盘恢复）
map2.close();
```

#### 索引选择

```java
// 场景1: 高并发读写，推荐分段索引（默认）
RogueMap<String, String> concurrentMap = RogueMap.<String, String>mmap()
    .temporary()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(StringCodec.INSTANCE)
    .segmentedIndex(64)  // 64个段，减少锁竞争
    .build();

// 场景2: 内存敏感，Long键，推荐原始索引
RogueMap<Long, Long> memoryOptimized = RogueMap.<Long, Long>mmap()
    .temporary()
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .primitiveIndex()  // 节省81%内存
    .build();

// 场景3: 简单场景，推荐基础索引
RogueMap<String, Integer> simpleMap = RogueMap.<String, Integer>mmap()
    .temporary()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.INTEGER)
    .basicIndex()
    .build();
```

### RogueList - 双向链表

RogueList 是基于内存映射文件的高性能双向链表，支持 O(1) 随机访问：

```java
// 临时文件模式
RogueList<String> list = RogueList.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

// 头部/尾部操作
list.addFirst("hello");
list.addLast("world");

String first = list.getFirst();     // "hello"
String last = list.getLast();       // "world"

// O(1) 随机访问
String element = list.get(0);       // "hello"

// 移除操作
String removed = list.removeFirst(); // "hello"
String removed2 = list.removeLast(); // "world"

// 持久化模式
RogueList<Long> persistentList = RogueList.<Long>mmap()
    .persistent("data/mylist.db")
    .elementCodec(PrimitiveCodecs.LONG)
    .allocateSize(256 * 1024 * 1024L)
    .build();

// 迭代器支持
for (String s : list) {
    System.out.println(s);
}

// ListIterator 双向遍历
java.util.ListIterator<String> it = list.listIterator();
while (it.hasNext()) {
    System.out.println(it.next());
}
while (it.hasPrevious()) {
    System.out.println(it.previous());
}
```

### RogueSet - 并发集合

RogueSet 是基于内存映射文件的高性能并发集合，采用 64 段分段锁设计：

```java
// 临时文件模式
RogueSet<String> set = RogueSet.<String>mmap()
    .temporary()
    .elementCodec(StringCodec.INSTANCE)
    .build();

// 基本操作
set.add("apple");   // true
set.add("apple");   // false（已存在）
set.contains("apple"); // true
set.remove("apple");   // true

// 持久化模式
RogueSet<Long> persistentSet = RogueSet.<Long>mmap()
    .persistent("data/myset.db")
    .elementCodec(PrimitiveCodecs.LONG)
    .segmentCount(64)  // 64段分段锁
    .build();

// 迭代器支持
for (String s : set) {
    System.out.println(s);
}

// 清空
set.clear();
```

### RogueQueue - FIFO 队列

RogueQueue 支持两种模式：链表模式（无界）和环形缓冲区模式（有界）：

```java
// 链表模式（无界队列）
RogueQueue<String> linkedQueue = RogueQueue.<String>mmap()
    .temporary()
    .linked()
    .elementCodec(StringCodec.INSTANCE)
    .build();

linkedQueue.offer("task1");
linkedQueue.offer("task2");
String task = linkedQueue.poll();   // "task1"
String peek = linkedQueue.peek();   // "task2"

// 环形缓冲区模式（有界队列）
RogueQueue<Long> circularQueue = RogueQueue.<Long>mmap()
    .persistent("data/myqueue.db")
    .circular(1024, 64)  // 容量1024，最大元素64字节
    .elementCodec(PrimitiveCodecs.LONG)
    .build();

circularQueue.offer(1L);
circularQueue.offer(2L);

if (circularQueue.isFull()) {
    System.out.println("队列已满");
}

Long value = circularQueue.poll();  // 1L
```

### 支持的数据类型

RogueMap 提供了零拷贝的原始类型编解码器：

```java
// Long 类型（高性能）
RogueMap<Long, Long> longMap = RogueMap.<Long, Long>mmap()
    .temporary()
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// Integer 类型
RogueMap<Integer, Integer> intMap = RogueMap.<Integer, Integer>mmap()
    .temporary()
    .keyCodec(PrimitiveCodecs.INTEGER)
    .valueCodec(PrimitiveCodecs.INTEGER)
    .build();

// String 类型
RogueMap<String, String> stringMap = RogueMap.<String, String>mmap()
    .temporary()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(StringCodec.INSTANCE)
    .build();

// 混合类型
RogueMap<String, Double> mixedMap = RogueMap.<String, Double>mmap()
    .temporary()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.DOUBLE)
    .build();
```

**支持的原始类型**：`Long`, `Integer`, `Double`, `Float`, `Short`, `Byte`, `Boolean`

如果是对象类型，RogueMap 也提供了对象的编码解析器：

```java
// 对象类型
RogueMap<String, YourObject> objectMap = RogueMap.<String, YourObject>mmap()
    .temporary()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(KryoObjectCodec.create(YourObject.class))
    .build();
```

## 📊 性能测试

测试环境：Linux 2C4G 服务器，100 万条记录（10 属性对象）

### 综合性能对比

| 方案 | 写入时间 | 读取时间 | 写吞吐量 | 读吞吐量 | 堆内存占用 | 持久化 |
|-----|----------|----------|----------|----------|-----------|--------|
| **HashMap** | 1,535ms | **158ms** | 651K ops/s | **6,329K ops/s** | 311 MB | ❌ |
| **FastUtil** | **600ms** | **32ms** | **1,667K ops/s** | **31,250K ops/s** | 276 MB | ❌ |
| **Caffeine** | 1,107ms | 2,298ms | 903K ops/s | 435K ops/s | 352 MB | ❌ |
| **RogueMap Mmap 持久化** | **1,057ms** | **642ms** | **946K ops/s** | **1,558K ops/s** | **48 MB** | ✅ |
| **RogueMap Mmap 临时** | 1,113ms | 704ms | 898K ops/s | 1,420K ops/s | **48 MB** | ❌ |
| **MapDB OffHeap** | 8,259ms | 8,451ms | 121K ops/s | 118K ops/s | 11 MB | ❌ |
| **MapDB 临时文件** | 9,002ms | 7,717ms | 111K ops/s | 130K ops/s | 8 MB | ❌ |
| **MapDB 持久化** | 8,117ms | 7,709ms | 123K ops/s | 130K ops/s | 8 MB | ✅ |

### 核心发现

**RogueMap 用读取速度换来了什么？**

1. **堆内存占用减少 84.7%** - 从 311MB 降到 48MB，告别 Full GC 噩梦
2. **写入性能提升 1.45 倍** - 仅写入索引，延迟序列化策略
3. **数据持久化能力** - 进程重启后数据自动恢复，HashMap 完全不具备
4. **突破容量限制** - 可处理超过堆大小的数据集，HashMap 无法做到
5. **本地访问速度** - 155 万 ops/s，比 Redis 网络操作快 **15.6 倍**

**性能权衡的价值**：

读取速度约为 HashMap 的 1/4，这是因为需要从内存映射文件反序列化数据。但这个代价换来的是：
- ✅ **持久化存储** - 数据不丢失
- ✅ **无限容量** - 不受 JVM 堆限制
- ✅ **零 GC 压力** - 84.7% 的内存节省
- ✅ **更快写入** - 1.45 倍写入性能

**推荐使用场景**：
- 🏆 **写多读少** - 数据采集、日志聚合、消息队列
- 💾 **需要持久化** - 用户会话、缓存数据、临时计算结果
- 📈 **大数据集** - 超过堆大小的数据处理
- ⚡ **GC 敏感** - 对 GC 停顿零容忍的实时系统

### 持久化方案性能对比

**关键洞察**：
- **RogueMap Mmap 持久化** 在所有支持持久化的方案中性能最优
  - 写入: 1,057ms，比 HashMap(1,535ms) 快 **31%**，比 MapDB(8,117ms) 快 **7.7 倍**
  - 读取: 642ms (155 万 ops/s)，比 MapDB(7,709ms) 快 **12 倍**，比 Redis 网络快 **15.6 倍**
- **内存占用大幅优化**：RogueMap(48 MB) 比 HashMap(311 MB) 节省 **84.7%** 堆内存
- **综合性价比最高**：在持久化 + 性能 + 内存三方面取得最佳平衡

RogueMap 的设计哲学：**用可接受的读取速度，换取持久化存储和巨大的内存节省**

### 运行性能测试

```bash
# 运行 RogueMap 多模式对比
mvn test -Dtest=MemoryUsageComparisonTest

# 运行 RogueMap vs MapDB 对比
mvn test -Dtest=RogueMapVsMapDBComparisonTest

# 运行所有性能测试
mvn test -Dtest=*ComparisonTest

# 运行 List/Set/Queue 测试
mvn test -Dtest=ListFunctionalTest
mvn test -Dtest=SetFunctionalTest
mvn test -Dtest=QueueFunctionalTest

# 运行并发测试
mvn test -Dtest=ListConcurrentTest
mvn test -Dtest=SetConcurrentTest
mvn test -Dtest=QueueConcurrentTest
```

## 🏗️ 架构设计

```
API Layer (RogueMap, RogueList, RogueSet, RogueQueue)
   ↓
Index Layer (HashIndex/SegmentedHashIndex/ListIndex/SetIndex)
   ↓
Storage Engine (MmapStorage)
   ↓
Memory Allocator (MmapAllocator)
   ↓
UnsafeOps (Java 8 Unsafe)
   ↓
Memory-Mapped Files
```

### 核心模块

- **RogueMap** - 键值存储，提供 MmapBuilder 构建器
- **RogueList** - 双向链表，O(1) 随机访问，支持 ListIterator
- **RogueSet** - 并发集合，64 段分段锁，StampedLock 乐观读
- **RogueQueue** - FIFO 队列，支持链表模式（无界）和环形缓冲区模式（有界）
- **index** - Map 索引层
  - `HashIndex` - 基础哈希索引，基于 ConcurrentHashMap
  - `SegmentedHashIndex` - 分段哈希索引，64 个段 + StampedLock 乐观锁
  - `LongPrimitiveIndex` - Long 键原始数组索引，节省 81% 内存
  - `IntPrimitiveIndex` - Integer 键原始数组索引
- **list** - List 索引层
  - `ListIndex` - 头尾指针 + 位置索引数组
  - `RogueListIterator` - 双向迭代器
- **set** - Set 索引层
  - `SetIndex` - 分段哈希集合索引
  - `SetIterator` - 迭代器实现
- **queue** - Queue 存储层
  - `LinkedQueueStorage` - 链表队列存储
  - `CircularQueueStorage` - 环形缓冲区队列存储
- **storage** - 存储引擎
  - `MmapStorage` - 内存映射文件存储
  - `MmapFileHeader` - 文件头，支持多种数据类型
- **memory** - 内存管理
  - `MmapAllocator` - 内存映射文件分配器，支持超过 2GB 的大文件
  - `UnsafeOps` - 底层 Unsafe API 操作
- **serialization** - 序列化层
  - `PrimitiveCodecs` - 原始类型零拷贝编解码器
  - `StringCodec` - String 编解码器
  - `KryoObjectCodec` - Kryo 对象序列化编解码器（可选）

### 内存管理机制

#### MmapAllocator（文件映射）

- **特点**: 使用 MappedByteBuffer 将文件映射到内存
- **大文件支持**: 单个分段最大 2GB，自动分多段处理
- **并发安全**: CAS 操作分配偏移量
- **双模式**: 支持持久化和临时文件

### 高并发支持

#### SegmentedHashIndex 并发机制

- **分段数量**: 64 个独立段
- **锁策略**: 每个段独立的 StampedLock
- **乐观读**: 读操作优先使用乐观读，验证失败时降级为读锁
- **性能**: 高并发场景下读性能提升 15 倍

#### LongPrimitiveIndex 并发机制

- **实现**: 原始数组 (long[] keys, long[] addresses, int[] sizes)
- **锁策略**: StampedLock 乐观读
- **内存优化**: 节省 81% 内存

## 📖 文档

- [性能测试白皮书](docs/benchmark.md) - 完整的性能测试数据和分析

## 🔧 构建项目

```bash
# 编译
mvn clean compile

# 运行测试
mvn test

# 运行特定测试
mvn test -Dtest=MmapFunctionalTest
mvn test -Dtest=ConcurrentSafetyTest
mvn test -Dtest=ListFunctionalTest
mvn test -Dtest=SetFunctionalTest
mvn test -Dtest=QueueFunctionalTest
```

## 📝 系统要求

- Java 8
- Maven 3.6+

## ⚠️ 注意事项

1. **Unsafe API 警告** - 本项目使用 `sun.misc.Unsafe` API，这是内部 API，可能在未来版本中被移除。以后将添加 Java 17/21 的替代实现。

2. **资源管理** - 请确保正确关闭实例以释放资源：
   ```java
   try (RogueMap<K, V> map = ...) {
       // 使用 map
   } // 自动关闭，释放资源

   try (RogueList<E> list = ...) {
       // 使用 list
   }

   try (RogueSet<E> set = ...) {
       // 使用 set
   }

   try (RogueQueue<E> queue = ...) {
       // 使用 queue
   }
   ```

3. **文件大小** - Mmap 模式的 `allocateSize()` 会立即占用磁盘空间，请根据实际需求设置

4. **并发安全** - 所有数据结构都是线程安全的，支持高并发读写

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

## 🙏 致谢

本项目的设计灵感来自于：
- [MapDB](https://github.com/jankotek/mapdb) - 优秀的嵌入式数据库
- [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map) - 高性能堆外 Map
