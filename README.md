<div align="center">
  <img src="static/img/logo.svg" alt="RogueMap Logo" width="120" height="120">
  <h1>RogueMap</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://www.oracle.com/java/)
[![Version](https://img.shields.io/badge/version-1.0.0--BETA1-green.svg)](https://github.com/bryan31/RogueMap)

</div>

**RogueMap** 是一个高性能的嵌入式键值存储引擎，突破 HashMap 的内存墙，提供堆外内存和持久化存储能力。

## 🎯 为什么选择 RogueMap？

### HashMap 的困境

在处理大规模数据时，传统的 HashMap 面临诸多限制：

- ❌ **内存瓶颈** - 所有数据必须存储在堆内存，受 JVM 堆大小限制
- ❌ **GC 压力** - 百万级对象导致 Full GC 频繁，影响应用稳定性
- ❌ **数据易失** - 进程重启后数据全部丢失，无持久化能力
- ❌ **容量受限** - 超大数据集（10GB+）无法处理，OutOfMemoryError 噩梦
- ❌ **冷启动慢** - 每次启动都需要重新加载数据，耗时数分钟甚至更久

### RogueMap 的突破

RogueMap 将数据存储在 **堆外内存** 或 **内存映射文件** 中，让你享受 HashMap 的简单 API，同时获得超越其限制的能力：

- ✅ **无限容量** - 突破 JVM 堆限制，轻松处理 100GB+ 数据集
- ✅ **零 GC 压力** - 堆内存占用减少 **84.7%**，告别 Full GC 噩梦
- ✅ **数据持久化** - 进程重启后数据自动恢复，零成本持久化
- ✅ **即开即用** - Mmap 模式秒级启动，无需预热加载
- ✅ **写入更快** - 写入性能提升 **1.45 倍**，仅写入索引，延迟序列化
- ✅ **临时存储** - 支持自动清理的临时文件模式，完美替代磁盘缓存

### 核心优势

| 特性 | HashMap | RogueMap |
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

- ✅ **多种存储模式** - 支持 堆外内存、内存映射文件、内存映射临时文件 三种模式
- ✅ **持久化支持** - Mmap 模式支持数据持久化到磁盘，支持自动恢复
- ✅ **临时文件模式** - 支持自动清理的临时文件存储
- ✅ **零拷贝序列化** - 原始类型直接内存布局，无序列化开销
- ✅ **高并发支持** - 分段锁设计（64 个段），StampedLock 乐观锁优化
- ✅ **智能内存分配** - Slab Allocator 减少内存碎片
- ✅ **多种索引结构** - 支持 HashIndex、SegmentedHashIndex、LongPrimitiveIndex、IntPrimitiveIndex
- ✅ **类型安全** - 泛型支持，编译时类型检查
- ✅ **零依赖** - 核心库无第三方依赖

## 🚀 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap</artifactId>
    <version>1.0.0-BETA1</version>
</dependency>
```

### 基本使用

#### OffHeap 模式（堆外内存）

```java
import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;

// 创建一个 String -> Long 的堆外内存 Map
try (RogueMap<String, Long> map = RogueMap.<String, Long>offHeap()
        .keyCodec(StringCodec.INSTANCE)
        .valueCodec(PrimitiveCodecs.LONG)
        .maxMemory(100 * 1024 * 1024) // 100MB
        .build()) {

    // 存储数据
    map.put("user1", 1000L);
    map.put("user2", 2000L);

    // 读取数据
    Long score = map.get("user1");
    System.out.println("Score: " + score);

    // 更新数据
    map.put("user1", 1500L);

    // 删除数据
    map.remove("user2");

    // 检查存在
    boolean exists = map.containsKey("user1");

    // 获取大小
    int size = map.size();
}
```

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

### 支持的数据类型

RogueMap 提供了零拷贝的原始类型编解码器：

```java
// Long 类型（高性能）
RogueMap<Long, Long> longMap = RogueMap.<Long, Long>offHeap()
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// Integer 类型
RogueMap<Integer, Integer> intMap = RogueMap.<Integer, Integer>offHeap()
    .keyCodec(PrimitiveCodecs.INTEGER)
    .valueCodec(PrimitiveCodecs.INTEGER)
    .build();

// String 类型
RogueMap<String, String> stringMap = RogueMap.<String, String>offHeap()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(StringCodec.INSTANCE)
    .build();

// 混合类型
RogueMap<String, Double> mixedMap = RogueMap.<String, Double>offHeap()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.DOUBLE)
    .build();
```

**支持的原始类型**：`Long`, `Integer`, `Double`, `Float`, `Short`, `Byte`, `Boolean`

如果是对象类型，RogueMap也提供了对象的编码解析器：

```java
// 对象类型
RogueMap<Long, Long> longMap = RogueMap.<String, YourObject>offHeap()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(KryoObjectCodec.create(YourObject.class))
                .build();
```

### 索引选择

RogueMap 提供了多种索引策略，适用于不同场景：

```java
// 场景1: 高并发读写，推荐分段索引（默认）
RogueMap<String, String> concurrentMap = RogueMap.<String, String>offHeap()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(StringCodec.INSTANCE)
    .segmentedIndex(64)  // 64个段，减少锁竞争
    .build();

// 场景2: 内存敏感，Long键，推荐原始索引
RogueMap<Long, Long> memoryOptimized = RogueMap.<Long, Long>offHeap()
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .primitiveIndex()  // 节省81%内存
    .build();

// 场景3: 简单场景，推荐基础索引
RogueMap<String, Integer> simpleMap = RogueMap.<String, Integer>offHeap()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.INTEGER)
    .basicIndex()
    .build();
```

### 配置选项

#### OffHeap 模式配置

```java
RogueMap<K, V> map = RogueMap.<K, V>offHeap()
    // 必需配置
    .keyCodec(keyCodec)           // 键的编解码器
    .valueCodec(valueCodec)       // 值的编解码器

    // 可选配置
    .maxMemory(1024 * 1024 * 1024) // 最大内存 (默认 1GB)
        
    // 以下三种配置一种即可，或者不配置
    .basicIndex()                 // 使用基础索引
    .segmentedIndex(64)           // 使用分段索引 (默认)
    .primitiveIndex()             // 使用原始索引（仅Long/Integer键）
        
    .build();
```

#### Mmap 临时文件模式配置

```java
RogueMap<K, V> map = RogueMap.<K, V>mmap()
    // 必须配置
    .temporary()                  // 临时文件模式
    .keyCodec(keyCodec)           // 键的编解码器
    .valueCodec(valueCodec)       // 值的编解码器

    // 可选配置
    .allocateSize(10L * 1024 * 1024 * 1024) // 预分配大小 (默认 10GB)
        
    // 以下三种配置一种即可，或者不配置
    .basicIndex()                 // 使用基础索引
    .segmentedIndex(64)           // 使用分段索引 (默认)
    .primitiveIndex()             // 使用原始索引（仅Long/Integer键）
        
    .build();
```

#### Mmap 文件持久化模式配置

```java
RogueMap<K, V> map = RogueMap.<K, V>mmap()
    // 必需配置
    .persistent("data.db")        // 持久化文件路径
    .keyCodec(keyCodec)           // 键的编解码器
    .valueCodec(valueCodec)       // 值的编解码器

    // 可选配置
    .allocateSize(10L * 1024 * 1024 * 1024) // 预分配大小 (默认 10GB)

    // 以下三种配置一种即可，或者不配置
    .basicIndex()                 // 使用基础索引
    .segmentedIndex(64)           // 使用分段索引 (默认)
    .primitiveIndex()             // 使用原始索引（仅Long/Integer键）
        
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
| **RogueMap OffHeap** | 1,924ms | 854ms | 520K ops/s | 1,171K ops/s | **48 MB** | ❌ |
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

读取速度约为 HashMap 的 1/4，这是因为需要从堆外内存反序列化数据。但这个代价换来的是：
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
```

## 🏗️ 架构设计

```
RogueMap API
   ↓
Index Layer (HashIndex/SegmentedHashIndex/PrimitiveIndex)
   ↓
Storage Engine (OffHeapStorage/MmapStorage)
   ↓
Memory Allocator (SlabAllocator/MmapAllocator)
   ↓
UnsafeOps (Java 8 Unsafe)
   ↓
Off-Heap Memory / Memory-Mapped Files
```

### 核心模块

- **RogueMap** - 主类，提供 OffHeapBuilder 和 MmapBuilder 两个构建器
- **index** - 索引层
  - `HashIndex` - 基础哈希索引，基于 ConcurrentHashMap
  - `SegmentedHashIndex` - 分段哈希索引，64 个段 + StampedLock 乐观锁
  - `LongPrimitiveIndex` - Long 键原始数组索引，节省 81% 内存
  - `IntPrimitiveIndex` - Integer 键原始数组索引
- **storage** - 存储引擎
  - `OffHeapStorage` - 堆外内存存储
  - `MmapStorage` - 内存映射文件存储
- **memory** - 内存管理
  - `SlabAllocator` - Slab 分配器，7 个大小类别（16B 到 16KB）
  - `MmapAllocator` - 内存映射文件分配器，支持超过 2GB 的大文件
  - `UnsafeOps` - 底层 Unsafe API 操作
- **serialization** - 序列化层
  - `PrimitiveCodecs` - 原始类型零拷贝编解码器
  - `StringCodec` - String 编解码器
  - `KryoObjectCodec` - Kryo 对象序列化编解码器（可选）

### 内存管理机制

#### SlabAllocator（堆外内存）

- **分配策略**: 7 个 size class (16B, 64B, 256B, 1KB, 4KB, 16KB)
- **块大小**: 1MB
- **优化**: 空闲列表重用，负载因子自适应扩容
- **内存节省**: 相比 HashMap 节省 87% 堆内存

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
mvn test -Dtest=OffHeapFunctionalTest
mvn test -Dtest=MmapFunctionalTest
```

## 📝 系统要求

- Java 8
- Maven 3.6+

## ⚠️ 注意事项

1. **Unsafe API 警告** - 本项目使用 `sun.misc.Unsafe` API，这是内部 API，可能在未来版本中被移除。以后将添加 Java 17/21 的替代实现。

2. **内存管理** - 请确保正确关闭 RogueMap 实例以释放堆外内存：
   ```java
   try (RogueMap<K, V> map = ...) {
       // 使用 map
   } // 自动关闭，释放资源
   ```

3. **内存限制** - 堆外内存受 `-XX:MaxDirectMemorySize` JVM 参数限制，建议根据实际需求设置：
   ```bash
   java -XX:MaxDirectMemorySize=2g -jar your-app.jar
   ```

4. **文件大小** - Mmap 模式的 `allocateSize()` 会立即占用磁盘空间，请根据实际需求设置

5. **并发安全** - RogueMap 是线程安全的，支持高并发读写

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

## 🙏 致谢

本项目的设计灵感来自于：
- [MapDB](https://github.com/jankotek/mapdb) - 优秀的嵌入式数据库
- [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map) - 高性能堆外 Map

