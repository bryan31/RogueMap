package com.yomahub.roguemap.queue;

import com.yomahub.roguemap.RogueQueue;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueQueue 功能测试
 */
public class QueueFunctionalTest {

    private static final String TEST_FILE_LINKED = "target/test-queue-linked.db";
    private static final String TEST_FILE_CIRCULAR = "target/test-queue-circular.db";

    @BeforeEach
    public void setUp() {
        deleteTestFiles();
    }

    @AfterEach
    public void tearDown() {
        deleteTestFiles();
    }

    private void deleteTestFiles() {
        new File(TEST_FILE_LINKED).delete();
        new File(TEST_FILE_CIRCULAR).delete();
    }

    // ========== 链表队列测试 ==========

    @Test
    public void testLinkedQueueBasicOperations() {
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .temporary()
                .linked()
                .elementCodec(new StringCodec())
                .build();

        try {
            // 入队
            assertTrue(queue.offer("first"));
            assertTrue(queue.offer("second"));
            assertTrue(queue.offer("third"));

            assertEquals(3, queue.size());
            assertFalse(queue.isEmpty());

            // 查看队首
            assertEquals("first", queue.peek());
            assertEquals(3, queue.size()); // peek不移除

            // 出队
            assertEquals("first", queue.poll());
            assertEquals("second", queue.poll());
            assertEquals("third", queue.poll());

            assertEquals(0, queue.size());
            assertTrue(queue.isEmpty());
            assertNull(queue.poll());
            assertNull(queue.peek());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testLinkedQueueClear() {
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 100; i++) {
                assertTrue(queue.offer(i));
            }

            assertEquals(100, queue.size());

            queue.clear();

            assertEquals(0, queue.size());
            assertTrue(queue.isEmpty());
            assertNull(queue.poll());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testLinkedQueuePersistence() {
        // 写入
        RogueQueue<Integer> queue1 = RogueQueue.<Integer>mmap()
                .persistent(TEST_FILE_LINKED)
                .allocateSize(10 * 1024 * 1024L)
                .linked()
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            for (int i = 0; i < 100; i++) {
                queue1.offer(i);
            }
            assertEquals(100, queue1.size());
        } finally {
            queue1.close();
        }

        // 读取
        RogueQueue<Integer> queue2 = RogueQueue.<Integer>mmap()
                .persistent(TEST_FILE_LINKED)
                .linked()
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            assertEquals(100, queue2.size());

            // FIFO顺序验证
            for (int i = 0; i < 100; i++) {
                assertEquals(i, queue2.poll());
            }
            assertTrue(queue2.isEmpty());
        } finally {
            queue2.close();
        }
    }

    // ========== 环形队列测试 ==========

    @Test
    public void testCircularQueueBasicOperations() {
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .temporary()
                .circular(5, 64)
                .elementCodec(new StringCodec())
                .build();

        try {
            // 入队
            assertTrue(queue.offer("a"));
            assertTrue(queue.offer("b"));
            assertTrue(queue.offer("c"));

            assertEquals(3, queue.size());
            assertFalse(queue.isFull());

            // 填满队列
            assertTrue(queue.offer("d"));
            assertTrue(queue.offer("e"));
            assertTrue(queue.isFull());

            // 队列已满，offer返回false
            assertFalse(queue.offer("f"));

            // 出队
            assertEquals("a", queue.poll());
            assertEquals("b", queue.poll());

            // 现在有空位了
            assertTrue(queue.offer("g"));
            assertEquals(4, queue.size());

            // 继续出队
            assertEquals("c", queue.poll());
            assertEquals("d", queue.poll());
            assertEquals("e", queue.poll());
            assertEquals("g", queue.poll());

            assertTrue(queue.isEmpty());
            assertNull(queue.poll());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testCircularQueueWrapAround() {
        RogueQueue<Integer> queue = RogueQueue.<Integer>mmap()
                .temporary()
                .circular(3, 16)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            // 填满队列
            assertTrue(queue.offer(1));
            assertTrue(queue.offer(2));
            assertTrue(queue.offer(3));
            assertTrue(queue.isFull());

            // 出队1个
            assertEquals(1, queue.poll());

            // 再入队1个（测试环形环绕）
            assertTrue(queue.offer(4));

            // 验证顺序
            assertEquals(2, queue.poll());
            assertEquals(3, queue.poll());
            assertEquals(4, queue.poll());

            assertTrue(queue.isEmpty());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testCircularQueueClear() {
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .circular(10, 16)
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                assertTrue(queue.offer(i));
            }
            assertTrue(queue.isFull());

            queue.clear();

            assertEquals(0, queue.size());
            assertTrue(queue.isEmpty());
            assertFalse(queue.isFull());

            // 清空后可以继续使用
            assertTrue(queue.offer(100L));
            assertEquals(100L, queue.poll());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testCircularQueuePersistence() {
        // 写入
        RogueQueue<Integer> queue1 = RogueQueue.<Integer>mmap()
                .persistent(TEST_FILE_CIRCULAR)
                .allocateSize(10 * 1024 * 1024L)
                .circular(100, 16)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            for (int i = 0; i < 50; i++) {
                queue1.offer(i);
            }
            assertEquals(50, queue1.size());
        } finally {
            queue1.close();
        }

        // 读取
        RogueQueue<Integer> queue2 = RogueQueue.<Integer>mmap()
                .persistent(TEST_FILE_CIRCULAR)
                .circular(100, 16)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            assertEquals(50, queue2.size());

            // FIFO顺序验证
            for (int i = 0; i < 50; i++) {
                assertEquals(i, queue2.poll());
            }
            assertTrue(queue2.isEmpty());
        } finally {
            queue2.close();
        }
    }

    // ========== 边界条件测试 ==========

    @Test
    public void testEmptyQueue() {
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .temporary()
                .linked()
                .elementCodec(new StringCodec())
                .build();

        try {
            assertEquals(0, queue.size());
            assertTrue(queue.isEmpty());
            assertNull(queue.peek());
            assertNull(queue.poll());
            assertFalse(queue.isFull());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testNullElement() {
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .temporary()
                .linked()
                .elementCodec(new StringCodec())
                .build();

        try {
            assertThrows(IllegalArgumentException.class, () -> queue.offer(null));
        } finally {
            queue.close();
        }
    }

    @Test
    public void testCircularQueueElementSizeExceeded() {
        RogueQueue<String> queue = RogueQueue.<String>mmap()
                .temporary()
                .circular(10, 20) // 最大元素20字节
                .elementCodec(new StringCodec())
                .build();

        try {
            // "hello" 需要9字节（4字节长度+5字节数据），应该可以
            assertTrue(queue.offer("hello"));

            // 很长的字符串，应该抛出异常
            String longString = "this is a very long string that exceeds the maximum element size limit";
            assertThrows(IllegalArgumentException.class, () -> queue.offer(longString));
        } finally {
            queue.close();
        }
    }

    // ========== 大数据量测试 ==========

    @Test
    public void testLinkedQueueLargeData() {
        RogueQueue<Long> queue = RogueQueue.<Long>mmap()
                .temporary()
                .allocateSize(100 * 1024 * 1024L)
                .linked()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            int count = 10000;

            // 写入
            long startTime = System.currentTimeMillis();
            for (long i = 0; i < count; i++) {
                assertTrue(queue.offer(i));
            }
            long writeTime = System.currentTimeMillis() - startTime;

            assertEquals(count, queue.size());

            // 读取
            startTime = System.currentTimeMillis();
            for (long i = 0; i < count; i++) {
                assertEquals(i, queue.poll());
            }
            long readTime = System.currentTimeMillis() - startTime;

            assertTrue(queue.isEmpty());

            System.out.printf("LinkedQueue大数据量测试: 写入 %d 条, 耗时 %d ms; 读取耗时 %d ms%n",
                    count, writeTime, readTime);
        } finally {
            queue.close();
        }
    }

    @Test
    public void testCircularQueueLargeData() {
        int capacity = 1000;
        RogueQueue<Integer> queue = RogueQueue.<Integer>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .circular(capacity, 16)
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            // 填满队列
            for (int i = 0; i < capacity; i++) {
                assertTrue(queue.offer(i));
            }
            assertTrue(queue.isFull());

            // 混合读写
            int readCount = 0;
            int writeCount = capacity;

            long startTime = System.currentTimeMillis();
            for (int round = 0; round < 10; round++) {
                // 出队一半
                for (int i = 0; i < capacity / 2; i++) {
                    assertEquals(readCount++, queue.poll());
                }

                // 入队一半
                for (int i = 0; i < capacity / 2; i++) {
                    assertTrue(queue.offer(writeCount++));
                }
            }
            long rwTime = System.currentTimeMillis() - startTime;

            System.out.printf("CircularQueue混合读写测试: 10轮, 每轮 %d 读写, 耗时 %d ms%n",
                    capacity, rwTime);
        } finally {
            queue.close();
        }
    }

    // ========== KryoObjectCodec 测试 ==========

    @Test
    public void testLinkedQueueKryoObjectCodec() {
        RogueQueue<TestUser> queue = RogueQueue.<TestUser>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .linked()
                .elementCodec(KryoObjectCodec.create(TestUser.class))
                .build();

        try {
            TestUser user1 = new TestUser(1L, "Alice", 25);
            TestUser user2 = new TestUser(2L, "Bob", 30);
            TestUser user3 = new TestUser(3L, "Charlie", 35);

            // 入队
            assertTrue(queue.offer(user1));
            assertTrue(queue.offer(user2));
            assertTrue(queue.offer(user3));

            assertEquals(3, queue.size());

            // 查看队首
            TestUser peeked = queue.peek();
            assertEquals(1L, peeked.getId());
            assertEquals("Alice", peeked.getName());
            assertEquals(25, peeked.getAge());

            // 出队验证FIFO顺序
            TestUser polled1 = queue.poll();
            assertEquals(1L, polled1.getId());
            assertEquals("Alice", polled1.getName());

            TestUser polled2 = queue.poll();
            assertEquals(2L, polled2.getId());
            assertEquals("Bob", polled2.getName());

            TestUser polled3 = queue.poll();
            assertEquals(3L, polled3.getId());
            assertEquals("Charlie", polled3.getName());

            assertTrue(queue.isEmpty());
        } finally {
            queue.close();
        }
    }

    @Test
    public void testLinkedQueueKryoObjectCodecPersistence() {
        String file = "target/test-queue-kryo-linked.db";
        try {
            // 写入
            RogueQueue<TestUser> queue1 = RogueQueue.<TestUser>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .linked()
                    .elementCodec(KryoObjectCodec.create(TestUser.class))
                    .build();

            try {
                queue1.offer(new TestUser(1L, "Alice", 25));
                queue1.offer(new TestUser(2L, "Bob", 30));
                queue1.offer(new TestUser(3L, "Charlie", 35));
                assertEquals(3, queue1.size());
            } finally {
                queue1.close();
            }

            // 读取验证
            RogueQueue<TestUser> queue2 = RogueQueue.<TestUser>mmap()
                    .persistent(file)
                    .linked()
                    .elementCodec(KryoObjectCodec.create(TestUser.class))
                    .build();

            try {
                assertEquals(3, queue2.size());

                TestUser user1 = queue2.poll();
                assertEquals(1L, user1.getId());
                assertEquals("Alice", user1.getName());
                assertEquals(25, user1.getAge());

                TestUser user2 = queue2.poll();
                assertEquals(2L, user2.getId());
                assertEquals("Bob", user2.getName());
                assertEquals(30, user2.getAge());

                TestUser user3 = queue2.poll();
                assertEquals(3L, user3.getId());
                assertEquals("Charlie", user3.getName());
                assertEquals(35, user3.getAge());

                assertTrue(queue2.isEmpty());
            } finally {
                queue2.close();
            }
        } finally {
            new File(file).delete();
        }
    }

    @Test
    public void testCircularQueueKryoObjectCodec() {
        RogueQueue<TestUser> queue = RogueQueue.<TestUser>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .circular(10, 256) // 容量10，每个元素最大256字节
                .elementCodec(KryoObjectCodec.create(TestUser.class))
                .build();

        try {
            // 入队
            for (int i = 0; i < 10; i++) {
                assertTrue(queue.offer(new TestUser(i, "User" + i, 20 + i)));
            }
            assertTrue(queue.isFull());

            // 再入队应该失败
            assertFalse(queue.offer(new TestUser(99, "NewUser", 99)));

            // 出队一半
            for (int i = 0; i < 5; i++) {
                TestUser user = queue.poll();
                assertEquals(i, user.getId());
                assertEquals("User" + i, user.getName());
                assertEquals(20 + i, user.getAge());
            }

            assertEquals(5, queue.size());
            assertFalse(queue.isFull());

            // 再入队5个
            for (int i = 10; i < 15; i++) {
                assertTrue(queue.offer(new TestUser(i, "User" + i, 20 + i)));
            }
            assertTrue(queue.isFull());
        } finally {
            queue.close();
        }
    }

    // 测试用的用户类
    public static class TestUser {
        private long id;
        private String name;
        private int age;

        public TestUser() {}

        public TestUser(long id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public long getId() { return id; }
        public String getName() { return name; }
        public int getAge() { return age; }
    }
}
