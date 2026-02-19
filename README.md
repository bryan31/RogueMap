<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg)](https://github.com/bryan31/RogueMap)

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

- ✅ **无限容量** - 突破 JVM 堆限制，轻松处理 100GB+ 数据集，支持自动扩容
- ✅ **零 GC 压力** - 堆内存占用减少 **84.7%**，告别 Full GC 噩梦
- ✅ **数据持久化** - 进程重启后数据自动恢复，零成本持久化
- ✅ **即开即用** - Mmap 模式秒级启动，无需预热加载
- ✅ **写入更快** - 写入性能提升 **1.45 倍**，仅写入索引，延迟序列化
- ✅ **事务支持** - 多键原子操作，Read Committed 隔离级别
- ✅ **崩溃恢复** - CRC32 校验 + 写入代数 + 脏标志，确保数据一致性

### 核心优势

| 特性 | 传统集合 | RogueMap |
|------|---------|----------|
| **数据容量** | 受限于堆大小（通常 < 10GB） | **无限制**，可达 TB 级 |
| **堆内存占用** | 100% | **仅 15.3%** |
| **GC 影响** | 严重（Full GC 秒级） | **几乎无影响** |
| **持久化** | ❌ 不支持 | ✅ 支持 |
| **进程重启** | 数据全部丢失 | **数据自动恢复** |
| **写性能** | 基准 | **1.45 倍提升** |
| **事务** | ❌ 不支持 | ✅ 原子多键操作 |
| **自动扩容** | ❌ 不支持 | ✅ 按需增长 |
| **崩溃恢复** | ❌ 不支持 | ✅ 快照恢复 |

### 适用场景

**RogueMap 适合这些场景**：
- ✅ **写多读少** - 数据采集、日志聚合、指标统计
- ✅ **需要持久化** - 用户会话、应用状态、缓存数据
- ✅ **大数据集** - 数据量超过 JVM 堆大小限制
- ✅ **GC 敏感** - 对 Full GC 停顿零容忍的实时系统
- ✅ **临时数据处理** - 海量临时数据暂存，自动清理避免泄露
- ✅ **事务场景** - 需要多键原子操作的业务

**RogueMap 不适合这些场景**：
- ❌ **读密集型** - 如果你的应用是读多写少，HashMap 或 Caffeine 更合适
- ❌ **微秒级延迟** - 如果需要极致的读取性能，纯内存方案更好
- ❌ **小数据集** - 数据量 < 1GB 时，HashMap 的简单性更有优势

## ✨ 特性

- ✅ **四种数据结构** - RogueMap（键值）、RogueList（链表）、RogueSet（集合）、RogueQueue（队列）
- ✅ **持久化支持** - Mmap 模式支持数据持久化到磁盘，支持自动恢复
- ✅ **自动扩容** - 文件写满自动增长（`autoExpand`），无需预估容量，已有数据地址不受影响
- ✅ **事务支持** - 多操作原子提交（`beginTransaction`），Read Committed 隔离级别，死锁预防
- ✅ **崩溃恢复** - CRC32 校验 + 写入代数机制 + 脏标志 + 快照，确保数据一致性
- ✅ **零拷贝序列化** - 原始类型直接内存布局，无序列化开销
- ✅ **高并发支持** - 64 段分段锁设计，StampedLock 乐观读优化
- ✅ **多种索引结构** - HashIndex、SegmentedHashIndex、LongPrimitiveIndex、IntPrimitiveIndex
- ✅ **运维指标** - 碎片率、使用量、条目数实时监控
- ✅ **空间回收** - compact() 方法回收已删除数据占用的空间
- ✅ **Fail-fast 迭代器** - 并发修改检测，防止数据不一致
- ✅ **零依赖** - 核心库无第三方依赖（Kryo、SLF4J 为可选）

## 🚀 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap</artifactId>
    <version>1.0.0</version>
</dependency>
```

### RogueMap - 键值存储

#### 临时文件模式

```java
// 自动创建临时文件，JVM 关闭后自动删除
RogueMap<Long, Long> tempMap = RogueMap.<Long, Long>mmap()
    .temporary()
    .allocateSize(500 * 1024 * 1024L)
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();
```

#### 持久化模式

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

#### 自动扩容

无需预估文件大小，写满后自动增长：

```java
RogueMap<String, Long> map = RogueMap.<String, Long>mmap()
    .persistent("data/scores.db")
    .allocateSize(64 * 1024 * 1024L)  // 初始 64MB，不够时自动扩容
    .autoExpand(true)                  // 开启自动扩容
    .expandFactor(2.0)                 // 每次扩容为原来的 2 倍（默认）
    // .maxFileSize(10L * 1024 * 1024 * 1024)  // 可选：设置最大文件大小上限
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// 正常写入，无需关心容量
for (int i = 0; i < 10_000_000; i++) {
    map.put("key-" + i, (long) i);  // 文件满时自动扩容，已有数据地址不变
}
```

**自动扩容特性**：
- 扩容时仅对新增区域创建映射，已有数据的物理地址完全不变
- 线程安全：普通写入持读锁（CAS 无锁），扩容时独占写锁，扩容完成后其他线程继续正常写入
- 扩容后文件大小可通过 `map.getMetrics().getTotalFileSize()` 查看

#### 事务支持

对多个 key 的操作保证原子性，要么全部成功，要么全部回滚：

```java
// 正常提交
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("alice", 100L);
    txn.put("bob", 200L);
    txn.remove("charlie");
    txn.commit();  // 原子提交：三个操作同时生效
}  // 未调用 commit() 时，close() 自动回滚

// 异常时自动回滚
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("alice", 999L);
    txn.put("bob", 888L);
    if (someCondition) {
        // 不调用 commit()，close() 时自动回滚
        return;
    }
    txn.commit();
}

// 手动回滚
try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
    txn.put("key1", 1L);
    txn.put("key2", 2L);
    txn.rollback();  // 显式回滚
}
```

**事务特性**：
- **原子性**：commit() 使用分段排序加锁，所有写入原子生效
- **隔离级别**：Read Committed — 事务内读取的是已提交数据
- **死锁预防**：始终按 segment index 升序加锁，杜绝死锁
- **支持同 key 多次写入**：以最后一次 put 为准

#### 运维指标与空间回收

```java
// 获取运维指标
StorageMetrics metrics = map.getMetrics();
System.out.println("文件大小: " + metrics.getTotalFileSize());
System.out.println("已使用: " + metrics.getUsedBytes());
System.out.println("碎片率: " + metrics.getFragmentationRatio());
System.out.println("条目数: " + metrics.getEntryCount());

// 判断是否需要压缩
if (metrics.shouldCompact(0.5)) {  // 碎片率 > 50%
    map = map.compact(newAllocateSize);  // 压缩并返回新实例
}

// 显式检查点（崩溃恢复点）
map.checkpoint();  // 强制持久化索引，确保崩溃后可恢复
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
list.addFirst("hello");   // 注意：O(n) 复杂度
list.addLast("world");    // O(1) 复杂度，推荐使用

String first = list.getFirst();     // "hello"
String last = list.getLast();       // "world"

// O(1) 随机访问
String element = list.get(0);       // "hello"

// 移除操作
String removed = list.removeFirst(); // 注意：O(n) 复杂度
String removed2 = list.removeLast(); // O(1) 复杂度，推荐使用

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

> **⚠️ 性能提示**：`addFirst()` 和 `removeFirst()` 是 O(n) 复杂度（需要移动位置索引数组），大列表场景建议优先使用 `addLast()`/`removeLast()`（O(1)）。

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

// 迭代器支持（Fail-fast）
try {
    for (String s : set) {
        // 迭代过程中修改集合会抛出 ConcurrentModificationException
        set.add("new-element");  // 危险！
    }
} catch (ConcurrentModificationException e) {
    // 处理并发修改
}

// 清空
set.clear();
```

### RogueQueue - FIFO 队列

RogueQueue 支持两种模式：链表模式（无界）和环形缓冲区模式（有界）：

```java
// 链表模式（无界队列）- 支持 compact 和崩溃恢复快照
RogueQueue<String> linkedQueue = RogueQueue.<String>mmap()
    .temporary()
    .linked()
    .elementCodec(StringCodec.INSTANCE)
    .build();

linkedQueue.offer("task1");
linkedQueue.offer("task2");
String task = linkedQueue.poll();   // "task1"
String peek = linkedQueue.peek();   // "task2"

// 环形缓冲区模式（有界队列）- 固定容量，无碎片
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

**链表队列特性**：
- 无界容量，自动扩容
- 空闲节点链表复用（poll 的节点可供 offer 复用）
- 支持 compact() 回收空间
- 每次 offer/poll 自动写入崩溃恢复快照

**环形队列特性**：
- 有界容量，固定槽位
- 无碎片，适合高频入队出队场景
- 不支持 compact（本身无碎片）

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

- **RogueMap** - 键值存储，提供 MmapBuilder 构建器，支持事务
- **RogueList** - 双向链表，O(1) 随机访问，支持 ListIterator
- **RogueSet** - 并发集合，64 段分段锁，StampedLock 乐观读
- **RogueQueue** - FIFO 队列，支持链表模式（无界）和环形缓冲区模式（有界）
- **index** - Map 索引层
  - `HashIndex` - 基础哈希索引
  - `SegmentedHashIndex` - 分段哈希索引，64 段 + StampedLock 乐观锁
  - `LongPrimitiveIndex` - Long 键原始数组索引，节省 81% 内存
  - `IntPrimitiveIndex` - Integer 键原始数组索引
- **list** - List 索引层
  - `ListIndex` - 头尾指针 + 位置索引数组
  - `RogueListIterator` - 双向迭代器（Fail-fast）
- **set** - Set 索引层
  - `SetIndex` - 分段哈希集合索引
  - `SetIterator` - 迭代器（Fail-fast，懒加载分段）
- **queue** - Queue 存储层
  - `LinkedQueueStorage` - 链表队列存储（空闲链表复用 + 崩溃恢复快照）
  - `CircularQueueStorage` - 环形缓冲区队列存储
- **storage** - 存储引擎
  - `MmapStorage` - 内存映射文件存储
  - `MmapFileHeader` - 文件头（CRC32 + 写入代数 + 脏标志 + 快照区）
- **memory** - 内存管理
  - `MmapAllocator` - 内存映射文件分配器，支持 >2GB 分段、自动扩容
  - `UnsafeOps` - 底层 Unsafe API 操作
- **serialization** - 序列化层
  - `PrimitiveCodecs` - 原始类型零拷贝编解码器
  - `StringCodec` - String 编解码器
  - `KryoObjectCodec` - Kryo 对象序列化编解码器（可选）

### 关键技术实现

#### MmapFileHeader 格式（4KB）
```
offset  0-47:  9 数据字段（magic, version, dataType, entryCount等）
offset 48-51:  CRC32 校验和（确保数据完整性）
offset 52-55:  writeGen（写入代数，奇数=写入中，偶数=完成）
offset 56-59:  dirtyFlag（脏标志，1=异常关闭，0=正常关闭）
offset 60-63:  保留
offset 64-95:  Queue 快照区（headOffset, tailOffset, size, valid）
offset 96-4095: 保留
```

#### 内存分配机制
- **CAS 无锁分配**：普通分配使用 CAS 操作，高并发无阻塞
- **分段支持**：单段最大 2GB，自动分多段处理超大文件
- **自动扩容**：ReadWriteLock 保护，扩容时仅映射新增区域
- **边界检测**：`tryAllocate()` 检测跨段边界，防止 SIGSEGV

#### 并发控制
- **64 段分段锁**：每个段独立 StampedLock，减少锁竞争
- **乐观读**：读操作优先使用乐观读，验证失败降级为读锁
- **死锁预防**：事务按 segment index 升序加锁

#### 崩溃恢复
1. **CRC32 校验**：确保头部数据完整性
2. **写入代数**：区分写入中/写入完成状态
3. **脏标志**：检测是否正常关闭
4. **Queue 快照**：每次 offer/poll 写入快照，崩溃后优先恢复

## 📖 文档

- [性能测试白皮书](docs/benchmark.md) - 完整的性能测试数据和分析

## 🔧 构建项目

```bash
# 编译
mvn clean compile

# 运行所有测试（169 个测试用例）
mvn test

# 运行特定测试
mvn test -Dtest=MmapFunctionalTest
mvn test -Dtest=TransactionTest
mvn test -Dtest=AutoExpansionTest

# 发布到 Maven Central
mvn clean deploy -P release
```

## 📝 系统要求

- Java 8+
- Maven 3.6+

## ⚠️ 注意事项

1. **Unsafe API 警告** - 本项目使用 `sun.misc.Unsafe` API，这是内部 API，在 Java 9+ 需要添加 JVM 参数。后续版本将添加 Java 17/21 的替代实现。

2. **资源管理** - 请确保正确关闭实例以释放资源：
   ```java
   try (RogueMap<K, V> map = ...) {
       // 使用 map
   } // 自动关闭，持久化模式会保存索引
   ```

3. **文件大小** - Mmap 模式的 `allocateSize()` 会立即占用磁盘空间，请根据实际需求设置；如果不确定容量，建议开启 `autoExpand(true)` 让文件按需增长。

4. **并发安全** - 所有数据结构都是线程安全的，支持高并发读写。

5. **事务注意事项**：
   - 事务仅支持 `RogueMap` 且使用 `SegmentedHashIndex`（默认）
   - commit 后调用 `checkpoint()` 可确保崩溃也能恢复
   - 隔离级别为 Read Committed，不支持读自己的未提交写入

6. **迭代器注意事项**：
   - RogueSet 和 RogueList 的迭代器是 Fail-fast 的
   - 迭代过程中修改集合会抛出 `ConcurrentModificationException`

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

## 🙏 致谢

本项目的设计灵感来自于：
- [MapDB](https://github.com/jankotek/mapdb) - 优秀的嵌入式数据库
- [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map) - 高性能堆外 Map
