# RogueMap 批量 API（putAll / getAll）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RogueMap 新增非原子高吞吐的 `putAll` / `getAll` 批量 API，分段索引模式下按段分组、每段一次写锁批量提交。

**Architecture:** 自底向上四层改动：`AutoCheckpointManager` 加批量计数 → `Index` 接口加 `putBatch` default 方法（循环 fallback，四种索引模式全兼容）→ `SegmentedHashIndex` 覆写为逐段分组提交 → `RogueMap` 组装完整数据流（锁外编码分配 → 批量索引更新 → 锁外释放旧值）。设计规格见 `docs/superpowers/specs/2026-07-02-roguemap-batch-api-design.md`。

**Tech Stack:** Java 8、JUnit 5（org.junit.jupiter）、Maven 多模块（只动 `roguemap-core`）。

## Global Constraints

- Java 8 语法（roguemap-core 基线）：不用 `var`、`List.of`、Stream 链式风格，与现有代码一致
- 零新增依赖
- 注释与 Javadoc 用中文（项目惯例）
- 不改文件格式 / 持久化布局
- `putAll` 为**非原子**语义（用户已确认方案 B）；原子需求走现有 `beginTransaction()`
- `putBatch` 仅支持 `OpType.PUT` 条目，REMOVE 条目抛 `IllegalArgumentException`
- 测试命令模板：`mvn test -pl roguemap-core -Dtest=<ClassName>`
- 每个提交信息用中文，结尾加：`Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `roguemap-core/src/main/java/com/yomahub/roguemap/AutoCheckpointManager.java` | 修改 | 新增 `onWriteOperations(int)`，`onWriteOperation()` 委托它 |
| `roguemap-core/src/main/java/com/yomahub/roguemap/index/Index.java` | 修改 | 新增 `putBatch` default 方法 |
| `roguemap-core/src/main/java/com/yomahub/roguemap/index/SegmentedHashIndex.java` | 修改 | 覆写 `putBatch`：逐段分组、每段一次写锁 |
| `roguemap-core/src/main/java/com/yomahub/roguemap/RogueMap.java` | 修改 | 新增 `putAll` ×2、`getAll` |
| `roguemap-core/src/test/java/com/yomahub/roguemap/common/AutoCheckpointBatchCountTest.java` | 创建 | 批量计数单元测试 |
| `roguemap-core/src/test/java/com/yomahub/roguemap/index/IndexPutBatchTest.java` | 创建 | putBatch 索引层单元测试（default 实现 + 分段覆写） |
| `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java` | 创建 | putAll/getAll 功能、矩阵、持久化、并发测试 |
| `roguemap-core/src/test/java/com/yomahub/roguemap/benchmark/BatchPutBenchmarkTest.java` | 创建 | putAll vs 循环 put 吞吐对比（非门禁） |

---

### Task 1: AutoCheckpointManager 批量写操作计数

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/AutoCheckpointManager.java`（现有 `onWriteOperation()` 在第 86-99 行）
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/common/AutoCheckpointBatchCountTest.java`

**Interfaces:**
- Consumes: 现有 `AutoCheckpointManager(Runnable checkpointAction, long intervalMillis, int operationThreshold)` 公开构造函数、`start()`、`stop()`、私有 `doCheckpoint()`、`AtomicInteger operationCount` 字段
- Produces: `public void onWriteOperations(int n)` — Task 4 的 putAll 调用它

- [ ] **Step 1: 写失败测试**

创建 `roguemap-core/src/test/java/com/yomahub/roguemap/common/AutoCheckpointBatchCountTest.java`：

```java
package com.yomahub.roguemap.common;

import com.yomahub.roguemap.AutoCheckpointManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AutoCheckpointManager 批量写操作计数测试
 */
public class AutoCheckpointBatchCountTest {

    @Test
    public void testBatchCountTriggersCheckpoint() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 10);
        mgr.start();

        mgr.onWriteOperations(4);
        mgr.onWriteOperations(4);
        assertEquals(0, checkpoints.get());

        // 累计 12 >= 10，触发一次并重置计数
        mgr.onWriteOperations(4);
        assertEquals(1, checkpoints.get());

        // 计数已重置，9 < 10 不触发
        mgr.onWriteOperations(9);
        assertEquals(1, checkpoints.get());

        mgr.stop();
    }

    @Test
    public void testSingleOperationDelegatesToBatch() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 3);
        mgr.start();

        mgr.onWriteOperation();
        mgr.onWriteOperation();
        assertEquals(0, checkpoints.get());

        mgr.onWriteOperation();
        assertEquals(1, checkpoints.get());

        mgr.stop();
    }

    @Test
    public void testNotStartedDoesNotTrigger() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 2);
        // 未调用 start()
        mgr.onWriteOperations(5);
        assertEquals(0, checkpoints.get());
    }

    @Test
    public void testNonPositiveCountIgnored() {
        AtomicInteger checkpoints = new AtomicInteger(0);
        AutoCheckpointManager mgr = new AutoCheckpointManager(
                checkpoints::incrementAndGet, -1, 1);
        mgr.start();
        mgr.onWriteOperations(0);
        mgr.onWriteOperations(-3);
        assertEquals(0, checkpoints.get());
        mgr.stop();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl roguemap-core -Dtest=AutoCheckpointBatchCountTest`
Expected: **COMPILATION ERROR** — `cannot find symbol: method onWriteOperations(int)`

- [ ] **Step 3: 最小实现**

在 `AutoCheckpointManager.java` 中，将现有 `onWriteOperation()`（第 83-99 行）替换为：

```java
    /**
     * 写操作后调用，用于操作计数模式
     */
    public void onWriteOperation() {
        onWriteOperations(1);
    }

    /**
     * 批量记录 n 次写操作（操作计数模式）。
     *
     * <p>计数逻辑与单次记录一致：累计达到阈值时通过 CAS 重置计数并触发一次
     * checkpoint，避免并发下重复触发。
     *
     * @param n 本次记录的写操作次数，非正数忽略
     */
    public void onWriteOperations(int n) {
        if (!running || operationThreshold <= 0 || n <= 0) {
            return;
        }

        int count = operationCount.addAndGet(n);
        if (count >= operationThreshold) {
            // 重置计数器并触发 checkpoint
            // 使用 compareAndSet 避免多次触发
            if (operationCount.compareAndSet(count, 0)) {
                doCheckpoint();
            }
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl roguemap-core -Dtest=AutoCheckpointBatchCountTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0` → BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/AutoCheckpointManager.java \
        roguemap-core/src/test/java/com/yomahub/roguemap/common/AutoCheckpointBatchCountTest.java
git commit -m "feat(core): AutoCheckpointManager 支持批量写操作计数

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Index 接口 putBatch default 方法

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/index/Index.java`
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/index/IndexPutBatchTest.java`（新建，Task 3 会追加用例）

**Interfaces:**
- Consumes: 现有 `Index.putAndGetOld(K key, long newAddress, int newSize)`；`BatchEntry<K>`（公开字段 `opType`/`key`/`newAddress`/`newSize`，工厂 `BatchEntry.put(key, addr, size)`）；`IndexUpdateResult`（公开字段 `wasPresent`/`oldAddress`/`oldSize`）；`HashIndex<K>` 构造函数 `new HashIndex<>(keyCodec, 16)`
- Produces: `default IndexUpdateResult[] putBatch(List<BatchEntry<K>> entries)` — Task 3 覆写它，Task 4 通过 `Index<K>` 引用调用它

- [ ] **Step 1: 写失败测试**

创建 `roguemap-core/src/test/java/com/yomahub/roguemap/index/IndexPutBatchTest.java`：

```java
package com.yomahub.roguemap.index;

import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Index.putBatch 单元测试
 *
 * <p>索引层不触碰真实内存，测试使用假地址（如 1000L）。
 */
public class IndexPutBatchTest {

    // ========== default 实现（经由 HashIndex）==========

    @Test
    public void testDefaultPutBatchBasic() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));
        entries.add(BatchEntry.put("b", 2000L, 20));

        IndexUpdateResult[] results = index.putBatch(entries);

        assertEquals(2, results.length);
        assertFalse(results[0].wasPresent);
        assertFalse(results[1].wasPresent);
        assertEquals(1000L, index.get("a"));
        assertEquals(2000L, index.get("b"));
        assertEquals(2, index.size());
    }

    @Test
    public void testDefaultPutBatchReturnsOldValues() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);
        index.put("a", 500L, 5);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));

        IndexUpdateResult[] results = index.putBatch(entries);

        assertTrue(results[0].wasPresent);
        assertEquals(500L, results[0].oldAddress);
        assertEquals(5, results[0].oldSize);
        assertEquals(1000L, index.get("a"));
        assertEquals(1, index.size());
    }

    @Test
    public void testDefaultPutBatchEmptyAndNull() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);
        assertEquals(0, index.putBatch(new ArrayList<>()).length);
        assertEquals(0, index.putBatch(null).length);
    }

    @Test
    public void testDefaultPutBatchRejectsRemoveEntries() {
        Index<String> index = new HashIndex<>(new StringCodec(), 16);
        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));
        entries.add(BatchEntry.remove("b"));

        assertThrows(IllegalArgumentException.class, () -> index.putBatch(entries));
        // 校验先于应用：整批拒绝，"a" 未写入
        assertEquals(0, index.get("a"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl roguemap-core -Dtest=IndexPutBatchTest`
Expected: **COMPILATION ERROR** — `cannot find symbol: method putBatch`

- [ ] **Step 3: 最小实现**

修改 `roguemap-core/src/main/java/com/yomahub/roguemap/index/Index.java`：

在文件顶部 import 区（`import com.yomahub.roguemap.memory.AddressTranslator;` 之后）加：

```java
import java.util.List;
```

在 `removeAndGet` 方法声明之后加：

```java
    /**
     * 批量更新索引（<b>非原子</b>）。
     *
     * <p>默认实现逐条调用 {@link #putAndGetOld}。实现类可覆写以按内部分区
     * 分组、摊薄锁开销（见 {@link SegmentedHashIndex#putBatch}）。
     *
     * <p>仅支持 {@link BatchEntry.OpType#PUT} 条目；出现 REMOVE 条目时在
     * 应用任何操作之前抛出 {@link IllegalArgumentException}（整批拒绝）。
     *
     * @param entries 批量 PUT 条目列表（null 或空列表返回空数组）
     * @return 与 entries 等长、顺序一一对应的旧值信息数组
     */
    default IndexUpdateResult[] putBatch(List<BatchEntry<K>> entries) {
        if (entries == null || entries.isEmpty()) {
            return new IndexUpdateResult[0];
        }
        for (BatchEntry<K> e : entries) {
            if (e.opType != BatchEntry.OpType.PUT) {
                throw new IllegalArgumentException("putBatch 仅支持 PUT 操作");
            }
        }
        IndexUpdateResult[] results = new IndexUpdateResult[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            BatchEntry<K> e = entries.get(i);
            results[i] = putAndGetOld(e.key, e.newAddress, e.newSize);
        }
        return results;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl roguemap-core -Dtest=IndexPutBatchTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0` → BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/index/Index.java \
        roguemap-core/src/test/java/com/yomahub/roguemap/index/IndexPutBatchTest.java
git commit -m "feat(core): Index 接口新增 putBatch 批量更新默认实现

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: SegmentedHashIndex 覆写 putBatch（逐段分组提交）

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/index/SegmentedHashIndex.java`（在 `applyBatch` 方法之后、`rollbackApplied` 之前插入）
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/index/IndexPutBatchTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 2 的 `Index.putBatch` 签名；`SegmentedHashIndex` 内部结构（`segments[]` 数组、内部类 `Segment<K>` 的 `lock`（StampedLock）与 `map`（HashMap<K, Entry>）字段、内部类 `Entry(address, size)`、`AtomicInteger size` 字段、`public int getSegmentIndex(K key)`）；构造函数 `new SegmentedHashIndex<>(keyCodec, 64, 16)`
- Produces: `@Override public IndexUpdateResult[] putBatch(List<BatchEntry<K>> entries)` — 语义同接口声明，但按段仅加一次写锁

- [ ] **Step 1: 写失败测试**

在 `IndexPutBatchTest.java` 末尾（最后一个 `}` 之前）追加：

```java
    // ========== SegmentedHashIndex 覆写 ==========

    @Test
    public void testSegmentedPutBatchManyKeys() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entries.add(BatchEntry.put("key" + i, 10000L + i, 8));
        }

        IndexUpdateResult[] results = index.putBatch(entries);

        assertEquals(1000, results.length);
        assertEquals(1000, index.size());
        for (int i = 0; i < 1000; i++) {
            assertFalse(results[i].wasPresent);
            assertEquals(10000L + i, index.get("key" + i));
        }
    }

    @Test
    public void testSegmentedPutBatchReturnsOldValuesInInputOrder() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);
        index.put("exist1", 111L, 1);
        index.put("exist2", 222L, 2);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("new1", 1000L, 10));
        entries.add(BatchEntry.put("exist2", 2000L, 20));
        entries.add(BatchEntry.put("exist1", 3000L, 30));

        IndexUpdateResult[] results = index.putBatch(entries);

        // 结果必须按入参顺序回填，与段分组顺序无关
        assertFalse(results[0].wasPresent);
        assertTrue(results[1].wasPresent);
        assertEquals(222L, results[1].oldAddress);
        assertTrue(results[2].wasPresent);
        assertEquals(111L, results[2].oldAddress);
        assertEquals(3, index.size());
    }

    @Test
    public void testSegmentedPutBatchDuplicateKeyAppliedInOrder() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("dup", 100L, 1));
        entries.add(BatchEntry.put("dup", 200L, 2));

        IndexUpdateResult[] results = index.putBatch(entries);

        // 同键按列表顺序依次应用：后者覆盖前者，前者作为旧值返回
        assertFalse(results[0].wasPresent);
        assertTrue(results[1].wasPresent);
        assertEquals(100L, results[1].oldAddress);
        assertEquals(200L, index.get("dup"));
        assertEquals(1, index.size());
    }

    @Test
    public void testSegmentedPutBatchRejectsRemoveEntries() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);

        List<BatchEntry<String>> entries = new ArrayList<>();
        entries.add(BatchEntry.put("a", 1000L, 10));
        entries.add(BatchEntry.remove("b"));

        assertThrows(IllegalArgumentException.class, () -> index.putBatch(entries));
        // 校验在分组阶段完成（任何加锁之前），整批拒绝
        assertEquals(0, index.get("a"));
        assertEquals(0, index.size());
    }

    @Test
    public void testSegmentedPutBatchEmptyAndNull() {
        SegmentedHashIndex<String> index = new SegmentedHashIndex<>(new StringCodec(), 64, 16);
        assertEquals(0, index.putBatch(new ArrayList<>()).length);
        assertEquals(0, index.putBatch(null).length);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl roguemap-core -Dtest=IndexPutBatchTest`
Expected: 编译通过（default 方法已存在），但 `testSegmentedPutBatch*` 全部走 default 实现也会通过——**注意**：这几个用例对 default 实现同样成立，本任务的验证点是覆写后行为不变且实现真正分段加锁。因此此步预期 **PASS**（9 个用例全绿）。这是行为保持型覆写，红灯来自下一步之前不存在覆写方法本身——用 Step 3 后的代码评审确认锁路径，不依赖红灯。

- [ ] **Step 3: 实现覆写**

在 `SegmentedHashIndex.java` 的 `applyBatch` 方法结束（约第 276 行 `}` 之后）、`rollbackApplied` 之前插入：

```java
    /**
     * 批量更新索引（<b>非原子</b>）——putAll 的高吞吐路径。
     *
     * <p>与 {@link #applyBatch}（事务用：全部涉及段<b>同时</b>持写锁、支持
     * 补偿回滚、保证原子性）不同，本方法按段分组后<b>逐段独立</b>加锁提交，
     * 段间不保证原子性，锁持有时间短、并发更友好。
     *
     * <p>同段内按入参顺序应用；同键多次出现时后者覆盖前者，前者作为旧值返回。
     *
     * @param entries 批量 PUT 条目列表（null 或空列表返回空数组）
     * @return 与 entries 等长、顺序一一对应的旧值信息数组
     */
    @Override
    public IndexUpdateResult[] putBatch(List<BatchEntry<K>> entries) {
        if (entries == null || entries.isEmpty()) {
            return new IndexUpdateResult[0];
        }

        // 分组阶段（任何加锁之前）：按 segment 分组并校验操作类型
        TreeMap<Integer, List<Integer>> segToOps = new TreeMap<>();
        for (int i = 0; i < entries.size(); i++) {
            BatchEntry<K> op = entries.get(i);
            if (op.opType != BatchEntry.OpType.PUT) {
                throw new IllegalArgumentException("putBatch 仅支持 PUT 操作");
            }
            int segIdx = getSegmentIndex(op.key);
            segToOps.computeIfAbsent(segIdx, k -> new ArrayList<>()).add(i);
        }

        IndexUpdateResult[] results = new IndexUpdateResult[entries.size()];

        // 逐段独立加锁提交
        for (Map.Entry<Integer, List<Integer>> group : segToOps.entrySet()) {
            Segment<K> seg = segments[group.getKey()];
            long stamp = seg.lock.writeLock();
            try {
                for (int opIdx : group.getValue()) {
                    BatchEntry<K> op = entries.get(opIdx);
                    Entry oldEntry = seg.map.put(op.key, new Entry(op.newAddress, op.newSize));
                    if (oldEntry != null) {
                        results[opIdx] = IndexUpdateResult.withOldValue(oldEntry.address, oldEntry.size);
                    } else {
                        results[opIdx] = IndexUpdateResult.noOldValue();
                        size.incrementAndGet();
                    }
                }
            } finally {
                seg.lock.unlockWrite(stamp);
            }
        }

        return results;
    }
```

（`TreeMap`、`List`、`ArrayList`、`Map` 均已被 `applyBatch` 使用，无需新增 import。）

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl roguemap-core -Dtest=IndexPutBatchTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0` → BUILD SUCCESS

再跑事务回归（确认未影响 applyBatch）：
Run: `mvn test -pl roguemap-core -Dtest=TransactionTest`
Expected: 全部通过

- [ ] **Step 5: 提交**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/index/SegmentedHashIndex.java \
        roguemap-core/src/test/java/com/yomahub/roguemap/index/IndexPutBatchTest.java
git commit -m "feat(core): SegmentedHashIndex 覆写 putBatch 逐段批量提交

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: RogueMap.putAll（两个重载）

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/RogueMap.java`（在 `put(K, V, long, TimeUnit)` 方法结束、`get` 之前插入；import 区补充）
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 `autoCheckpointManager.onWriteOperations(int)`；Task 2/3 的 `index.putBatch(List<BatchEntry<K>>)`；现有字段 `valueCodec`/`allocator`/`index`/`defaultTTLMillis`/`autoCheckpointManager`；`TTLUtils.totalSize(int)` / `calculateExpireTime(long)` / `writeExpireTime(long, long)` / `getDataAddress(long)`；`BatchEntry.put(key, addr, size)`
- Produces: `public void putAll(Map<? extends K, ? extends V> m)`、`public void putAll(Map<? extends K, ? extends V> m, long ttl, TimeUnit unit)` — Task 5/6/7 的测试使用

- [ ] **Step 1: 写失败测试**

创建 `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java`：

```java
package com.yomahub.roguemap.map;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueMap 批量 API（putAll/getAll）功能测试
 */
public class BatchOperationTest {

    private RogueMap<String, String> newTempMap() {
        return RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
    }

    // ========== putAll 基本功能 ==========

    @Test
    public void testPutAllBasic() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                batch.put("key" + i, "value" + i);
            }
            map.putAll(batch);

            assertEquals(100, map.size());
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, map.get("key" + i));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllOverwriteFreesOldMemory() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch1 = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                batch1.put("key" + i, "old-value-" + i);
            }
            map.putAll(batch1);
            long deadBefore = map.getMetrics().getDeadBytes();

            Map<String, String> batch2 = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                batch2.put("key" + i, "new-value-" + i);
            }
            map.putAll(batch2);

            assertEquals(50, map.size());
            for (int i = 0; i < 50; i++) {
                assertEquals("new-value-" + i, map.get("key" + i));
            }
            // 旧值内存已释放（计入 dead bytes）
            assertTrue(map.getMetrics().getDeadBytes() > deadBefore);
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllWithTTL() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            batch.put("ttl-key1", "v1");
            batch.put("ttl-key2", "v2");
            map.putAll(batch, 300, TimeUnit.MILLISECONDS);

            assertEquals("v1", map.get("ttl-key1"));
            assertEquals("v2", map.get("ttl-key2"));

            Thread.sleep(500);

            assertNull(map.get("ttl-key1"));
            assertNull(map.get("ttl-key2"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllEmptyIsNoop() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.putAll(new HashMap<>());
            assertEquals(0, map.size());
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllNullMapThrows() {
        RogueMap<String, String> map = newTempMap();
        try {
            assertThrows(IllegalArgumentException.class, () -> map.putAll(null));
        } finally {
            map.close();
        }
    }

    @Test
    public void testPutAllNullKeyRejectsWholeBatch() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            batch.put("ok-key", "v");
            batch.put(null, "v2");

            assertThrows(IllegalArgumentException.class, () -> map.putAll(batch));

            // 校验先于分配：整批拒绝，无任何条目写入
            assertEquals(0, map.size());
            assertNull(map.get("ok-key"));
        } finally {
            map.close();
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl roguemap-core -Dtest=BatchOperationTest`
Expected: **COMPILATION ERROR** — `cannot find symbol: method putAll`

- [ ] **Step 3: 最小实现**

修改 `roguemap-core/src/main/java/com/yomahub/roguemap/RogueMap.java`：

(a) import 区：在 `import com.yomahub.roguemap.index.HashIndex;` 之前加：

```java
import com.yomahub.roguemap.index.BatchEntry;
```

在 `import java.util.Iterator;` 之前加：

```java
import java.util.ArrayList;
```

在 `import java.util.Map;` 之后加：

```java
import java.util.List;
```

(b) 在 `put(K key, V value, long ttl, TimeUnit unit)` 方法结束（第 150 行 `}` 之后）插入：

```java
    /**
     * 批量放入键值对（使用默认 TTL）。
     *
     * <p>语义与 {@link java.util.Map#putAll} 一致：<b>不保证跨键原子性</b>。
     * 单个键的更新各自原子，并发读写可与本操作交错；抛出异常时部分条目可能
     * 已写入。需要原子多键写入时请使用 {@link #beginTransaction()}。
     *
     * <p>分段索引模式下按段分组、每段仅加一次写锁，但据 10 万条短字符串基准实测，
     * 单线程加速比约 0.5–1.0x、6 线程并发约 0.65–0.8x，与循环 put 基本持平或略低；
     * putAll 的价值主要在 API 便利性与一次大批次只触发一次自动 checkpoint 计数。
     * 其他索引模式下退化为逐条更新（功能等价）。
     *
     * @param m 键值对集合
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        putAll(m, defaultTTLMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 批量放入键值对（指定 TTL，覆盖默认 TTL）。
     *
     * <p>整批条目使用同一过期时间戳。其余语义见 {@link #putAll(Map)}。
     *
     * @param m    键值对集合
     * @param ttl  过期时间（0 表示永不过期）
     * @param unit 时间单位
     */
    public void putAll(Map<? extends K, ? extends V> m, long ttl, TimeUnit unit) {
        if (m == null) {
            throw new IllegalArgumentException("批量集合不能为 null");
        }
        if (m.isEmpty()) {
            return;
        }
        // 校验阶段：null 键整批拒绝（早于任何内存分配，无副作用）
        for (K key : m.keySet()) {
            if (key == null) {
                throw new IllegalArgumentException("键不能为 null");
            }
        }

        long ttlMillis = (ttl > 0 && unit != null) ? unit.toMillis(ttl) : 0;
        long expireTime = TTLUtils.calculateExpireTime(ttlMillis);

        // 编码分配阶段（锁外）：失败则释放本批次已分配内存后抛出，索引未动
        List<BatchEntry<K>> entries = new ArrayList<>(m.size());
        try {
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                int valueSize = valueCodec.calculateSize(e.getValue());
                if (valueSize < 0) {
                    throw new IllegalStateException("无法确定值的大小");
                }
                int totalSize = TTLUtils.totalSize(valueSize);
                long newAddress = allocator.allocate(totalSize);
                if (newAddress == 0) {
                    throw new OutOfMemoryError("分配 " + totalSize + " 字节失败");
                }
                try {
                    TTLUtils.writeExpireTime(newAddress, expireTime);
                    int actualSize = valueCodec.encode(TTLUtils.getDataAddress(newAddress), e.getValue());
                    entries.add(BatchEntry.put(e.getKey(), newAddress, TTLUtils.totalSize(actualSize)));
                } catch (RuntimeException | Error encodeErr) {
                    // 当前条目已分配但尚未记入 entries，单独释放
                    allocator.free(newAddress, totalSize);
                    throw encodeErr;
                }
            }
        } catch (RuntimeException | Error err) {
            for (BatchEntry<K> entry : entries) {
                allocator.free(entry.newAddress, entry.newSize);
            }
            throw err;
        }

        // 索引更新阶段：分段索引按段批量提交；异常时已提交的段保持生效（非原子语义）
        IndexUpdateResult[] results = index.putBatch(entries);

        // 旧值释放阶段（锁外，不解码旧值）
        for (IndexUpdateResult result : results) {
            if (result.wasPresent) {
                allocator.free(result.oldAddress, result.oldSize);
            }
        }

        if (autoCheckpointManager != null) {
            autoCheckpointManager.onWriteOperations(entries.size());
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl roguemap-core -Dtest=BatchOperationTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0` → BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/RogueMap.java \
        roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java
git commit -m "feat(core): RogueMap 新增 putAll 批量写入 API

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: RogueMap.getAll

**Files:**
- Modify: `roguemap-core/src/main/java/com/yomahub/roguemap/RogueMap.java`（在 `get(K key)` 方法结束之后插入；import 区补充）
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java`（追加用例）

**Interfaces:**
- Consumes: 现有 `get(K key)`（含惰性过期删除）；Task 4 的 `putAll`
- Produces: `public Map<K, V> getAll(Collection<? extends K> keys)` — Task 6/7 的测试使用

- [ ] **Step 1: 写失败测试**

在 `BatchOperationTest.java` 末尾（最后一个 `}` 之前）追加，并在 import 区加 `import java.util.Arrays;`、`import java.util.ArrayList;`：

```java
    // ========== getAll ==========

    @Test
    public void testGetAllRoundtrip() {
        RogueMap<String, String> map = newTempMap();
        try {
            Map<String, String> batch = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                batch.put("key" + i, "value" + i);
            }
            map.putAll(batch);

            Map<String, String> got = map.getAll(batch.keySet());
            assertEquals(100, got.size());
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, got.get("key" + i));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllMissingKeysOmitted() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("exists", "v");

            Map<String, String> got = map.getAll(Arrays.asList("exists", "missing1", "missing2"));

            assertEquals(1, got.size());
            assertEquals("v", got.get("exists"));
            assertFalse(got.containsKey("missing1"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllNullElementsSkipped() {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("k", "v");

            Map<String, String> got = map.getAll(new ArrayList<>(Arrays.asList("k", null)));

            assertEquals(1, got.size());
            assertEquals("v", got.get("k"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllExpiredKeysOmitted() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        try {
            map.put("eternal", "v1");
            map.put("mortal", "v2", 200, TimeUnit.MILLISECONDS);

            Thread.sleep(400);

            Map<String, String> got = map.getAll(Arrays.asList("eternal", "mortal"));
            assertEquals(1, got.size());
            assertEquals("v1", got.get("eternal"));
        } finally {
            map.close();
        }
    }

    @Test
    public void testGetAllNullCollectionThrows() {
        RogueMap<String, String> map = newTempMap();
        try {
            assertThrows(IllegalArgumentException.class, () -> map.getAll(null));
        } finally {
            map.close();
        }
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl roguemap-core -Dtest=BatchOperationTest`
Expected: **COMPILATION ERROR** — `cannot find symbol: method getAll`

- [ ] **Step 3: 最小实现**

修改 `RogueMap.java`：

(a) import 区：在 `import java.util.ArrayList;` 之后加：

```java
import java.util.Collection;
import java.util.HashMap;
```

(b) 在 `get(K key)` 方法结束之后插入：

```java
    /**
     * 批量获取。
     *
     * <p>返回的 Map 中不包含未找到或已过期的键（过期条目与 {@link #get}
     * 一致做惰性删除）；集合中的 null 元素被跳过。
     *
     * <p>说明：分段索引的读路径是乐观读，本方法不做段级批量优化，
     * 价值在于 API 便利性。
     *
     * @param keys 键集合
     * @return 命中的键值对（可修改的 HashMap，无序）
     */
    public Map<K, V> getAll(Collection<? extends K> keys) {
        if (keys == null) {
            throw new IllegalArgumentException("键集合不能为 null");
        }
        Map<K, V> result = new HashMap<>(Math.max(16, keys.size() * 2));
        for (K key : keys) {
            if (key == null) {
                continue;
            }
            V value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl roguemap-core -Dtest=BatchOperationTest`
Expected: `Tests run: 11, Failures: 0, Errors: 0` → BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add roguemap-core/src/main/java/com/yomahub/roguemap/RogueMap.java \
        roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java
git commit -m "feat(core): RogueMap 新增 getAll 批量读取 API

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 索引模式矩阵 + 持久化测试

**Files:**
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 4/5 的 `putAll`/`getAll`；builder 选项 `basicIndex()` / `primitiveIndex()`（仅 Long/Integer 键）/ `lowHeapIndex()`（仅 StringCodec）/ `persistent(path)`；`PrimitiveCodecs.LONG`
- Produces: 无新接口（纯测试任务）

- [ ] **Step 1: 追加测试**

在 `BatchOperationTest.java` 中：

(a) import 区加：

```java
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
```

(b) 类首部（`newTempMap()` 方法之前）加持久化文件管理：

```java
    private static final String TEST_FILE = "target/test-batch-persist.db";

    @BeforeEach
    public void setUp() {
        deleteTestFile();
    }

    @AfterEach
    public void tearDown() {
        deleteTestFile();
    }

    private void deleteTestFile() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            file.delete();
        }
    }
```

(c) 末尾追加用例与辅助方法：

```java
    // ========== 索引模式矩阵 ==========

    private void verifyBatchRoundtrip(RogueMap<String, String> map) {
        Map<String, String> batch = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            batch.put("key" + i, "value" + i);
        }
        map.putAll(batch);
        assertEquals(200, map.size());

        Map<String, String> got = map.getAll(batch.keySet());
        assertEquals(200, got.size());
        for (int i = 0; i < 200; i++) {
            assertEquals("value" + i, got.get("key" + i));
        }
    }

    @Test
    public void testBatchWithBasicIndex() {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .basicIndex()
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        try {
            verifyBatchRoundtrip(map);
        } finally {
            map.close();
        }
    }

    @Test
    public void testBatchWithLowHeapIndex() {
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .lowHeapIndex()
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        try {
            verifyBatchRoundtrip(map);
        } finally {
            map.close();
        }
    }

    @Test
    public void testBatchWithPrimitiveIndex() {
        RogueMap<Long, Long> map = RogueMap.<Long, Long>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .primitiveIndex()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();
        try {
            Map<Long, Long> batch = new HashMap<>();
            for (long i = 0; i < 200; i++) {
                batch.put(i, i * 10);
            }
            map.putAll(batch);
            assertEquals(200, map.size());

            Map<Long, Long> got = map.getAll(batch.keySet());
            assertEquals(200, got.size());
            assertEquals(Long.valueOf(50L), got.get(5L));
            assertEquals(Long.valueOf(1990L), got.get(199L));
        } finally {
            map.close();
        }
    }

    // ========== 持久化 ==========

    @Test
    public void testPutAllPersistence() {
        Map<String, String> batch = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            batch.put("pk" + i, "pv" + i);
        }

        RogueMap<String, String> map1 = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        map1.putAll(batch);
        map1.close();

        RogueMap<String, String> map2 = RogueMap.<String, String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
        try {
            assertEquals(300, map2.size());
            Map<String, String> got = map2.getAll(batch.keySet());
            assertEquals(300, got.size());
            assertEquals("pv123", got.get("pk123"));
        } finally {
            map2.close();
        }
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `mvn test -pl roguemap-core -Dtest=BatchOperationTest`
Expected: `Tests run: 15, Failures: 0, Errors: 0` → BUILD SUCCESS
（本任务是对已有实现的覆盖扩展，无红灯阶段；若有用例失败即发现真实缺陷，先修复实现再继续。）

- [ ] **Step 3: 提交**

```bash
git add roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java
git commit -m "test(core): 批量 API 索引模式矩阵与持久化测试

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 并发安全测试

**Files:**
- Test: `roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 4/5 的 `putAll`/`getAll`
- Produces: 无新接口（纯测试任务）

- [ ] **Step 1: 追加测试**

在 `BatchOperationTest.java` import 区加：

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
```

末尾追加：

```java
    // ========== 并发 ==========

    @Test
    public void testConcurrentPutAllAndGet() throws InterruptedException {
        RogueMap<String, String> map = newTempMap();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            int threads = 4;
            int rounds = 20;
            int keysPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        for (int round = 0; round < rounds; round++) {
                            Map<String, String> batch = new HashMap<>();
                            for (int i = 0; i < keysPerThread; i++) {
                                batch.put("t" + tid + "-k" + i, "v" + round + "-" + i);
                            }
                            map.putAll(batch);
                            // 写后立即读自己的键，验证读写交错安全
                            Map<String, String> got = map.getAll(batch.keySet());
                            if (got.size() != keysPerThread) {
                                throw new IllegalStateException(
                                        "线程 " + tid + " 第 " + round + " 轮读到 " + got.size() + " 条");
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(120, java.util.concurrent.TimeUnit.SECONDS), "并发测试超时");
            assertNull(error.get(), "并发执行出现异常: " + error.get());

            // 各线程键空间不相交，终态每线程各 keysPerThread 条
            assertEquals(threads * keysPerThread, map.size());
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < keysPerThread; i++) {
                    assertEquals("v" + (rounds - 1) + "-" + i, map.get("t" + t + "-k" + i));
                }
            }
        } finally {
            pool.shutdownNow();
            map.close();
        }
    }
```

- [ ] **Step 2: 运行测试确认通过（重复跑 3 次防偶发）**

Run: `for i in 1 2 3; do mvn test -pl roguemap-core -Dtest=BatchOperationTest#testConcurrentPutAllAndGet || break; done`
Expected: 3 次全部 BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add roguemap-core/src/test/java/com/yomahub/roguemap/map/BatchOperationTest.java
git commit -m "test(core): 批量 API 并发安全测试

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: 吞吐基准（非门禁）+ 全量回归

**Files:**
- Create: `roguemap-core/src/test/java/com/yomahub/roguemap/benchmark/BatchPutBenchmarkTest.java`

**Interfaces:**
- Consumes: Task 4 的 `putAll`
- Produces: 无新接口（基准输出到 stdout，仅断言数据正确性，不断言耗时——避免 CI 环境波动导致假失败）

- [ ] **Step 1: 写基准测试**

创建 `roguemap-core/src/test/java/com/yomahub/roguemap/benchmark/BatchPutBenchmarkTest.java`：

```java
package com.yomahub.roguemap.benchmark;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * putAll vs 循环 put 吞吐对比（非门禁：只校验数据正确性，不断言耗时）
 */
public class BatchPutBenchmarkTest {

    private static final int N = 100_000;

    private RogueMap<String, String> newMap() {
        return RogueMap.<String, String>mmap()
                .temporary()
                .allocateSize(64 * 1024 * 1024L)
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();
    }

    @Test
    public void compareLoopPutVsPutAll() {
        // 预生成数据，排除数据构造开销
        Map<String, String> data = new HashMap<>(N * 2);
        for (int i = 0; i < N; i++) {
            data.put("bench-key-" + i, "bench-value-" + i);
        }

        long loopNanos;
        RogueMap<String, String> map1 = newMap();
        try {
            long t0 = System.nanoTime();
            for (Map.Entry<String, String> e : data.entrySet()) {
                map1.put(e.getKey(), e.getValue());
            }
            loopNanos = System.nanoTime() - t0;
            assertEquals(N, map1.size());
        } finally {
            map1.close();
        }

        long batchNanos;
        RogueMap<String, String> map2 = newMap();
        try {
            long t0 = System.nanoTime();
            map2.putAll(data);
            batchNanos = System.nanoTime() - t0;
            assertEquals(N, map2.size());
        } finally {
            map2.close();
        }

        System.out.printf("[BatchPutBenchmark] %,d 条 | 循环 put: %,d ms | putAll: %,d ms | 加速比: %.2fx%n",
                N, loopNanos / 1_000_000, batchNanos / 1_000_000,
                (double) loopNanos / batchNanos);
    }
}
```

- [ ] **Step 2: 运行基准并记录输出**

Run: `mvn test -pl roguemap-core -Dtest=BatchPutBenchmarkTest`
Expected: BUILD SUCCESS，stdout 打印形如 `[BatchPutBenchmark] 100,000 条 | 循环 put: xxx ms | putAll: yyy ms | 加速比: z.zzx`。把实际输出记录到任务完成说明中。据最终实测（10 万条短字符串、分段索引、JIT 未充分预热），单线程加速比约 0.5–1.0x、6 线程并发约 0.65–0.8x——putAll 与循环 put 基本持平或略低（段锁竞争本就很低，锁摊薄收益不足以抵消 BatchEntry/entries/results 额外开销，瓶颈在 allocator CAS 与值编码）。加速比 < 1 不代表 putBatch 未生效，而是该负载下批量化本身不产生吞吐收益。

- [ ] **Step 3: 全量回归**

Run: `mvn test -pl roguemap-core`
Expected: 全部测试通过（现有 218+ 用例 + 本计划新增），BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add roguemap-core/src/test/java/com/yomahub/roguemap/benchmark/BatchPutBenchmarkTest.java
git commit -m "test(core): putAll 吞吐基准对比

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 完成标准

1. `mvn test -pl roguemap-core` 全绿；
2. 基准输出显示 putAll 与循环 put 吞吐相当（分段索引、10 万条场景）：据最终实测，单线程加速比约 0.5–1.0x、6 线程并发约 0.65–0.8x，即持平或略低（putAll 的价值在 API 便利性与 checkpoint 节流，而非原始吞吐）；
3. 8 个提交，每个提交独立可编译、可测试。
