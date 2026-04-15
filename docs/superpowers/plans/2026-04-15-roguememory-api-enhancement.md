# RogueMemory API Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RogueMemory 新增 `add(id,...)`、`exists`、`delete(id,ns)`、`deleteByNamespace`、`update(id,ns,content)` 五类 API，两个内存模块同步变更。

**Architecture:** 所有新方法都在 `RogueMemory.java` 中实现，复杂方法通过委托调用现有私有逻辑（`delete(id)`、`update(id,content)`）避免重复代码。`roguemap-memory` 先实现并测试，然后逐字同步到 `roguemap-memory-pro`（两个文件除向量索引类名外完全相同）。

**Tech Stack:** Java 8, JUnit 5, Maven, sun.misc.Unsafe（已有），无新增依赖

---

## 文件变更总览

| 动作 | 文件 |
|------|------|
| 修改 | `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` |
| 新建 | `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java` |
| 修改 | `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` |

---

## Task 1：实现 `exists(String id)` 和 `exists(String id, String namespace)`

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`（在 `delete(String id)` 方法上方新增）
- Create: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java`

- [ ] **Step 1：写失败测试**

新建文件 `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java`：

```java
package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RogueMemoryApiEnhancementTest {

    private static final String TEST_DIR = "target/test-memory-api-enhancement";
    private RogueMemory memory;

    @BeforeEach
    void setUp() {
        deleteDir(new File(TEST_DIR));
        new File(TEST_DIR).mkdirs();
        memory = RogueMemory.mmap()
            .persistent(TEST_DIR + "/mem")
            .embeddingProvider(new MockEmbeddingProvider(4))
            .searchMode(SearchMode.HYBRID)
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        memory.close();
    }

    // ===== exists =====

    @Test
    void existsReturnsFalseForUnknownId() {
        assertFalse(memory.exists("nonexistent-id"));
    }

    @Test
    void existsReturnsTrueAfterAdd() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        assertTrue(memory.exists(id));
    }

    @Test
    void existsReturnsFalseAfterDelete() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        memory.delete(id);
        assertFalse(memory.exists(id));
    }

    @Test
    void existsWithNamespaceReturnsTrueWhenMatches() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        assertTrue(memory.exists(id, "ns1"));
    }

    @Test
    void existsWithNamespaceReturnsFalseWhenNamespaceMismatches() {
        String id = memory.add("hello world", Collections.emptyMap(), "ns1");
        assertFalse(memory.exists(id, "ns2"));
    }

    @Test
    void existsWithNamespaceReturnsFalseForUnknownId() {
        assertFalse(memory.exists("nonexistent-id", "ns1"));
    }

    // ===== helper =====

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
```

- [ ] **Step 2：运行测试，确认编译失败（方法不存在）**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：编译错误，`cannot find symbol: method exists(String)`

- [ ] **Step 3：在 `RogueMemory.java` 的公开 API 区实现两个 `exists` 方法**

在 `delete(String id)` 方法上方插入：

```java
public boolean exists(String id) {
    checkOpen();
    int ordinal = ordinalRegistry.getOrdinal(id);
    if (ordinal == -1) return false;
    return ordinal < offsetTable.length && offsetTable[ordinal] != 0;
}

public boolean exists(String id, String namespace) {
    checkOpen();
    if (!exists(id)) return false;
    int ordinal = ordinalRegistry.getOrdinal(id);
    MemoryEntry entry = readRecord(offsetTable[ordinal]);
    return entry != null && namespace.equals(entry.getNamespace());
}
```

- [ ] **Step 4：运行测试，确认全部通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：`Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5：提交**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java
git commit -m "feat(memory): add exists(id) and exists(id, namespace) methods"
```

---

## Task 2：实现 `add(String id, String content, Map<String, String> metadata, String namespace)`

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java`

- [ ] **Step 1：追加失败测试**

在 `RogueMemoryApiEnhancementTest.java` 的 `// ===== exists =====` 区后面追加：

```java
    // ===== add with external id =====

    @Test
    void addWithExternalIdStoresEntry() {
        String customId = "my-custom-id-001";
        String returned = memory.add(customId, "外部指定id的内容",
            Collections.singletonMap("source", "llm"), "agent-ns");
        assertEquals(customId, returned);

        MemoryEntry entry = memory.get(customId);
        assertNotNull(entry);
        assertEquals("外部指定id的内容", entry.getContent());
        assertEquals("agent-ns", entry.getNamespace());
        assertEquals("llm", entry.getMetadata().get("source"));
    }

    @Test
    void addWithDuplicateIdThrowsIllegalArgumentException() {
        String customId = "dup-id-001";
        memory.add(customId, "第一次", Collections.emptyMap(), "ns1");
        assertThrows(IllegalArgumentException.class, () ->
            memory.add(customId, "第二次", Collections.emptyMap(), "ns1"));
    }

    @Test
    void addWithExternalIdIsSearchable() {
        String customId = "search-id-001";
        memory.add(customId, "人工智能记忆", Collections.emptyMap(), "ns1");
        List<MemoryResult> results = memory.search("人工智能", 5);
        assertFalse(results.isEmpty());
        assertEquals(customId, results.get(0).getId());
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：编译错误，`cannot find symbol: method add(String, String, Map, String)`

- [ ] **Step 3：在 `RogueMemory.java` 实现三参数 `add` 之后紧接着插入新方法**

在现有 `add(String content, Map<String, String> metadata, String namespace)` 方法结束的 `}` 之后插入：

```java
public String add(String id, String content, Map<String, String> metadata, String namespace) {
    checkOpen();
    if (id == null || id.isEmpty()) throw new IllegalArgumentException("id must not be null or empty");
    if (ordinalRegistry.getOrdinal(id) != -1) {
        throw new IllegalArgumentException("id already exists: " + id);
    }
    int ordinal = ordinalRegistry.register(id);
    growTablesIfNeeded(ordinal);

    long createdAt = System.currentTimeMillis();
    float[] vector = null;
    if (searchMode != SearchMode.KEYWORD_ONLY && embeddingProvider != null) {
        vector = embeddingProvider.embed(content);
    }

    long recordOffset = writeRecordToAllocator(allocator, id, content, metadata, namespace,
            createdAt, 0L, vector, false);
    offsetTable[ordinal] = allocator.getFileOffsetForAddress(recordOffset);

    if (vector != null) {
        vectorOffsetTable[ordinal] = computeVectorOffset(recordOffset, namespace, content, metadata);
    }

    bm25Index.addDocument(ordinal, content);
    if (vectorIndex != null && vector != null) {
        vectorIndex.add(ordinal, vector);
    }
    if (autoCheckpointManager != null) autoCheckpointManager.onWriteOperation();
    return id;
}
```

- [ ] **Step 4：运行测试，确认全部通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：`Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5：提交**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java
git commit -m "feat(memory): add add(id, content, metadata, namespace) with duplicate-id guard"
```

---

## Task 3：实现 `delete(String id, String namespace)`

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java`

- [ ] **Step 1：追加失败测试**

```java
    // ===== delete(id, namespace) =====

    @Test
    void deleteWithMatchingNamespaceRemovesEntry() {
        String id = memory.add("待删除", Collections.emptyMap(), "target-ns");
        memory.delete(id, "target-ns");
        assertNull(memory.get(id));
        assertFalse(memory.exists(id));
    }

    @Test
    void deleteWithMismatchedNamespaceIgnores() {
        String id = memory.add("不应被删除", Collections.emptyMap(), "target-ns");
        memory.delete(id, "wrong-ns");
        assertNotNull(memory.get(id));   // 仍然存在
        assertTrue(memory.exists(id));
    }

    @Test
    void deleteWithNamespaceOnUnknownIdIsNoOp() {
        // 不应抛异常
        assertDoesNotThrow(() -> memory.delete("nonexistent", "any-ns"));
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：编译错误，`cannot find symbol: method delete(String, String)`

- [ ] **Step 3：在 `RogueMemory.java` 现有 `delete(String id)` 方法下方插入**

```java
public void delete(String id, String namespace) {
    checkOpen();
    int ordinal = ordinalRegistry.getOrdinal(id);
    if (ordinal == -1) return;
    if (ordinal >= offsetTable.length || offsetTable[ordinal] == 0) return;
    MemoryEntry entry = readRecord(offsetTable[ordinal]);
    if (entry == null || !namespace.equals(entry.getNamespace())) return;
    delete(id);
}
```

- [ ] **Step 4：运行测试，确认全部通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：`Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 5：提交**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java
git commit -m "feat(memory): add delete(id, namespace) with namespace guard"
```

---

## Task 4：实现 `update(String id, String namespace, String newContent)`

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java`

- [ ] **Step 1：追加失败测试**

```java
    // ===== update(id, namespace, newContent) =====

    @Test
    void updateWithMatchingNamespaceUpdatesContent() {
        String id = memory.add("原始内容", Collections.emptyMap(), "update-ns");
        memory.update(id, "update-ns", "新内容");
        MemoryEntry entry = memory.get(id);
        assertNotNull(entry);
        assertEquals("新内容", entry.getContent());
        assertEquals("update-ns", entry.getNamespace());   // namespace 不变
    }

    @Test
    void updateWithMismatchedNamespaceIgnores() {
        String id = memory.add("原始内容", Collections.emptyMap(), "update-ns");
        memory.update(id, "wrong-ns", "新内容");
        MemoryEntry entry = memory.get(id);
        assertNotNull(entry);
        assertEquals("原始内容", entry.getContent());      // 内容未变
    }

    @Test
    void updateWithNamespaceOnUnknownIdIsNoOp() {
        assertDoesNotThrow(() -> memory.update("nonexistent", "any-ns", "新内容"));
    }

    @Test
    void updateWithNamespacePreservesMetadata() {
        Map<String, String> meta = Collections.singletonMap("key", "value");
        String id = memory.add("原始内容", meta, "meta-ns");
        memory.update(id, "meta-ns", "更新内容");
        MemoryEntry entry = memory.get(id);
        assertEquals("value", entry.getMetadata().get("key"));  // metadata 保留
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：编译错误，`cannot find symbol: method update(String, String, String)`

- [ ] **Step 3：在 `RogueMemory.java` 现有 `update(String id, String newContent)` 方法下方插入**

```java
public void update(String id, String namespace, String newContent) {
    checkOpen();
    int ordinal = ordinalRegistry.getOrdinal(id);
    if (ordinal == -1) return;
    if (ordinal >= offsetTable.length || offsetTable[ordinal] == 0) return;
    MemoryEntry entry = readRecord(offsetTable[ordinal]);
    if (entry == null || !namespace.equals(entry.getNamespace())) return;
    update(id, newContent);
}
```

- [ ] **Step 4：运行测试，确认全部通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：`Tests run: 16, Failures: 0, Errors: 0`

- [ ] **Step 5：提交**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java
git commit -m "feat(memory): add update(id, namespace, newContent) with namespace guard"
```

---

## Task 5：实现 `deleteByNamespace(String namespace)`

**Files:**
- Modify: `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`
- Modify: `roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java`

- [ ] **Step 1：追加失败测试**

```java
    // ===== deleteByNamespace =====

    @Test
    void deleteByNamespaceRemovesAllInNamespace() {
        String id1 = memory.add("内容1", Collections.emptyMap(), "del-ns");
        String id2 = memory.add("内容2", Collections.emptyMap(), "del-ns");
        String id3 = memory.add("内容3", Collections.emptyMap(), "other-ns");

        memory.deleteByNamespace("del-ns");

        assertNull(memory.get(id1));
        assertNull(memory.get(id2));
        assertNotNull(memory.get(id3));   // other-ns 不受影响
    }

    @Test
    void deleteByNamespaceOnEmptyNamespaceIsNoOp() {
        String id = memory.add("内容", Collections.emptyMap(), "keep-ns");
        memory.deleteByNamespace("nonexistent-ns");
        assertNotNull(memory.get(id));    // 未受影响
    }

    @Test
    void deleteByNamespaceAllowsReaddWithSameId() {
        String customId = "reuse-id";
        memory.add(customId, "旧内容", Collections.emptyMap(), "batch-ns");
        memory.deleteByNamespace("batch-ns");
        // 删除后可以重新用同一个 id 添加（ordinal 已释放）
        assertFalse(memory.exists(customId));
        String returned = memory.add(customId, "新内容", Collections.emptyMap(), "batch-ns");
        assertEquals(customId, returned);
        assertTrue(memory.exists(customId));
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：编译错误，`cannot find symbol: method deleteByNamespace(String)`

- [ ] **Step 3：在 `RogueMemory.java` `delete(String id, String namespace)` 方法下方插入**

```java
public void deleteByNamespace(String namespace) {
    checkOpen();
    int cap = ordinalRegistry.capacity();
    List<String> toDelete = new ArrayList<>();
    for (int i = 0; i < cap; i++) {
        if (i >= offsetTable.length || offsetTable[i] == 0) continue;
        String id = ordinalRegistry.getId(i);
        if (id == null) continue;
        MemoryEntry entry = readRecord(offsetTable[i]);
        if (entry != null && namespace.equals(entry.getNamespace())) {
            toDelete.add(id);
        }
    }
    for (String id : toDelete) {
        delete(id);
    }
}
```

- [ ] **Step 4：运行测试，确认全部通过**

```bash
mvn test -pl roguemap-memory -Dtest=RogueMemoryApiEnhancementTest 2>&1 | tail -20
```

期望：`Tests run: 19, Failures: 0, Errors: 0`

- [ ] **Step 5：确认所有现有测试仍然通过**

```bash
mvn test -pl roguemap-memory 2>&1 | tail -10
```

期望：`BUILD SUCCESS`，无失败测试

- [ ] **Step 6：提交**

```bash
git add roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java \
        roguemap-memory/src/test/java/com/yomahub/roguemap/memory/RogueMemoryApiEnhancementTest.java
git commit -m "feat(memory): add deleteByNamespace(namespace) for bulk removal"
```

---

## Task 6：同步到 `roguemap-memory-pro`

**Files:**
- Modify: `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java`

`roguemap-memory-pro` 与 `roguemap-memory` 的 `RogueMemory.java` 结构完全相同，唯一区别是向量索引类型（`JVectorIndex` 而非 `HnswVectorIndex`）。需将 Task 1-5 中添加的所有方法逐字复制过去。

- [ ] **Step 1：打开 pro 模块的 RogueMemory.java，找到对应插入位置**

在 `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` 中：
- 在 `delete(String id)` 上方插入 `exists(String id)` 和 `exists(String id, String namespace)`
- 在现有 `add(String content, Map, String)` 方法后插入 `add(String id, String content, Map, String)`
- 在现有 `delete(String id)` 方法后插入 `delete(String id, String namespace)` 和 `deleteByNamespace(String namespace)`
- 在现有 `update(String id, String newContent)` 方法后插入 `update(String id, String namespace, String newContent)`

所有方法体与 `roguemap-memory` 模块完全相同（不涉及向量索引类名，无需修改）。

- [ ] **Step 2：编译 pro 模块确认无错误**

```bash
mvn compile -pl roguemap-memory-pro 2>&1 | tail -10
```

期望：`BUILD SUCCESS`

- [ ] **Step 3：运行 pro 模块的所有测试**

```bash
mvn test -pl roguemap-memory-pro 2>&1 | tail -10
```

期望：`BUILD SUCCESS`，无失败

- [ ] **Step 4：提交**

```bash
git add roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java
git commit -m "feat(memory-pro): sync API enhancement from roguemap-memory"
```

---

## Task 7：全量回归验证

- [ ] **Step 1：运行全项目测试**

```bash
mvn test 2>&1 | tail -15
```

期望：`BUILD SUCCESS`，所有模块 0 failures, 0 errors

- [ ] **Step 2：提交（若有任何遗漏改动）**

若 Step 1 全部通过且无未提交文件，无需额外 commit。否则：

```bash
git add -A
git commit -m "fix(memory): address any regression from API enhancement"
```
