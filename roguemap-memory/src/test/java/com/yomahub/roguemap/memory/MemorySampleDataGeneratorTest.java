package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成 Memory 的持久化样本文件，供 RogueMapApp 桌面可视化工具测试使用。
 * 输出目录：target/test-data/
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemorySampleDataGeneratorTest {

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
    void generateMemoryFile() {
        String basePath = OUTPUT_DIR + "/sample-memory";
        Random random = new Random(42);

        try (RogueMemory mem = RogueMemory.mmap()
                .persistent(basePath)
                .allocateSize(64 * 1024 * 1024L)
                .embeddingProvider(new MockEmbeddingProvider(16))
                .searchMode(SearchMode.HYBRID)
                .build()) {

            // 对话记忆
            String[] conversations = {
                    "用户询问了关于Java并发编程的问题，我建议使用CompletableFuture来处理异步任务",
                    "用户报告了一个NullPointerException错误，定位到是空指针解引用导致的问题",
                    "用户需要优化数据库查询性能，建议添加复合索引并使用覆盖索引",
                    "用户想了解微服务架构的优缺点，讨论了服务拆分粒度和分布式事务的权衡",
                    "用户在部署Kubernetes集群时遇到了网络策略配置问题，已协助解决",
                    "用户询问了Redis缓存穿透、缓存击穿和缓存雪崩的区别和解决方案",
                    "用户希望实现一个消息队列系统，讨论了Kafka和RabbitMQ的选型对比",
                    "用户在进行代码审查时发现了潜在的SQL注入风险，已修复为参数化查询",
                    "用户需要设计一个限流方案，建议使用令牌桶算法配合Redis实现分布式限流",
                    "用户遇到了OOM问题，通过heap dump分析发现是内存泄漏导致的老年代溢出",
            };

            for (int i = 0; i < conversations.length; i++) {
                Map<String, String> meta = mapOf(
                        "type", "conversation",
                        "sessionId", "session-" + (i / 3 + 1),
                        "turn", String.valueOf(i % 3 + 1),
                        "timestamp", "2026-04-" + String.format("%02d", 1 + i) + "T10:30:00"
                );
                mem.add(conversations[i], meta, "conversations");
            }

            // 知识片段
            String[] knowledge = {
                    "RogueMap是一个高性能的嵌入式键值存储引擎，使用内存映射文件实现堆外存储",
                    "RogueMap支持四种数据结构：RogueMap（键值对）、RogueList（双向链表）、RogueSet（并发集合）、RogueQueue（FIFO队列）",
                    "RogueMap使用分段锁设计，64个独立的StampedLock最小化锁竞争",
                    "RogueMap的compact()方法可以回收碎片空间，创建仅包含活跃数据的新文件",
                    "RogueMap的持久化机制在close()或checkpoint()时将索引和元数据写入文件尾部",
                    "RogueMemory是AI记忆层，支持向量搜索和BM25关键词搜索的混合检索",
                    "HNSW向量索引用于近似最近邻搜索，默认参数M=16、efConstruction=200、ef=50",
                    "RogueMap的TTL支持允许设置数据过期时间，过期数据在读取时自动跳过",
                    "RogueMap的事务API提供原子性多键操作，使用读已提交隔离级别",
                    "RogueMap支持自动扩展，当空间不足时自动增长文件大小",
                    "RogueList维护位置索引数组，支持O(1)随机访问，但addFirst和removeFirst是O(n)",
                    "RogueQueue支持linked和circular两种模式，linked模式是无界队列，circular模式是有界环形缓冲区",
                    "LowHeapStringIndex将字符串键的字节存储在mmap堆外内存中，仅段元数据和锁留在JVM堆上",
                    "AutoCheckpointManager支持按时间间隔和操作计数两种自动检查点触发模式",
                    "RogueSet使用64段并发设计，迭代器采用惰性段加载，堆峰值仅为O(N/64)",
            };

            for (int i = 0; i < knowledge.length; i++) {
                Map<String, String> meta = mapOf(
                        "type", "knowledge",
                        "category", i < 5 ? "architecture" : (i < 10 ? "feature" : "data-structure"),
                        "importance", String.valueOf(1 + random.nextInt(5)),
                        "version", "1.1.2"
                );
                mem.add(knowledge[i], meta, "knowledge");
            }

            // 用户偏好
            String[] preferences = {
                    "用户偏好使用Java 8语法，避免使用Java 11+的特性",
                    "用户项目使用Maven构建，不使用Gradle",
                    "用户偏好中文注释和文档",
                    "用户习惯使用IntelliJ IDEA作为开发工具",
                    "用户偏好使用try-with-resources管理资源",
            };

            for (int i = 0; i < preferences.length; i++) {
                Map<String, String> meta = mapOf(
                        "type", "preference",
                        "scope", i < 2 ? "coding" : (i < 4 ? "tooling" : "style"),
                        "confidence", String.format("%.2f", 0.7 + random.nextDouble() * 0.3)
                );
                mem.add(preferences[i], meta, "preferences");
            }

            // 任务记忆
            String[] tasks = {
                    "待办：为RogueMap添加B树索引支持，支持范围查询",
                    "已完成：实现了LinkedQueue的free list节点回收机制",
                    "进行中：优化RogueSet迭代器的内存使用，采用惰性段加载",
                    "待办：添加RogueMap的watch/observe回调机制，支持数据变更通知",
                    "已完成：修复了MmapAllocator跨段分配导致的SIGSEGV问题",
                    "进行中：设计RogueMapApp桌面可视化工具，支持查看和编辑持久化文件",
            };

            for (int i = 0; i < tasks.length; i++) {
                String status = tasks[i].startsWith("待办") ? "todo" :
                        tasks[i].startsWith("已完成") ? "done" : "in-progress";
                Map<String, String> meta = mapOf(
                        "type", "task",
                        "status", status,
                        "priority", String.valueOf(1 + random.nextInt(3)),
                        "assignee", random.nextBoolean() ? "developer-a" : "developer-b"
                );
                mem.add(tasks[i], meta, "tasks");
            }

            // 更多随机对话记忆，增加数据量
            String[] extraTopics = {
                    "讨论了Spring Boot自动配置的原理和自定义starter的开发方法",
                    "分析了JVM G1垃圾收集器的工作原理和调优参数",
                    "对比了gRPC和REST API的性能差异和适用场景",
                    "介绍了分布式链路追踪的OpenTelemetry标准和实现方案",
                    "讨论了OAuth 2.0授权码模式和客户端凭证模式的区别",
                    "分享了MySQL索引优化的实战经验和EXPLAIN执行计划分析方法",
                    "探讨了Event Sourcing和CQRS架构模式的优缺点",
                    "介绍了WebFlux响应式编程模型和背压处理机制",
                    "讨论了领域驱动设计中聚合根、值对象和领域事件的定义",
                    "分析了Netty的线程模型和ByteBuf内存管理策略",
                    "讨论了分库分表的ShardingSphere方案和跨片查询问题",
                    "介绍了Service Mesh架构下Istio的流量管理和可观测性能力",
            };

            for (int i = 0; i < extraTopics.length; i++) {
                Map<String, String> meta = mapOf(
                        "type", "conversation",
                        "sessionId", "session-" + (10 + i / 2),
                        "turn", String.valueOf(i % 2 + 1),
                        "timestamp", "2026-04-" + String.format("%02d", 10 + i) + "T14:00:00",
                        "topic", extraTopics[i].substring(2, Math.min(10, extraTopics[i].length()))
                );
                mem.add(extraTopics[i], meta, "conversations");
            }
        }

        File dataFile = new File(basePath + ".mem");
        assertTrue(dataFile.exists(), "Memory数据文件应存在: " + dataFile.getAbsolutePath());
        assertTrue(dataFile.length() > 0, "Memory数据文件不应为空");
        System.out.println("[Memory] 生成完成: " + dataFile.getAbsolutePath() + " (" + dataFile.length() + " bytes)");

        File hnswFile = new File(basePath + ".hnsw");
        if (hnswFile.exists()) {
            System.out.println("[HNSW] 索引文件: " + hnswFile.getAbsolutePath() + " (" + hnswFile.length() + " bytes)");
        }
    }

    private static Map<String, String> mapOf(String... kvs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) m.put(kvs[i], kvs[i + 1]);
        return m;
    }
}
