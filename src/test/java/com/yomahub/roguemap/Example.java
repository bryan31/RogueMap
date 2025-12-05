package com.yomahub.roguemap;

import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;

/**
 * RogueMap 使用示例
 */
public class Example {

    public static void main(String[] args) {
        basicUsageExample();
        primitiveIndexExample();
    }

    /**
     * 基本用法示例
     */
    private static void basicUsageExample() {
        System.out.println("=== 基本用法示例 ===\n");

        RogueMap<String, Long> userScores = RogueMap.<String, Long>builder()
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(PrimitiveCodecs.LONG)
                .maxMemory(100 * 1024 * 1024) // 100MB 堆外内存
                .build();

        // 插入数据
        userScores.put("Alice", 1000L);
        userScores.put("Bob", 1500L);
        userScores.put("Charlie", 2000L);

        // 读取数据
        System.out.println("Alice's score: " + userScores.get("Alice"));
        System.out.println("Bob's score: " + userScores.get("Bob"));

        // 更新数据
        userScores.put("Alice", 1200L);
        System.out.println("Alice's updated score: " + userScores.get("Alice"));

        // 检查键是否存在
        System.out.println("Contains 'Alice': " + userScores.containsKey("Alice"));
        System.out.println("Contains 'David': " + userScores.containsKey("David"));

        // 获取大小
        System.out.println("Map size: " + userScores.size());

        // 删除数据
        Long removed = userScores.remove("Bob");
        System.out.println("Removed Bob's score: " + removed);
        System.out.println("Map size after removal: " + userScores.size());

        System.out.println();
    }

    /**
     * 原始类型索引示例 - 极致内存优化
     */
    private static void primitiveIndexExample() {
        System.out.println("=== 原始类型索引示例（内存优化） ===\n");

        // 使用 Long 键的原始类型索引
        RogueMap<Long, String> userMap = RogueMap.<Long, String>builder()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(StringCodec.INSTANCE)
                .maxMemory(100 * 1024 * 1024)  // 100MB 堆外内存
                .primitiveIndex()              // 使用原始类型索引，节省80%堆内存
                .initialCapacity(10_000)       // 预分配容量
                .build();

        System.out.println("插入 10,000 条用户数据...");
        long startTime = System.currentTimeMillis();

        // 注意：LongPrimitiveIndex 不支持 0 和 Long.MIN_VALUE 作为键（用作内部标记）
        for (long i = 1; i <= 10_000; i++) {
            userMap.put(i, "User_" + i);
        }

        long insertTime = System.currentTimeMillis() - startTime;
        System.out.println("插入完成，耗时: " + insertTime + " ms");
        System.out.println("Map size: " + userMap.size());

        // 随机读取测试
        startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            long randomKey = (long) (Math.random() * 10_000) + 1;  // 1-10000
            userMap.get(randomKey);
        }
        long readTime = System.currentTimeMillis() - startTime;
        System.out.println("随机读取1000次，耗时: " + readTime + " ms");

        System.out.println("\n原始类型索引优势：");
        System.out.println("- 内存占用比 HashMap 减少 80% 以上");
        System.out.println("- 更好的缓存局部性，性能提升 10-20%");
        System.out.println("- 适用于 Long 或 Integer 键的场景");
    }
}
