package com.yomahub.roguemap;

import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;

/**
 * MMAP 模式使用示例
 */
public class MmapExample {

    public static void main(String[] args) {
        System.out.println("=== RogueMap MMAP 模式示例 ===\n");

        /*// 示例 1: 基本使用
        example1BasicUsage();

        // 示例 2: 大数据量场景
        example2LargeDataSet();

        // 示例 3: 原始类型优化
        example3PrimitiveTypes();*/

        comparePerformance();
    }

    /**
     * 示例 1: 基本使用
     */
    private static void example1BasicUsage() {
        System.out.println("【示例 1】基本使用");
        System.out.println("创建 MMAP 模式的 RogueMap，数据持久化到文件");

        // 创建 MMAP 模式的 RogueMap
        RogueMap<String, String> map = RogueMap.<String, String>builder()
                .persistent("data.db")                    // 指定持久化文件路径
                .mmap()                                    // 启用 MMAP 模式
                .allocateSize(100 * 1024 * 1024L)         // 预分配 100MB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        try {
            // 写入数据
            map.put("user:1001", "Alice");
            map.put("user:1002", "Bob");
            map.put("user:1003", "Charlie");

            // 读取数据
            System.out.println("user:1001 = " + map.get("user:1001"));
            System.out.println("user:1002 = " + map.get("user:1002"));
            System.out.println("总条目数: " + map.size());

            // 刷新到磁盘
            map.flush();
            System.out.println("数据已刷新到磁盘\n");

        } finally {
            map.close();
        }
    }

    /**
     * 示例 2: 大数据量场景
     */
    private static void example2LargeDataSet() {
        System.out.println("【示例 2】大数据量场景");
        System.out.println("使用 MMAP 处理大量数据，利用操作系统页缓存");

        RogueMap<String, String> map = RogueMap.<String, String>builder()
                .persistent("large-data.db")
                .mmap()
                .allocateSize(500 * 1024 * 1024L)         // 预分配 500MB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        try {
            long startTime = System.currentTimeMillis();

            // 写入 10 万条数据
            int count = 100000;
            for (int i = 0; i < count; i++) {
                String key = "key_" + i;
                String value = "value_" + i + "_data_with_some_content";
                map.put(key, value);

                if (i > 0 && i % 10000 == 0) {
                    System.out.println("已写入 " + i + " 条记录...");
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("写入 " + count + " 条记录完成");
            System.out.println("耗时: " + elapsed + " ms");
            System.out.println("平均: " + (count * 1000.0 / elapsed) + " ops/s");

            // 验证数据
            System.out.println("验证: key_50000 = " + map.get("key_50000"));
            System.out.println("总条目数: " + map.size() + "\n");

        } finally {
            map.close();
        }
    }

    /**
     * 示例 3: 原始类型优化
     */
    private static void example3PrimitiveTypes() {
        System.out.println("【示例 3】原始类型优化");
        System.out.println("使用原始类型可以获得更高的性能和更低的内存占用");

        // Long 类型的 Map
        RogueMap<Long, Long> idMap = RogueMap.<Long, Long>builder()
                .persistent("id-mapping.db")
                .mmap()
                .allocateSize(200 * 1024 * 1024L)         // 200MB
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            long startTime = System.currentTimeMillis();

            // 写入 ID 映射
            int count = 1000000;  // 100 万条
            for (long i = 0; i < count; i++) {
                idMap.put(i, i * 1000);

                if (i > 0 && i % 100000 == 0) {
                    System.out.println("已写入 " + i + " 条记录...");
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("写入 " + count + " 条 Long 类型记录完成");
            System.out.println("耗时: " + elapsed + " ms");
            System.out.println("平均: " + (count * 1000.0 / elapsed) + " ops/s");

            // 验证数据
            System.out.println("验证: 12345L -> " + idMap.get(12345L));
            System.out.println("总条目数: " + idMap.size() + "\n");

        } finally {
            idMap.close();
        }
    }

    /**
     * 示例 4: MMAP vs 堆外内存对比
     */
    public static void comparePerformance() {
        System.out.println("【性能对比】MMAP vs 堆外内存");

        int testSize = 100000;

        // 测试堆外内存模式
        System.out.println("\n1. 堆外内存模式:");
        RogueMap<String, String> offHeapMap = RogueMap.<String, String>builder()
                .offHeap()
                .maxMemory(1024L * 1024 * 1024)  // 1GB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < testSize; i++) {
            offHeapMap.put("key" + i, "value" + i);
        }
        long offHeapTime = System.currentTimeMillis() - startTime;
        System.out.println("   写入耗时: " + offHeapTime + " ms");
        System.out.println("   吞吐量: " + (testSize * 1000.0 / offHeapTime) + " ops/s");
        offHeapMap.close();

        // 测试 MMAP 模式
        System.out.println("\n2. MMAP 模式:");
        RogueMap<String, String> mmapMap = RogueMap.<String, String>builder()
                .persistent("perf-test.db")
                .mmap()
                .allocateSize(1024L * 1024 * 1024)  // 1GB
                .keyCodec(new StringCodec())
                .valueCodec(new StringCodec())
                .build();

        startTime = System.currentTimeMillis();
        for (int i = 0; i < testSize; i++) {
            mmapMap.put("key" + i, "value" + i);
        }
        long mmapTime = System.currentTimeMillis() - startTime;
        System.out.println("   写入耗时: " + mmapTime + " ms");
        System.out.println("   吞吐量: " + (testSize * 1000.0 / mmapTime) + " ops/s");

        System.out.println("\n性能对比:");
        System.out.println("   MMAP 相对性能: " + (offHeapTime * 100.0 / mmapTime) + "%");

        mmapMap.close();
    }
}
