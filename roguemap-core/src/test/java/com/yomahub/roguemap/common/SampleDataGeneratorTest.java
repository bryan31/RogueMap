package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.RogueMap;
import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成 Map/List/Set/Queue 的持久化样本文件，供 RogueMapApp 桌面可视化工具测试使用。
 * 输出目录：target/test-data/
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SampleDataGeneratorTest {

    private static final String OUTPUT_DIR = "target/test-data";

    @BeforeAll
    void setUp() {
        File dir = new File(OUTPUT_DIR);
        if (dir.exists()) {
            for (File f : dir.listFiles()) f.delete();
        }
        dir.mkdirs();
    }

    @Test
    void generateMapFile() {
        String filePath = OUTPUT_DIR + "/sample-map.dat";
        RogueMap<String, String> map = RogueMap.<String, String>mmap()
                .persistent(filePath)
                .allocateSize(32 * 1024 * 1024L)
                .keyCodec(StringCodec.INSTANCE)
                .valueCodec(StringCodec.INSTANCE)
                .build();

        // 用户配置数据
        String[] names = {"张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十",
                "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry"};
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京",
                "Tokyo", "New York", "London", "Paris"};
        String[] roles = {"admin", "editor", "viewer", "moderator"};
        String[] statuses = {"active", "inactive", "suspended", "pending"};

        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            String key = "user:" + String.format("%04d", i);
            String name = names[random.nextInt(names.length)];
            String email = "user" + i + "@example.com";
            String city = cities[random.nextInt(cities.length)];
            String role = roles[random.nextInt(roles.length)];
            String status = statuses[random.nextInt(statuses.length)];
            int age = 18 + random.nextInt(50);
            double score = Math.round(random.nextDouble() * 1000.0) / 10.0;

            String value = String.format(
                    "{\"name\":\"%s\",\"email\":\"%s\",\"city\":\"%s\",\"role\":\"%s\",\"status\":\"%s\",\"age\":%d,\"score\":%.1f}",
                    name, email, city, role, status, age, score);
            map.put(key, value);
        }

        // 产品信息
        String[] categories = {"电子产品", "服装", "食品", "图书", "家居", "运动"};
        for (int i = 0; i < 50; i++) {
            String key = "product:" + String.format("%04d", i);
            String cat = categories[random.nextInt(categories.length)];
            double price = Math.round((10 + random.nextDouble() * 9990) * 100.0) / 100.0;
            int stock = random.nextInt(1000);
            String value = String.format(
                    "{\"name\":\"Product-%03d\",\"category\":\"%s\",\"price\":%.2f,\"stock\":%d}",
                    i, cat, price, stock);
            map.put(key, value);
        }

        map.close();
        assertFileExists(filePath);
        System.out.println("[Map] 生成完成: " + filePath + " (" + new File(filePath).length() + " bytes)");
    }

    @Test
    void generateListFile() {
        String filePath = OUTPUT_DIR + "/sample-list.dat";
        RogueList<String> list = RogueList.<String>mmap()
                .persistent(filePath)
                .allocateSize(16 * 1024 * 1024L)
                .elementCodec(StringCodec.INSTANCE)
                .build();

        // 时间序列数据
        LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        Random random = new Random(42);

        for (int i = 0; i < 300; i++) {
            LocalDateTime time = baseTime.plusMinutes(i * 5);
            double temperature = 15 + Math.sin(i * 0.05) * 10 + (random.nextDouble() - 0.5) * 3;
            double humidity = 60 + Math.cos(i * 0.03) * 20 + (random.nextDouble() - 0.5) * 5;
            int pressure = 1013 + (int) (Math.sin(i * 0.02) * 20);
            double windSpeed = Math.max(0, 5 + Math.sin(i * 0.08) * 8 + (random.nextDouble() - 0.5) * 4);

            String entry = String.format(
                    "{\"timestamp\":\"%s\",\"temperature\":%.1f,\"humidity\":%.1f,\"pressure\":%d,\"windSpeed\":%.1f}",
                    time.format(fmt), temperature, humidity, pressure, windSpeed);
            list.addLast(entry);
        }

        list.close();
        assertFileExists(filePath);
        System.out.println("[List] 生成完成: " + filePath + " (" + new File(filePath).length() + " bytes)");
    }

    @Test
    void generateSetFile() {
        String filePath = OUTPUT_DIR + "/sample-set.dat";
        RogueSet<String> set = RogueSet.<String>mmap()
                .persistent(filePath)
                .allocateSize(16 * 1024 * 1024L)
                .elementCodec(StringCodec.INSTANCE)
                .build();

        // 用户标签
        String[] tagPrefixes = {"tag", "category", "group", "label", "topic"};
        String[] tagValues = {"科技", "金融", "教育", "医疗", "娱乐", "体育", "旅游", "美食",
                "AI", "blockchain", "cloud", "IoT", "VR", "security", "data",
                "java", "python", "rust", "go", "typescript"};
        Random random = new Random(42);

        for (int i = 0; i < 200; i++) {
            String prefix = tagPrefixes[random.nextInt(tagPrefixes.length)];
            String value = tagValues[random.nextInt(tagValues.length)];
            set.add(prefix + ":" + value + ":" + i);
        }

        // 去重后的用户ID集合
        for (int i = 0; i < 100; i++) {
            set.add("uid:" + String.format("%08x", random.nextInt(0xFFFFFF)));
        }

        // IP地址集合
        for (int i = 0; i < 50; i++) {
            String ip = String.format("%d.%d.%d.%d",
                    10 + random.nextInt(240),
                    random.nextInt(256),
                    random.nextInt(256),
                    1 + random.nextInt(254));
            set.add("ip:" + ip);
        }

        set.close();
        assertFileExists(filePath);
        System.out.println("[Set] 生成完成: " + filePath + " (" + new File(filePath).length() + " bytes)");
    }

    @Test
    void generateQueueFile() {
        String filePath = OUTPUT_DIR + "/sample-queue.dat";
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .persistent(filePath)
                .allocateSize(32 * 1024 * 1024L)
                .linked()
                .elementCodec(StringCodec.INSTANCE)
                .build();

        String[] levels = {"INFO", "WARN", "ERROR", "DEBUG"};
        String[] services = {"auth-service", "api-gateway", "order-service", "payment-service",
                "user-service", "notification-service", "search-service"};
        String[] messages = {
                "Request processed successfully",
                "Database connection timeout",
                "User login from new device",
                "Order created",
                "Payment received",
                "Cache miss for key",
                "Rate limit exceeded",
                "Health check passed",
                "Configuration updated",
                "Scheduled task executed"
        };
        String[] endpoints = {"/api/users", "/api/orders", "/api/products", "/api/auth/login",
                "/api/search", "/api/payments", "/api/notifications"};

        Random random = new Random(42);
        LocalDateTime baseTime = LocalDateTime.of(2026, 4, 1, 8, 0, 0);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

        for (int i = 0; i < 500; i++) {
            LocalDateTime time = baseTime.plusSeconds(i * 3 + random.nextInt(2));
            String level = levels[random.nextInt(levels.length)];
            if ("DEBUG".equals(level) && random.nextDouble() > 0.3) {
                level = "INFO";
            }
            String service = services[random.nextInt(services.length)];
            String message = messages[random.nextInt(messages.length)];
            String endpoint = endpoints[random.nextInt(endpoints.length)];
            int duration = 5 + random.nextInt(500);
            int status = random.nextDouble() > 0.9 ? (400 + random.nextInt(200)) : 200;

            String entry = String.format(
                    "{\"timestamp\":\"%s\",\"level\":\"%s\",\"service\":\"%s\",\"message\":\"%s\",\"endpoint\":\"%s\",\"duration\":%d,\"status\":%d}",
                    time.format(fmt), level, service, message, endpoint, duration, status);
            queue.offer(entry);
        }

        queue.close();
        assertFileExists(filePath);
        System.out.println("[Queue] 生成完成: " + filePath + " (" + new File(filePath).length() + " bytes)");
    }

    private void assertFileExists(String path) {
        File file = new File(path);
        assertTrue(file.exists(), "文件应存在: " + path);
        assertTrue(file.length() > 0, "文件不应为空: " + path);
    }
}
