# 迁移到 KryoObjectCodec

## 修改日期
2025-12-09

## 修改内容

### 1. 删除自定义 Codec
- ✅ 删除 `TestValueObjectCodec.java` - 不再需要手动编写序列化代码

### 2. 使用 KryoObjectCodec
- ✅ 在 `HeapMemoryComparisonTestFixed.java` 中使用 `KryoObjectCodec.create(TestValueObject.class)`
- ✅ 替换所有 `TestValueObjectCodec.INSTANCE` 引用

## 优势

### 使用 KryoObjectCodec 的好处：

1. **无需手动编写序列化代码**
   - 自定义 Codec：需要手动实现 encode/decode/calculateSize 方法（~180行代码）
   - KryoObjectCodec：一行代码搞定 `KryoObjectCodec.create(TestValueObject.class)`

2. **自动处理复杂对象**
   - 支持嵌套对象、集合、数组等复杂数据结构
   - 自动处理对象图中的循环引用
   - 支持继承和多态

3. **高性能**
   - Kryo 是业界最快的 Java 序列化框架之一
   - 使用线程本地缓存避免重复序列化
   - 预分配缓冲区减少内存分配

4. **通用性**
   - 可用于任何实现 `Serializable` 的 Java 类
   - 不需要为每个业务对象写专用 Codec

## 代码示例

### 之前（自定义 Codec）
```java
// 需要实现 ~180 行代码
public class TestValueObjectCodec implements Codec<TestValueObject> {
    @Override
    public int encode(long address, TestValueObject value) {
        // 手动编码每个字段...
        UnsafeOps.putInt(offset, value.getId());
        offset += 4;
        UnsafeOps.putLong(offset, value.getTimestamp());
        offset += 8;
        // ... 更多字段
    }

    @Override
    public TestValueObject decode(long address) {
        // 手动解码每个字段...
        int id = UnsafeOps.getInt(offset);
        offset += 4;
        // ... 更多字段
    }
}

// 使用
.valueCodec(TestValueObjectCodec.INSTANCE)
```

### 现在（KryoObjectCodec）
```java
// 一行代码搞定！
.valueCodec(KryoObjectCodec.create(TestValueObject.class))
```

## 测试结果

运行 `HeapMemoryComparisonTestFixed` 的结果：

```
=== RogueMap 堆内存对比测试（修正版）===
数据集大小: 1,000,000 条记录

模式                  堆内存(MB)    相对堆内存占用
----------------------------------------------------
HashMap模式           304.33        100.0%
OffHeap模式            40.16         13.2%    ← 节省 86.8%
Mmap临时文件模式       40.05         13.2%    ← 节省 86.8%
Mmap持久化模式         40.01         13.1%    ← 节省 86.9%
```

**关键发现：**
- HashMap：304 MB 堆内存（全部数据在堆上）
- RogueMap：~40 MB 堆内存（仅索引在堆上，数据在堆外）
- **堆内存节省：86.8%**
- **GC 压力显著降低**

## 何时使用 KryoObjectCodec vs 自定义 Codec

### 使用 KryoObjectCodec（推荐）
✅ 快速开发和原型设计
✅ 复杂对象结构
✅ 不需要极致性能优化
✅ 对象结构可能变化

### 使用自定义 Codec
✅ 追求极致性能（减少序列化开销）
✅ 需要精确控制序列化格式
✅ 需要跨语言兼容
✅ 固定的对象结构

## 性能对比

| 方面 | KryoObjectCodec | 自定义 Codec |
|------|----------------|-------------|
| 开发成本 | 极低（1行代码） | 高（~200行代码/类） |
| 序列化速度 | 很快 | 最快 |
| 序列化大小 | 稍大 | 最小 |
| 维护成本 | 低 | 高 |
| 通用性 | 高 | 低 |

对于大多数场景，**KryoObjectCodec 的便利性远超其微小的性能差异**。

## 相关文件

- [HeapMemoryComparisonTestFixed.java](src/test/java/com/rogue/compare/HeapMemoryComparisonTestFixed.java)
- [KryoObjectCodec.java](src/main/java/com/yomahub/roguemap/serialization/KryoObjectCodec.java)
- [TestValueObject.java](src/test/java/com/rogue/compare/TestValueObject.java)
