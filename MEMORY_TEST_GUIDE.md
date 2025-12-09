# RogueMap 内存测试指南

## 概述

本指南介绍了 RogueMap 的内存测试工具，这些工具用于准确测量不同模式下的**堆内存占用**。

## 测试数据结构

### TestValueObject

为了更真实地模拟实际应用场景，我们创建了一个包含 10 个常见类型字段的测试对象：

```java
public class TestValueObject {
    private int id;                  // 1. int 类型 (4 字节)
    private long timestamp;          // 2. long 类型 (8 字节)
    private double price;            // 3. double 类型 (8 字节)
    private boolean active;          // 4. boolean 类型 (1 字节)
    private String name;             // 5. String 类型 (变长)
    private String description;      // 6. String 类型 (变长)
    private byte status;             // 7. byte 类型 (1 字节)
    private short quantity;          // 8. short 类型 (2 字节)
    private float rating;            // 9. float 类型 (4 字节)
    private char category;           // 10. char 类型 (2 字节)
}
```

### TestValueObjectCodec

实现了自定义编解码器，使用 `UnsafeOps` 直接操作内存，支持：
- 基本类型的直接读写
- 字符串的长度前缀编码
- 可变长度计算

## 测试工具

### 1. HeapMemoryComparisonTest

**目的**: 对比不同模式下的堆内存占用

**测试数据**: 100 万条记录

**测试模式**:
- HashMap 模式（基准）
- OffHeap 模式
- Mmap 临时文件模式
- Mmap 持久化模式

**运行方式**:
```bash
./run_heap_memory_test.sh
```

或者直接运行：
```bash
java -cp target/classes:target/test-classes \
    -Xms512m -Xmx2g \
    com.rogue.compare.HeapMemoryComparisonTest
```

**输出内容**:
- 各模式的堆内存使用量
- 堆外内存使用量（如果有）
- 相对于 HashMap 的内存节省百分比

### 2. DetailedMemoryAnalysisTest

**目的**: 提供详细的内存分析，包括堆内存各代的使用情况

**测试数据**: 100 万条记录

**测试模式**: 同上

**运行方式**:
```bash
./run_detailed_analysis_test.sh
```

或者直接运行：
```bash
java -cp target/classes:target/test-classes \
    -Xms512m -Xmx2g \
    com.rogue.compare.DetailedMemoryAnalysisTest
```

**输出内容**:
- 基准内存快照
- 创建并填充数据后的内存快照
- 内存增长详情：
  - Eden 区变化
  - Survivor 区变化
  - Old Gen 区变化
  - 非堆内存变化
  - 直接内存变化
- 清理后的内存状态

## 关键改进

### 1. 测试方法的改进

**原始测试的问题**:
- 测量的是测试期间的堆内存变化，包含了临时对象、JVM 内部缓冲区等
- 测量时机不准确，导致 Mmap 模式反而显示更高的内存占用
- 页缓存和文件映射的元数据被错误地统计为堆内存

**新测试的改进**:
- 建立清晰的基准线（测试前强制 GC）
- 在数据填充**完成后**测量（而不是在操作过程中）
- 多次强制 GC 确保测量准确
- 分离堆内存和堆外内存的统计
- 只测量数据结构本身的堆内存占用

### 2. 更真实的测试数据

使用包含 10 个不同类型字段的对象，而不是简单的 String：
- 包含基本类型（int, long, double, boolean, byte, short, float, char）
- 包含引用类型（String）
- 更接近实际应用场景

### 3. 准确的内存测量

```java
// 清理内存，建立基准
forceGC();
long baselineMemory = getCurrentHeapMemory();

// 创建并填充数据
RogueMap<Long, TestValueObject> map = ...;
// ... 填充数据 ...

// 强制 GC，获取稳定的内存使用量
forceGC();
long usedMemory = getCurrentHeapMemory();
long heapUsed = usedMemory - baselineMemory;
```

## 预期结果

使用正确的测量方法后，应该看到：

| 模式 | 堆内存占用 | 说明 |
|------|-----------|------|
| HashMap | 最高 | 所有数据存储在堆中 |
| OffHeap | 显著降低 | 只有索引和元数据在堆中，数据在堆外 |
| Mmap 临时文件 | 显著降低 | 只有索引和元数据在堆中，数据在文件映射内存中 |
| Mmap 持久化 | 显著降低 | 同上 |

这样才能真正体现 RogueMap 的设计初衷：**减少 Java GC 管理的堆内存占用**！

## 理论计算

对于 100 万条 TestValueObject 记录：

### HashMap 模式
- Key (Long): 8 字节 × 100万 = 8 MB
- Value 对象: ~130 字节 × 100万 ≈ 124 MB
- HashMap 开销: ~32 字节/条 × 100万 ≈ 30 MB
- **预计总计**: ~162 MB（实际会更高，因为 Java 对象头开销）

### OffHeap/Mmap 模式
- 堆内索引结构: ~20-30 MB（索引 + 元数据）
- 堆外数据: ~130 MB（实际数据在堆外或文件中）
- **预计堆内存**: ~20-30 MB（相比 HashMap 节省 80-85%）

## 注意事项

1. **GC 的影响**: 测试前后都进行多次 GC，确保测量准确
2. **JVM 参数**: 使用合适的堆大小（-Xms512m -Xmx2g）
3. **预热**: 可以考虑添加预热轮次，让 JIT 编译优化生效
4. **数据量**: 数据量越大，OffHeap/Mmap 模式的优势越明显

## 文件清单

- `TestValueObject.java` - 测试用的值对象
- `TestValueObjectCodec.java` - 值对象的编解码器
- `HeapMemoryComparisonTest.java` - 堆内存对比测试
- `DetailedMemoryAnalysisTest.java` - 详细内存分析测试
- `run_heap_memory_test.sh` - 运行堆内存对比测试的脚本
- `run_detailed_analysis_test.sh` - 运行详细内存分析测试的脚本
- `PerformanceComparisonTest.java` - 性能对比测试（已改进）

## 总结

通过这些改进的测试工具，我们现在可以：
1. ✅ 准确测量堆内存占用
2. ✅ 区分堆内存和堆外内存
3. ✅ 验证 RogueMap 的设计目标（减少 GC 压力）
4. ✅ 使用更真实的测试数据
5. ✅ 提供详细的内存分析报告
