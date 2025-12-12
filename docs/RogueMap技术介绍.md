# HashMap太慢?内存爆炸?这款国产键值引擎让性能飙升,还能省87%内存!

> "我只是想存点数据，为什么要把JVM搞崩溃？"

作为Java程序员，你一定遇到过这些令人抓狂的场景：

- 业务数据越来越大，HashMap占用几个G内存，Full GC停顿动不动就是好几秒
- 想做个本地缓存，结果把整个JVM堆都塞满了，OOM警告满天飞
- 服务重启后，几千万条数据要重新加载，等待时间长到喝咖啡都凉了
- 试了各种第三方KV存储，不是API太复杂就是性能不给力

**今天要给大家介绍的RogueMap,就是为了解决这些痛点而生的。**

它能做到什么程度？

- 🚀 **读性能提升2.4倍**,即使对比HashMap,吞吐量都能从200万ops/s飙到500万ops/s
- 💾 **内存占用减少87%**,100w个对象（每个对象10个属性）实测，堆内存从304MB降到40MB
- ⚡ **进程重启秒级恢复**,千万级数据自动加载
- 📦 **API简单到爆**,就像用HashMap一样顺滑

听起来有点不可思议？让我们用最通俗的方式，看看RogueMap到底是何方神圣。

---

## 一、RogueMap是什么?三句话说清楚

想象一下，你在玩游戏时有三种存储道具的方式：

1. **背包（OffHeap模式）** - 随身携带，速度快，但下线就清空
2. **仓库临时箱（Mmap临时模式）** - 容量更大，关服清空，适合临时囤货
3. **银行保险箱（Mmap持久模式）** - 容量巨大，永久保存，下次登录自动恢复

**RogueMap就是这样一个"三合一"的数据仓库**,你可以根据需要选择存储模式：

```java
// 模式1: 背包 - 纯内存,速度极快
RogueMap<String, User> cache = RogueMap.<String, User>offHeap()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(new KryoObjectCodec<>(User.class))
    .maxMemory(100 * 1024 * 1024)  // 100MB
    .build();

// 模式2: 临时仓库 - 文件映射,自动清理
RogueMap<Long, Long> tempData = RogueMap.<Long, Long>mmap()
    .temporary()
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// 模式3: 保险箱 - 持久化,重启恢复
RogueMap<String, Long> scores = RogueMap.<String, Long>mmap()
    .persistent("data/game_scores.db")
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

scores.put("玩家A", 99999L);
scores.flush();  // 保存
scores.close();

// 进程重启后...
scores = RogueMap.<String, Long>mmap()
    .persistent("data/game_scores.db")
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

Long score = scores.get("玩家A");  // 99999L - 数据还在!
```

**核心优势**:用HashMap的简单API,获得超越其限制的能力。

---

## 二、性能到底有多强?数据说话

我们用100万条数据做了测试，对比结果让人眼前一亮：

### 📊 RogueMap vs HashMap

| 指标 | HashMap | RogueMap(Mmap持久) | 提升 |
|------|---------|-------------------|------|
| **写入耗时** | 611ms | 547ms | ⬆️ 12% |
| **读取耗时** | 463ms | 195ms | ⬆️ **137%** |
| **读吞吐量** | 216万ops/s | 513万ops/s | ⬆️ **137%** |
| **堆内存占用** | 304MB | 40MB | ⬇️ **87%** |



### 🔥 RogueMap vs MapDB (竞品对比)

| 指标 | RogueMap | MapDB | 领先倍数 |
|------|----------|-------|---------|
| **读取速度** | 202ms | 3207ms | **15.9x** |
| **写入速度** | 632ms | 2764ms | **4.4x** |
| **读吞吐量** | 495万ops/s | 31万ops/s | **15.9x** |

MapDB是业内知名的嵌入式存储引擎，但在RogueMap面前，**读取性能直接被碾压15倍**。

---

## 三、为什么这么快?揭秘五大黑科技

看到这里，你可能会问："怎么做到的？是不是用了什么黑魔法？"

其实没有黑魔法，只有极致的工程优化。让我们用最简单的方式，揭开RogueMap的五大性能秘诀：

### 🎯 秘诀1: 堆外内存 - 让GC管不着

**问题**: HashMap把数据存在JVM堆内，数据越多，GC扫描越慢，停顿越久。

**RogueMap的做法**: 把数据存到堆外内存（DirectByteBuffer）或文件映射（Mmap)。

**效果**:
- JVM堆只需存40MB的索引结构
- GC扫描范围大幅减少，停顿从秒级降到毫秒级
- 数据量可以突破JVM堆限制，想存100GB都行

**类比**: 就像你把大量书籍从书桌（JVM堆）搬到书架（堆外内存）,书桌变整洁，找东西更快。

---

### ⚡ 秘诀2: 零拷贝序列化 - 原始类型直接怼

**问题**: 传统存储引擎要把Java对象序列化成字节数组，再存储。每次读写都要序列化/反序列化，慢得要命。

**RogueMap的做法**: 原始类型（Long、Integer等）直接写入内存，不经过任何转换。

```java
// ❌ 传统方式: 序列化开销巨大
byte[] bytes = serialize(value);  // Long -> 字节数组
storage.write(bytes);

// ✅ RogueMap方式: 直接内存操作
UnsafeOps.putLong(address, value);  // 8字节,一次性写入
```

**效果**: 读写延迟降低到纳秒级，吞吐量直接起飞。

**类比**: 就像快递员送包裹，传统方式要拆开检查再装箱，RogueMap直接扔过去。

---

### 🔓 秘诀3: 乐观并发 - 99%的操作不加锁

**问题**: HashMap在高并发场景要加锁，锁竞争严重时性能暴跌。

**RogueMap的做法**: 使用StampedLock的乐观读模式，大部分读操作完全不加锁。

```java
// 第一步: 乐观读(无锁)
long stamp = lock.tryOptimisticRead();
long value = readData(key);

// 第二步: 验证是否被其他线程修改
if (!lock.validate(stamp)) {
    // 冲突了才降级到读锁重试
    stamp = lock.readLock();
    value = readData(key);
    lock.unlockRead(stamp);
}
```

**效果**: 高并发场景下，读性能提升15倍。

**类比**: 图书馆不用每次借书都登记，只在发现书丢了才追查记录。

---

### 💾 秘诀4: 内存映射文件(Mmap) - 操作系统帮你缓存

**问题**: 传统文件IO需要从磁盘读到内核缓冲区，再拷贝到用户空间，路径太长。

**RogueMap的做法**: 使用Mmap把文件直接映射到内存地址空间。

**效果**:
- 操作系统自动管理页缓存，热数据常驻内存
- 读取时直接访问内存地址，速度接近纯内存
- 支持超大文件（>100GB),自动分段管理

**类比**: 传统方式像图书馆借书要填单子排队，Mmap就像书直接放在你桌上，拿来就看。

---

### 🧠 秘诀5: 极致的内存优化 - 原始类型数组索引

**问题**: HashMap的每个Entry要存储key引用、value引用、hash值、next指针，占用28字节。

**RogueMap的做法**: 针对Long类型键，用三个long数组存储索引，每条记录只占20字节。

```
传统HashMap的Entry结构:
┌────────────┬────────────┬────────┬────────┐
│ key引用(8B)│ value引用(8B)│ hash(4B)│ next(8B)│
└────────────┴────────────┴────────┴────────┘
= 28字节/条

RogueMap的LongPrimitiveIndex:
keys[]      : [123, 456, 789, ...]  (8字节/条)
addresses[] : [0x1000, 0x2000, ...] (8字节/条)
sizes[]     : [64, 128, 256, ...]   (4字节/条)
= 20字节/条
```

**效果**: 100万条数据，内存占用从104MB降到20MB,**节省81%**。

**类比**: 传统方式像每本书都配个厚厚的档案袋，RogueMap直接用Excel表格记录，省空间又高效。

---

## 四、三大应用场景,总有一个适合你

### 🎮 场景1: 游戏服务器 - 千万玩家数据秒级恢复

**痛点**: 游戏服务器维护重启，千万级玩家数据要重新加载，玩家等待时间长。

**RogueMap方案**:

```java
// 玩家数据持久化
RogueMap<Long, Player> playerDB = RogueMap.<Long, Player>mmap()
    .persistent("data/players.db")
    .keyCodec(PrimitiveCodecs.LONG)  // 玩家ID
    .valueCodec(new KryoObjectCodec<>(Player.class))
    .allocateSize(10L * 1024 * 1024 * 1024)  // 10GB
    .build();

// 写入数据
playerDB.put(10001L, new Player("张三", 99, "战士"));
playerDB.flush();

// 服务器重启后,自动恢复!
playerDB = RogueMap.<Long, Player>mmap()
    .persistent("data/players.db")
    .keyCodec(PrimitiveCodecs.LONG)
    .valueCodec(new KryoObjectCodec<>(Player.class))
    .build();

Player player = playerDB.get(10001L);  // 张三还在!
```

**收益**:
- ✅ 重启恢复从分钟级降到秒级
- ✅ 堆内存占用减少90%,Full GC基本消失
- ✅ 数据持久化，玩家数据永不丢失

---

### 📊 场景2: 推荐系统 - 亿级用户特征本地缓存

**痛点**: 推荐系统需要缓存亿级用户特征，Redis成本高，本地HashMap内存爆炸。

**RogueMap方案**:

```java
// 用户特征缓存
RogueMap<Long, UserFeature> featureCache = RogueMap.<Long, UserFeature>offHeap()
    .keyCodec(PrimitiveCodecs.LONG)  // 用户ID
    .valueCodec(new KryoObjectCodec<>(UserFeature.class))
    .maxMemory(50L * 1024 * 1024 * 1024)  // 50GB堆外内存
    .segmentedIndex(64)  // 64个分段,高并发
    .build();

// 高并发读取
UserFeature feature = featureCache.get(userId);
```

**收益**:
- ✅ 本地缓存，延迟从毫秒级降到微秒级
- ✅ 节省Redis集群成本，单机搞定
- ✅ 堆内存占用极低，GC压力小

---

### 🔬 场景3: 大数据处理 - 百GB临时数据不落盘

**痛点**: 数据清洗、ETL任务产生大量临时数据，写磁盘慢，放内存炸。

**RogueMap方案**:

```java
// 临时数据存储
RogueMap<String, Record> tempData = RogueMap.<String, Record>mmap()
    .temporary()  // 自动清理
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(new KryoObjectCodec<>(Record.class))
    .allocateSize(100L * 1024 * 1024 * 1024)  // 100GB
    .build();

// 处理海量数据
for (Record record : dataset) {
    String key = computeKey(record);
    tempData.put(key, record);
}

// 任务结束,自动清理临时文件
tempData.close();
```

**收益**:
- ✅ 容量突破内存限制，可达TB级
- ✅ 操作系统页缓存加速，速度接近内存
- ✅ 自动清理，不留垃圾文件

---

## 五、上手简单,五分钟集成

### Maven依赖

```xml
<dependency>
    <groupId>com.yomahub</groupId>
    <artifactId>roguemap</artifactId>
    <version>1.0.0-BETA1</version>
</dependency>
```

### 快速开始

```java
// 1. 创建RogueMap
RogueMap<String, Long> map = RogueMap.<String, Long>offHeap()
    .keyCodec(StringCodec.INSTANCE)
    .valueCodec(PrimitiveCodecs.LONG)
    .maxMemory(100 * 1024 * 1024)
    .build();

// 2. 使用方式和HashMap一模一样
map.put("apple", 100L);
map.put("banana", 200L);

Long value = map.get("apple");  // 100L
boolean exists = map.containsKey("banana");  // true
map.remove("apple");

// 3. 记得关闭释放资源
map.close();
```

**就是这么简单！**

---

## 六、技术亮点总结

让我们用一张表格，快速回顾RogueMap的核心优势：

| 维度 | HashMap | RogueMap | 优势 |
|------|---------|----------|------|
| **容量限制** | JVM堆大小 | 无限制(取决于磁盘) | ⬆️ 突破内存墙 |
| **GC压力** | 极高 | 极低 | ⬇️ 减少87%堆内存 |
| **读性能** | 快 | 更快 | ⬆️ 提升2.4倍 |
| **持久化** | 不支持 | 支持 | ✅ 秒级恢复 |
| **并发性能** | 中等 | 优秀 | ⬆️ 乐观读提升15倍 |
| **API复杂度** | 简单 | 简单 | 🎯 一致体验 |

---

## 七、RogueMap的作者是谁

RogueMap的作者是铂赛东，他也是LiteFlow，TLog等框架的作者。

之后RogueMap也会被使用在LiteFlow中。成为本地储存规则，脚本的方案。

其实这也是一开始写这个项目的初衷。

---

## 八、常见问题FAQ

### Q1: RogueMap支持哪些数据类型?

**A**:
- **原始类型**: Long、Integer、Short、Byte、Double、Float、Boolean(零拷贝，性能最优）
- **String**: 内置StringCodec
- **对象**: 使用KryoObjectCodec(需要Kryo依赖）

### Q2: 堆外内存会不会泄漏?

**A**: RogueMap使用引用计数管理内存，调用`close()`会自动释放。建议使用try-with-resources:

```java
try (RogueMap<K, V> map = RogueMap.<K, V>offHeap()...build()) {
    // 使用map
}  // 自动释放
```

### Q3: 支持并发吗?

**A**: 支持！SegmentedHashIndex使用分段锁+乐观读，高并发场景性能优秀。

### Q4: 和Redis、RocksDB比如何选择?

**A**:
- **Redis**: 分布式缓存，网络开销大，适合多机共享
- **RocksDB**: 功能强大，API复杂，适合复杂查询
- **RogueMap**: 本地缓存，API简单，适合单机高性能场景

### Q5: 能存多大的数据?

**A**:

- OffHeap模式： 受限于机器内存
- Mmap模式： 受限于磁盘空间，理论上TB级都可以

### Q6:为什么现在版本是1.0.0-BETA1

**A**:对，这是一个新项目，BETA1已经通过了100多个测试用例，已经表现很出色了。之后还会添加List，Set，Queue的支持。大家可以先试用下。希望大家持续关注这个项目。

---

## 九、写在最后

在Java生态中，我们不缺HashMap这样的内存数据结构，也不缺RocksDB这样的重量级存储引擎。

**但我们缺少一个简单、高效、堆外内存的本地KV存储方案。**

RogueMap就是为了填补这个空白而生：

- ✅ **简单**: HashMap级别的API,5分钟上手
- ✅ **高效**: 读性能提升2.4倍，内存节省87%
- ✅ **灵活**: 三种存储模式，覆盖绝大多数场景
- ✅ **可靠**: 持久化+自动恢复，数据不丢失

**如果你的应用正在被大数据量、高GC压力、慢速度困扰，不妨试试RogueMap。**

也许，它就是你一直在寻找的那个答案。

---

**项目地址**: 
Gitee：[https://github.com/bryan31/RogueMap](https://github.com/bryan31/RogueMap)

GitHub：[https://github.com/bryan31/RogueMap](https://github.com/bryan31/RogueMap)

**觉得有用？给个Star支持一下！⭐**

**有问题？提Issue或评论区交流！💬**

---

