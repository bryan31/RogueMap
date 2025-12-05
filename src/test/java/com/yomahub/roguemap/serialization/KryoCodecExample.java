package com.yomahub.roguemap.serialization;

import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.performance.UserData;

/**
 * KryoObjectCodec 使用示例
 *
 * 展示如何在 RogueMap 中使用 KryoObjectCodec 进行对象序列化
 */
public class KryoCodecExample {

    public static void main(String[] args) {
        // 示例1: 使用 KryoObjectCodec 存储自定义对象
        example1_BasicUsage();

        // 示例2: 不注册类的使用方式
        example2_WithoutRegistration();

        // 示例3: 在 RogueMap 中使用
        example3_WithRogueMap();
    }

    /**
     * 示例1: 基本使用
     */
    private static void example1_BasicUsage() {
        System.out.println("=== 示例1: 基本使用 ===");

        // 创建 KryoObjectCodec，默认会注册类以获得更好的性能
        KryoObjectCodec<UserData> codec = new KryoObjectCodec<>(UserData.class);

        // 创建测试数据
        UserData user = new UserData(
                1L,
                "zhangsan",
                "zhangsan@example.com",
                25,
                5000.0,
                System.currentTimeMillis(),
                "北京市朝阳区",
                "13800138000"
        );

        // 计算序列化后的大小
        int size = codec.calculateSize(user);
        System.out.println("序列化后大小: " + size + " 字节");

        System.out.println();
    }

    /**
     * 示例2: 不注册类的使用方式
     * 不注册类会导致序列化后的数据更大，性能稍差，但更灵活
     */
    private static void example2_WithoutRegistration() {
        System.out.println("=== 示例2: 不注册类 ===");

        // 创建不注册类的 codec
        KryoObjectCodec<UserData> codec = new KryoObjectCodec<>(UserData.class, false);

        UserData user = new UserData(
                2L,
                "lisi",
                "lisi@example.com",
                30,
                8000.0,
                System.currentTimeMillis(),
                "上海市浦东新区",
                "13900139000"
        );

        int size = codec.calculateSize(user);
        System.out.println("未注册类时序列化大小: " + size + " 字节");

        System.out.println();
    }

    /**
     * 示例3: 在 RogueMap 中使用
     */
    private static void example3_WithRogueMap() {
        System.out.println("=== 示例3: 在 RogueMap 中使用 ===");

        // 创建使用 KryoObjectCodec 的 RogueMap
        RogueMap<Long, UserData> userMap = RogueMap.<Long, UserData>builder()
                .keyCodec(PrimitiveCodecs.LONG)
                .valueCodec(new KryoObjectCodec<>(UserData.class))
                .maxMemory(10 * 1024 * 1024) // 10MB
                .build();

        try {
            // 插入数据
            for (int i = 1; i <= 5; i++) {
                UserData user = new UserData(
                        (long) i,
                        "user" + i,
                        "user" + i + "@example.com",
                        20 + i,
                        1000.0 * i,
                        System.currentTimeMillis(),
                        "地址" + i,
                        "1380013800" + i
                );
                userMap.put((long) i, user);
            }

            System.out.println("插入了 5 条用户数据");
            System.out.println("Map 大小: " + userMap.size());

            // 读取数据
            UserData user3 = userMap.get(3L);
            System.out.println("读取 ID=3 的用户: " + user3.getUsername() + ", " + user3.getEmail());

            // 更新数据
            user3.setBalance(99999.99);
            userMap.put(3L, user3);
            System.out.println("更新了 ID=3 的用户余额");

            // 验证更新
            UserData updated = userMap.get(3L);
            System.out.println("更新后余额: " + updated.getBalance());

            // 删除数据
            userMap.remove(1L);
            System.out.println("删除了 ID=1 的用户");
            System.out.println("删除后 Map 大小: " + userMap.size());

        } finally {
            // 关闭 map，释放堆外内存
            userMap.close();
            System.out.println("已释放堆外内存");
        }

        System.out.println();
    }

    /**
     * 示例4: 使用工厂方法创建
     */
    @SuppressWarnings("unused")
    private static void example4_FactoryMethod() {
        System.out.println("=== 示例4: 使用工厂方法 ===");

        // 使用工厂方法创建，默认注册类
        KryoObjectCodec<UserData> codec1 = KryoObjectCodec.create(UserData.class);

        // 使用工厂方法创建，指定是否注册类
        KryoObjectCodec<UserData> codec2 = KryoObjectCodec.create(UserData.class, false);

        System.out.println("使用工厂方法创建了两个 codec 实例");
        System.out.println();
    }
}
