# RogueMap 批量 API（putAll / getAll）设计

- 日期：2026-07-02
- 状态：设计已确认（用户已批准方案 B）
- 模块：`roguemap-core`
- 关联：第一批功能之一（批量 API → LangChain4j/Spring AI 适配器 → 崩溃恢复）

## 1. 背景与目标

RogueMap 目前只有单键操作（`put`/`get`/`remove`）。批量写入场景（初始化导入、缓存预热、批处理落盘）下，每个 `put` 都要独立获取一次段写锁、独立触发一次 checkpoint 计数，锁开销无法摊薄。

**目标**：提供 `putAll` / `getAll` 批量 API。`putAll` 按段分组、每段一次写锁批量更新（价值主要在 API 便利性与一次大批次只触发一次自动 checkpoint 计数；据实测原始写吞吐与循环 `put` 相当，详见成功标准）；`getAll` 提供便利的批量读。

**成功标准**：
- `putAll` 在批量导入基准中功能正确、吞吐与循环 `put` 相当（分段索引模式下）；据 10 万条短字符串实测，单线程加速比约 0.5–1.0x、6 线程并发约 0.65–0.8x，即与循环 put 基本持平或略低。putAll 的收益主要在 API 便利性与一次大批次只触发一次自动 checkpoint 计数，而非原始写吞吐提升（段锁竞争本就很低，锁摊薄收益不足以抵消 BatchEntry/entries/results 的额外开销，瓶颈在 allocator CAS 与值编码）。
- 所有索引模式（`basicIndex` / `segmentedIndex` / `primitiveIndex` / `lowHeapIndex`）功能正确；
- 不改变文件格式，与现有持久化/checkpoint/compact 机制完全兼容。

## 2. 范围

**包含**：
- `RogueMap.putAll(Map)` 及 TTL 变体
- `RogueMap.getAll(Collection)`
- `Index` 接口新增批量方法（带 default 循环实现）+ `SegmentedHashIndex` 优化覆写
- `AutoCheckpointManager` 批量计数方法

**不包含**（已与用户确认）：
- `removeAll`（YAGNI，有需求再加）
- 原子批量写（用户需要原子性时使用现有 `beginTransaction()`，这是事务的本职）
- RogueSet / RogueList / RogueQueue 的批量 API（后续按需）

## 3. API 设计

```java
// RogueMap 新增公开方法

/**
 * 批量放入键值对（使用默认 TTL）。
 * 语义与 java.util.Map.putAll 一致：不保证跨键原子性；
 * 单个键的更新各自原子。并发读写可与本操作交错。
 * 需要原子多键写入时请使用 beginTransaction()。
 */
public void putAll(Map<? extends K, ? extends V> m);

/** 批量放入键值对（指定 TTL，覆盖默认 TTL） */
public void putAll(Map<? extends K, ? extends V> m, long ttl, TimeUnit unit);

/**
 * 批量获取。返回结果 Map 中不包含未找到或已过期的键
 * （过期条目与 get() 一致做惰性删除）。
 */
public Map<K, V> getAll(Collection<? extends K> keys);
```

**参数校验**（与单键 API 保持一致）：
- `putAll(null)` / `getAll(null)` → `IllegalArgumentException`
- 批次中出现 `null` 键 → `IllegalArgumentException`（在校验阶段整批拒绝，早于任何内存分配，见错误处理）
- 空 map / 空 collection → 直接返回（no-op / 空结果）
- `getAll` 中的 null 元素 → 跳过（与 `get(null)` 返回 null 一致）

## 4. 索引层改动

### 4.1 `Index<K>` 接口新增

```java
/**
 * 批量更新索引（非原子）。默认实现逐条调用 putAndGetOld()。
 * 实现类可覆写以按内部分区分组、摊薄锁开销。
 * 返回数组与入参列表等长、顺序一一对应。
 */
default IndexUpdateResult[] putBatch(List<BatchEntry<K>> entries) {
    IndexUpdateResult[] results = new IndexUpdateResult[entries.size()];
    for (int i = 0; i < entries.size(); i++) {
        BatchEntry<K> e = entries.get(i);
        results[i] = putAndGetOld(e.key, e.address, e.size);
    }
    return results;
}
```

- 复用现有 `BatchEntry`（事务机制已有），仅使用其 `OpType.PUT` 形态。
- default 实现保证 `HashIndex` / 两个 Primitive 索引 / `LowHeapStringIndex` 零改动即正确（无锁合并收益，但语义一致）。

### 4.2 `SegmentedHashIndex<K>` 覆写

```java
@Override
public IndexUpdateResult[] putBatch(List<BatchEntry<K>> entries) {
    // 1. 按 getSegmentIndex(key) 分组（复用 applyBatch 的分组逻辑）
    // 2. 对每个段：writeLock 一次 → 应用该段全部 put → unlock
    //    段间互不依赖，逐段独立提交（与 applyBatch 的"全段同时持锁"不同）
    // 3. 结果按入参顺序回填
}
```

与 `applyBatch`（事务用，全段同时锁、支持回滚）的区别要在两个方法的 Javadoc 中明确写出，避免混用。

**同键重复处理**：同一批次内同一键出现多次时，以 `Map` 入参天然去重（`putAll(Map)` 不会产生重复键），`putBatch` 层面不做去重假设，按列表顺序依次应用（后者覆盖前者，前者旧地址正常返回并释放）。

## 5. putAll 数据流

```
putAll(m, ttl, unit)
  ├─ 1. 校验（null map / null key 整批拒绝）
  ├─ 2. 锁外编码分配（对每个 entry）：
  │      calculateSize → allocate(totalSize) → 写 TTL header → encode value
  │      → BatchEntry.put(key, address, actualTotalSize)
  │      （allocator 是 CAS 无锁的，本阶段不持任何段锁）
  ├─ 3. index.putBatch(entries) → IndexUpdateResult[]
  ├─ 4. 锁外释放旧值：对 wasPresent 的结果 allocator.free(oldAddress, oldSize)
  │      ── 不解码旧值（putAll 返回 void，省掉解码开销）
  └─ 5. autoCheckpointManager.onWriteOperations(n)（一批计 n 次写操作）
```

### AutoCheckpointManager 新增

```java
/** 批量记录 n 次写操作，计数逻辑与 onWriteOperation 一致（CAS 累加） */
public void onWriteOperations(int n);
```

现有 `onWriteOperation()` 改为委托调用 `onWriteOperations(1)`，保持单一计数路径。

## 6. getAll 设计

简单实现：循环复用 `get(key)` 的完整逻辑（含过期惰性删除），非 null 结果放入 `HashMap` 返回。

**明确不做段级优化**：分段索引的读路径是 StampedLock 乐观读，本来就几乎无锁竞争，批量化收益很小；getAll 的价值是 API 便利性，不为它复杂化读路径。

## 7. 错误处理

| 阶段 | 失败处理 |
|---|---|
| 校验/编码/分配阶段（索引未动） | 释放本批次已分配的全部内存后抛异常，**无任何副作用** |
| `putBatch` 索引更新阶段 | 已提交的段保持生效（非原子语义下符合预期）；异常向上抛出。Javadoc 明确"putAll 抛异常时部分条目可能已写入" |
| 旧值释放阶段 | `allocator.free` 是 no-op + 指标累加，不会抛出；防御性起见如有异常不回滚已完成的索引更新 |

编码阶段的单条失败（如 codec 无法确定大小）导致整批拒绝，这与"步骤 2 整体完成后才碰索引"的流程天然一致。

## 8. 兼容性

- **文件格式**：无变化（记录布局仍是 `[expireTime 8B][value]`），无需版本升级迁移。
- **索引模式**：全部支持；`lowHeapIndex` / `basicIndex` / `primitiveIndex` 走 default 循环实现。
- **事务**：`putAll` 与事务是两个独立入口，互不影响；文档指引"要原子性用事务"。
- **compact / checkpoint / TTL / 自动扩容**：复用现有单键路径的构件（TTLUtils、allocator、free 计入 dead bytes），行为一致。自动扩容场景下 `allocate` 可能触发 `expand()`，与单键 put 相同。

## 9. 测试计划

新增 `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java`：

**功能**：
1. putAll 基本写入 + getAll 回读一致
2. putAll 覆盖已有键：旧值不再可读，`getMetrics().deadBytes` 增加（验证旧内存已 free）
3. TTL 变体：过期后 getAll 结果中不含该键
4. 空 map / 空 collection no-op；null map/collection 抛 `IllegalArgumentException`
5. 批次含 null 键：整批拒绝且无内存泄漏（分配已回滚，entryCount 不变）
6. getAll 缺失键省略、null 元素跳过
7. 四种索引模式（segmented/basic/primitive/lowHeap）各跑一遍基本读写
8. 持久化：putAll → close → 重开 → getAll 数据完整

**并发**：
9. 多线程 putAll 与单键 get/put 并行，无异常且终态一致

**基准**（`benchmark/` 下，非门禁）：
10. putAll vs 循环 put 的吞吐对比（分段索引，10 万条）

## 10. 实现文件清单

| 文件 | 改动 |
|---|---|
| `RogueMap.java` | 新增 `putAll` ×2、`getAll` |
| `index/Index.java` | 新增 `putBatch` default 方法 |
| `index/SegmentedHashIndex.java` | 覆写 `putBatch`（逐段分组提交） |
| `AutoCheckpointManager.java` | 新增 `onWriteOperations(int)` |
| `map/BatchOperationTest.java` | 新增测试类 |
