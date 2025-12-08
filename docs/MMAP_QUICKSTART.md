# RogueMap MMAP 模式快速开始

## 5 分钟上手指南

### 1. 基本使用

```java
import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;

// 创建 MMAP 模式的 RogueMap
RogueMap<String, String> map = RogueMap.<String, String>builder()
    .persistent("data.db")                    // 持久化文件路径
    .mmap()                                    // 启用 MMAP 模式
    .allocateSize(100 * 1024 * 1024L)         // 预分配 100MB
    .keyCodec(new StringCodec())
    .valueCodec(new StringCodec())
    .build();

try {
    // 写入数据
    map.put("user:1001", "Alice");
    map.put("user:1002", "Bob");

    // 读取数据
    String user = map.get("user:1001");
    System.out.println(user);  // 输出: Alice

    // 刷新到磁盘
    map.flush();
} finally {
    map.close();
}
```

### 2. 与堆外内存模式对比

#### 堆外内存模式（默认）
```java
// 数据不持久化，进程重启后丢失
RogueMap<String, String> offHeapMap = RogueMap.<String, String>builder()
    .offHeap()                               // 堆外内存模式
    .maxMemory(1024L * 1024 * 1024)         // 1GB
    .keyCodec(new StringCodec())
    .valueCodec(new StringCodec())
    .build();
```

#### MMAP 模式
```java
// 数据持久化到文件
RogueMap<String, String> mmapMap = RogueMap.<String, String>builder()
    .persistent("data.db")                   // MMAP 模式
    .mmap()
    .allocateSize(1024L * 1024 * 1024)      // 1GB
    .keyCodec(new StringCodec())
    .valueCodec(new StringCodec())
    .build();
```

### 3. 使用原始类型（高性能）

```java
import com.yomahub.roguemap.serialization.PrimitiveCodecs;

RogueMap<Long, Long> idMap = RogueMap.<Long, Long>builder()
    .persistent("id-mapping.db")
    .mmap()
    .allocateSize(200 * 1024 * 1024L)
    .keyCodec(PrimitiveCodecs.LONG)          // Long 类型编解码器
    .valueCodec(PrimitiveCodecs.LONG)
    .build();

// 写入 100 万条数据
for (long i = 0; i < 1000000; i++) {
    idMap.put(i, i * 1000);
}
```

### 4. 完整示例

```java
package com.example;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.serialization.StringCodec;

public class MmapQuickStart {
    public static void main(String[] args) {
        // 创建持久化的 RogueMap
        RogueMap<String, String> userCache = RogueMap.<String, String>builder()
                .persistent("user-cache.db")
                .mmap()
                .allocateSize(500 * 1024 * 1024L)  // 500MB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        try {
            // 写入用户数据
            userCache.put("user:1", "{\"name\":\"Alice\",\"age\":25}");
            userCache.put("user:2", "{\"name\":\"Bob\",\"age\":30}");
            userCache.put("user:3", "{\"name\":\"Charlie\",\"age\":35}");

            // 查询用户
            String user1 = userCache.get("user:1");
            System.out.println("User 1: " + user1);

            // 检查是否存在
            boolean exists = userCache.containsKey("user:2");
            System.out.println("User 2 exists: " + exists);

            // 删除用户
            String removed = userCache.remove("user:3");
            System.out.println("Removed: " + removed);

            // 获取总数
            System.out.println("Total users: " + userCache.size());

            // 刷新到磁盘
            userCache.flush();
            System.out.println("Data flushed to disk");

        } finally {
            userCache.close();
        }
    }
}
```

### 5. 配置选项

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `persistent(String)` | 设置持久化文件路径 | 必须 |
| `mmap()` | 启用 MMAP 模式 | 自动 |
| `allocateSize(long)` | 预分配文件大小（字节） | 10GB |
| `keyCodec(Codec)` | 键编解码器 | 必须 |
| `valueCodec(Codec)` | 值编解码器 | 必须 |

### 6. 可用的编解码器

```java
// 原始类型
PrimitiveCodecs.BYTE
PrimitiveCodecs.SHORT
PrimitiveCodecs.INTEGER
PrimitiveCodecs.LONG
PrimitiveCodecs.FLOAT
PrimitiveCodecs.DOUBLE

// 字符串
new StringCodec()

// 对象（使用 Kryo）
new KryoObjectCodec<>(UserData.class)
```

### 7. 性能提示

1. **预分配合适的文件大小**
   ```java
   // 根据预期数据量设置
   .allocateSize(expectedDataSize * 2)  // 留一些余量
   ```

2. **使用原始类型优化**
   ```java
   // Long 类型比 String 快得多
   .keyCodec(PrimitiveCodecs.LONG)
   ```

3. **批量操作后刷盘**
   ```java
   // 批量写入
   for (int i = 0; i < 10000; i++) {
       map.put(key, value);
   }
   // 统一刷盘
   map.flush();
   ```

### 8. 注意事项

⚠️ **重要提醒**:

1. **文件大小**: `allocateSize()` 会立即占用磁盘空间，请根据实际需求设置
2. **关闭资源**: 务必在 `finally` 块中调用 `map.close()`
3. **大文件**: 单个文件超过 2GB 会自动分段（对用户透明）
4. **数据恢复**: 当前版本支持文件持久化，但崩溃恢复需要 WAL（后续版本）

### 9. 运行测试

```bash
# 运行所有测试
mvn test

# 运行 MMAP 测试
mvn test -Dtest=MmapTest

# 运行示例
mvn test-compile exec:java \
  -Dexec.mainClass="com.yomahub.roguemap.MmapExample" \
  -Dexec.classpathScope=test
```

### 10. 下一步

- 查看 [MMAP_IMPLEMENTATION.md](MMAP_IMPLEMENTATION.md) 了解实现细节
- 查看 [DESIGN_PLAN_V2.md](DESIGN_PLAN_V2.md) 了解整体架构
- 查看测试代码 `MmapTest.java` 了解更多用法
- 查看示例代码 `MmapExample.java` 了解性能测试

## 快速对比

| 场景 | 推荐模式 |
|------|---------|
| 临时缓存 | 堆外内存 (OffHeap) |
| 持久化存储 | MMAP |
| 超大数据集 (>100GB) | MMAP |
| 需要数据恢复 | MMAP |
| 极致性能 | 堆外内存 |

现在开始使用 RogueMap MMAP 模式吧！🚀
