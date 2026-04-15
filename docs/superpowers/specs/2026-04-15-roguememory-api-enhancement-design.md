# RogueMemory API Enhancement Design

**Date:** 2026-04-15  
**Status:** Approved  
**Scope:** `roguemap-memory` 和 `roguemap-memory-pro` 两个模块同步变更

---

## 背景

RogueMemory 当前 API 在以下场景存在缺口：

1. LLM 应用中需要由外部（LLM）指定 key（id），而非系统自动生成
2. `update` 缺乏 namespace 维度的安全校验
3. `delete` 缺乏 namespace 维度的安全校验，也缺少按 namespace 批量删除能力
4. 没有 `exists` 检查，调用方无法判断"是否需要先 add 再 update"

---

## API 变更详细说明

### 1. `add` — 支持外部指定 id

```java
// 现有（不变）
String add(String content)
String add(String content, Map<String, String> metadata, String namespace)

// 新增
String add(String id, String content, Map<String, String> metadata, String namespace)
```

**行为：**
- `id` 参数由调用方提供，格式为任意非空字符串（通常是 UUID 字符串）
- 若 `id` 已存在，抛出 `IllegalArgumentException`（快速失败，不做静默覆盖）
- 返回值仍为传入的 `id`（保持与现有重载返回签名一致）

**内部实现：**
- 在 `ordinalRegistry.register(id)` 之前，先检查 `ordinalRegistry.getOrdinal(id) != -1`，若存在则抛异常

---

### 2. `update` — 新增带 namespace 安全校验的重载

```java
// 现有（不变）
void update(String id, String newContent)

// 新增
void update(String id, String namespace, String newContent)
```

**行为：**
- 读取旧记录的 namespace，与传入 `namespace` 对比
- 若不一致，直接 return（静默忽略），不抛异常
- 若一致，执行与原有 `update(id, newContent)` 相同的逻辑（保留旧 metadata，重新 embed，写新记录）

**设计理由：** 与 `delete(id, namespace)` 语义对称；namespace 在此作为安全屏障，防止跨 namespace 误操作。

---

### 3. `delete` — 新增两个重载

```java
// 现有（不变）
void delete(String id)

// 新增：带 namespace 安全校验
void delete(String id, String namespace)

// 新增：按 namespace 批量删除
void deleteByNamespace(String namespace)
```

**`delete(String id, String namespace)` 行为：**
- 读取记录的实际 namespace，与传入值对比
- 若不一致，静默忽略
- 若一致，执行与 `delete(id)` 完全相同的删除逻辑

**`deleteByNamespace(String namespace)` 行为：**
- 遍历所有 ordinal，对每条未删除的记录读取其 namespace
- namespace 匹配则执行删除（标记 deleted 字节、从 bm25Index/vectorIndex 移除、释放 ordinal）
- 注意：遍历中修改 offsetTable，需按 ordinal 顺序处理，不存在并发问题（单线程遍历）

---

### 4. `exists` — 全新方法

```java
boolean exists(String id)
boolean exists(String id, String namespace)
```

**`exists(String id)` 行为：**
- `ordinalRegistry.getOrdinal(id) != -1` 且 `offsetTable[ordinal] != 0` 则返回 `true`
- 即：注册过且未被删除

**`exists(String id, String namespace)` 行为：**
- 先通过 `exists(id)` 检查存在性
- 存在时再读取记录的 namespace 与传入值对比，两者一致才返回 `true`

---

## 变更范围

| 文件 | 变更类型 |
|------|----------|
| `roguemap-memory/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` | 新增方法 |
| `roguemap-memory-pro/src/main/java/com/yomahub/roguemap/memory/RogueMemory.java` | 同步新增方法 |
| `roguemap-memory/src/test/java/...` | 新增测试 |
| `roguemap-memory-pro/src/test/java/...` | 新增测试（可选，结构一致） |

两个模块的 `RogueMemory.java` 结构完全相同（除向量索引后端），所有变更需同步到两个文件。

---

## 测试要点

- `add(id, ...)` 成功 + 重复 id 抛 `IllegalArgumentException`
- `update(id, namespace, content)` namespace 匹配时更新，不匹配时忽略
- `delete(id, namespace)` namespace 匹配时删除，不匹配时忽略
- `deleteByNamespace(ns)` 删除指定 namespace 的所有记录，其他 namespace 不受影响
- `exists(id)` add 后为 true，delete 后为 false
- `exists(id, namespace)` id 存在但 namespace 不匹配时为 false

---

## 不在本次范围内

- `store`（upsert）方法：用户明确只要 `exists`，由调用方自行组合判断逻辑
- metadata 的独立更新（如 `update(id, metadata)`）：不在本次需求范围内
- namespace 重命名：不涉及
