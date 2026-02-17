package com.yomahub.roguemap.list;

import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Iterator;
import java.util.ListIterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueList 功能测试
 */
public class ListFunctionalTest {

    private static final String TEST_FILE = "target/test-list-functional.db";

    @BeforeEach
    public void setUp() {
        deleteTestFile();
    }

    @AfterEach
    public void tearDown() {
        deleteTestFile();
    }

    private void deleteTestFile() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    // ========== 基本操作测试 ==========

    @Test
    public void testAddFirstAndLast() {
        RogueList<String> list = RogueList.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            list.addLast("a");
            list.addLast("b");
            list.addFirst("first");
            list.addLast("c");

            assertEquals(4, list.size());
            assertEquals("first", list.getFirst());
            assertEquals("c", list.getLast());
        } finally {
            list.close();
        }
    }

    @Test
    public void testRemoveFirstAndLast() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            list.addLast(1L);
            list.addLast(2L);
            list.addLast(3L);

            assertEquals(1L, list.removeFirst());
            assertEquals(2, list.size());
            assertEquals(2L, list.getFirst());

            assertEquals(3L, list.removeLast());
            assertEquals(1, list.size());
            assertEquals(2L, list.getLast());
        } finally {
            list.close();
        }
    }

    @Test
    public void testGetByIndex() {
        RogueList<Integer> list = RogueList.<Integer>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            for (int i = 0; i < 100; i++) {
                list.addLast(i);
            }

            assertEquals(100, list.size());

            // 测试随机访问（O(1)）
            assertEquals(0, list.get(0));
            assertEquals(50, list.get(50));
            assertEquals(99, list.get(99));

            // 测试越界
            assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> list.get(100));
        } finally {
            list.close();
        }
    }

    @Test
    public void testClear() {
        RogueList<String> list = RogueList.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            for (int i = 0; i < 50; i++) {
                list.addLast("item" + i);
            }

            assertEquals(50, list.size());

            list.clear();

            assertEquals(0, list.size());
            assertTrue(list.isEmpty());
            assertNull(list.getFirst());
            assertNull(list.getLast());
        } finally {
            list.close();
        }
    }

    // ========== 迭代器测试 ==========

    @Test
    public void testIterator() {
        RogueList<String> list = RogueList.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            list.addLast("a");
            list.addLast("b");
            list.addLast("c");

            StringBuilder sb = new StringBuilder();
            Iterator<String> it = list.iterator();
            while (it.hasNext()) {
                sb.append(it.next());
            }

            assertEquals("abc", sb.toString());
        } finally {
            list.close();
        }
    }

    @Test
    public void testListIterator() {
        RogueList<Integer> list = RogueList.<Integer>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            list.addLast(1);
            list.addLast(2);
            list.addLast(3);

            ListIterator<Integer> it = list.listIterator();

            // 正向遍历
            assertTrue(it.hasNext());
            assertEquals(0, it.nextIndex());
            assertEquals(1, it.next());
            assertEquals(2, it.next());
            assertEquals(3, it.next());
            assertFalse(it.hasNext());

            // 反向遍历
            assertTrue(it.hasPrevious());
            assertEquals(2, it.previousIndex());
            assertEquals(3, it.previous());
            assertEquals(2, it.previous());
            assertEquals(1, it.previous());
            assertFalse(it.hasPrevious());
        } finally {
            list.close();
        }
    }

    @Test
    public void testListIteratorFromIndex() {
        RogueList<String> list = RogueList.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            list.addLast("a");
            list.addLast("b");
            list.addLast("c");
            list.addLast("d");

            ListIterator<String> it = list.listIterator(2);

            assertEquals("c", it.next());
            assertEquals("d", it.next());
            assertFalse(it.hasNext());
        } finally {
            list.close();
        }
    }

    // ========== 持久化测试 ==========

    @Test
    public void testPersistence() {
        // 第一次写入
        RogueList<String> list1 = RogueList.<String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .elementCodec(new StringCodec())
                .build();

        try {
            list1.addFirst("head");
            list1.addLast("middle");
            list1.addLast("tail");
            assertEquals(3, list1.size());
        } finally {
            list1.close();
        }

        // 重新打开并验证
        RogueList<String> list2 = RogueList.<String>mmap()
                .persistent(TEST_FILE)
                .elementCodec(new StringCodec())
                .build();

        try {
            assertEquals(3, list2.size());
            assertEquals("head", list2.getFirst());
            assertEquals("tail", list2.getLast());
            assertEquals("middle", list2.get(1));
        } finally {
            list2.close();
        }
    }

    @Test
    public void testPersistenceWithLong() {
        String file = "target/test-list-long.db";
        try {
            // 写入
            RogueList<Long> list1 = RogueList.<Long>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .elementCodec(PrimitiveCodecs.LONG)
                    .build();

            try {
                for (long i = 0; i < 1000; i++) {
                    list1.addLast(i);
                }
                assertEquals(1000, list1.size());
            } finally {
                list1.close();
            }

            // 读取
            RogueList<Long> list2 = RogueList.<Long>mmap()
                    .persistent(file)
                    .elementCodec(PrimitiveCodecs.LONG)
                    .build();

            try {
                assertEquals(1000, list2.size());
                assertEquals(0L, list2.get(0));
                assertEquals(999L, list2.get(999));
                assertEquals(500L, list2.get(500));
            } finally {
                list2.close();
            }
        } finally {
            new File(file).delete();
        }
    }

    // ========== 边界条件测试 ==========

    @Test
    public void testEmptyList() {
        RogueList<String> list = RogueList.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            assertEquals(0, list.size());
            assertTrue(list.isEmpty());
            assertNull(list.getFirst());
            assertNull(list.getLast());
            assertNull(list.removeFirst());
            assertNull(list.removeLast());
        } finally {
            list.close();
        }
    }

    @Test
    public void testNullElement() {
        RogueList<String> list = RogueList.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            assertThrows(IllegalArgumentException.class, () -> list.addFirst(null));
            assertThrows(IllegalArgumentException.class, () -> list.addLast(null));
        } finally {
            list.close();
        }
    }

    @Test
    public void testSingleElement() {
        RogueList<Integer> list = RogueList.<Integer>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            list.addFirst(42);

            assertEquals(1, list.size());
            assertEquals(42, list.getFirst());
            assertEquals(42, list.getLast());
            assertEquals(42, list.get(0));

            assertEquals(42, list.removeFirst());
            assertEquals(0, list.size());
        } finally {
            list.close();
        }
    }

    // ========== 大数据量测试 ==========

    @Test
    public void testLargeDataList() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .allocateSize(100 * 1024 * 1024L)
                .elementCodec(PrimitiveCodecs.LONG)
                .initialCapacity(10000)
                .build();

        try {
            int count = 10000;

            // 写入
            long startTime = System.currentTimeMillis();
            for (long i = 0; i < count; i++) {
                list.addLast(i);
            }
            long writeTime = System.currentTimeMillis() - startTime;

            assertEquals(count, list.size());

            // 随机访问测试
            startTime = System.currentTimeMillis();
            for (int i = 0; i < count; i += 100) {
                assertEquals((long) i, list.get(i));
            }
            long randomAccessTime = System.currentTimeMillis() - startTime;

            // 迭代测试
            startTime = System.currentTimeMillis();
            Iterator<Long> it = list.iterator();
            int iterCount = 0;
            while (it.hasNext()) {
                it.next();
                iterCount++;
            }
            long iterTime = System.currentTimeMillis() - startTime;

            assertEquals(count, iterCount);

            System.out.printf("List大数据量测试: 写入 %d 条, 耗时 %d ms; 随机访问耗时 %d ms; 迭代耗时 %d ms%n",
                    count, writeTime, randomAccessTime, iterTime);
        } finally {
            list.close();
        }
    }

    // ========== 持久化更新测试 ==========

    @Test
    public void testPersistenceAfterOperations() {
        String file = "target/test-list-ops.db";
        try {
            // 写入
            RogueList<Integer> list1 = RogueList.<Integer>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .elementCodec(PrimitiveCodecs.INTEGER)
                    .build();

            try {
                for (int i = 0; i < 10; i++) {
                    list1.addLast(i);
                }

                // 执行一些操作
                list1.removeFirst(); // 移除 0
                list1.removeLast();  // 移除 9
                list1.addFirst(100); // 添加 100 到头部

                assertEquals(9, list1.size());
            } finally {
                list1.close();
            }

            // 验证
            RogueList<Integer> list2 = RogueList.<Integer>mmap()
                    .persistent(file)
                    .elementCodec(PrimitiveCodecs.INTEGER)
                    .build();

            try {
                assertEquals(9, list2.size());
                assertEquals(100, list2.getFirst()); // 100 在头部
                assertEquals(8, list2.getLast());    // 8 在尾部（9被移除）
                assertEquals(1, list2.get(1));       // 原来的 1 现在是索引 1
            } finally {
                list2.close();
            }
        } finally {
            new File(file).delete();
        }
    }

    // ========== KryoObjectCodec 测试 ==========

    @Test
    public void testKryoObjectCodec() {
        RogueList<TestUser> list = RogueList.<TestUser>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .elementCodec(KryoObjectCodec.create(TestUser.class))
                .build();

        try {
            TestUser user1 = new TestUser(1L, "Alice", 25);
            TestUser user2 = new TestUser(2L, "Bob", 30);
            TestUser user3 = new TestUser(3L, "Charlie", 35);

            list.addLast(user1);
            list.addLast(user2);
            list.addFirst(user3);

            assertEquals(3, list.size());

            // 验证读取
            TestUser first = list.getFirst();
            assertEquals(3L, first.getId());
            assertEquals("Charlie", first.getName());
            assertEquals(35, first.getAge());

            TestUser last = list.getLast();
            assertEquals(2L, last.getId());
            assertEquals("Bob", last.getName());
            assertEquals(30, last.getAge());

            // 验证随机访问
            TestUser middle = list.get(1);
            assertEquals(1L, middle.getId());
            assertEquals("Alice", middle.getName());
            assertEquals(25, middle.getAge());
        } finally {
            list.close();
        }
    }

    @Test
    public void testKryoObjectCodecPersistence() {
        String file = "target/test-list-kryo.db";
        try {
            // 写入
            RogueList<TestUser> list1 = RogueList.<TestUser>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .elementCodec(KryoObjectCodec.create(TestUser.class))
                    .build();

            try {
                list1.addLast(new TestUser(1L, "Alice", 25));
                list1.addLast(new TestUser(2L, "Bob", 30));
                list1.addLast(new TestUser(3L, "Charlie", 35));
                assertEquals(3, list1.size());
            } finally {
                list1.close();
            }

            // 读取验证
            RogueList<TestUser> list2 = RogueList.<TestUser>mmap()
                    .persistent(file)
                    .elementCodec(KryoObjectCodec.create(TestUser.class))
                    .build();

            try {
                assertEquals(3, list2.size());

                TestUser user1 = list2.get(0);
                assertEquals(1L, user1.getId());
                assertEquals("Alice", user1.getName());
                assertEquals(25, user1.getAge());

                TestUser user2 = list2.get(1);
                assertEquals(2L, user2.getId());
                assertEquals("Bob", user2.getName());
                assertEquals(30, user2.getAge());

                TestUser user3 = list2.get(2);
                assertEquals(3L, user3.getId());
                assertEquals("Charlie", user3.getName());
                assertEquals(35, user3.getAge());
            } finally {
                list2.close();
            }
        } finally {
            new File(file).delete();
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
