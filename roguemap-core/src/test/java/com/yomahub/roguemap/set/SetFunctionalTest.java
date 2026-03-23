package com.yomahub.roguemap.set;

import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.serialization.KryoObjectCodec;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RogueSet 功能测试
 */
public class SetFunctionalTest {

    private static final String TEST_FILE = "target/test-set-functional.db";

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
    public void testAddAndContains() {
        RogueSet<String> set = RogueSet.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            // 添加元素
            assertTrue(set.add("hello"));
            assertTrue(set.add("world"));
            assertFalse(set.add("hello")); // 重复添加返回false

            // 检查存在性
            assertTrue(set.contains("hello"));
            assertTrue(set.contains("world"));
            assertFalse(set.contains("foo"));

            // 检查大小
            assertEquals(2, set.size());
            assertFalse(set.isEmpty());
        } finally {
            set.close();
        }
    }

    @Test
    public void testRemove() {
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            // 添加元素
            assertTrue(set.add(1L));
            assertTrue(set.add(2L));
            assertTrue(set.add(3L));

            assertEquals(3, set.size());

            // 移除元素
            assertTrue(set.remove(2L));
            assertFalse(set.contains(2L));
            assertEquals(2, set.size());

            // 移除不存在的元素
            assertFalse(set.remove(99L));
            assertEquals(2, set.size());
        } finally {
            set.close();
        }
    }

    @Test
    public void testClear() {
        RogueSet<Integer> set = RogueSet.<Integer>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.INTEGER)
                .build();

        try {
            for (int i = 0; i < 100; i++) {
                set.add(i);
            }

            assertEquals(100, set.size());

            set.clear();

            assertEquals(0, set.size());
            assertTrue(set.isEmpty());

            for (int i = 0; i < 100; i++) {
                assertFalse(set.contains(i));
            }
        } finally {
            set.close();
        }
    }

    // ========== 迭代器测试 ==========

    @Test
    public void testIterator() {
        RogueSet<String> set = RogueSet.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            set.add("a");
            set.add("b");
            set.add("c");

            int count = 0;
            Iterator<String> it = set.iterator();
            while (it.hasNext()) {
                String element = it.next();
                assertTrue(element.equals("a") || element.equals("b") || element.equals("c"));
                count++;
            }

            assertEquals(3, count);
        } finally {
            set.close();
        }
    }

    // ========== 持久化测试 ==========

    @Test
    public void testPersistence() {
        // 第一次写入
        RogueSet<String> set1 = RogueSet.<String>mmap()
                .persistent(TEST_FILE)
                .allocateSize(10 * 1024 * 1024L)
                .elementCodec(new StringCodec())
                .build();

        try {
            set1.add("persistence");
            set1.add("test");
            set1.add("data");
            assertEquals(3, set1.size());
        } finally {
            set1.close();
        }

        // 重新打开并验证
        RogueSet<String> set2 = RogueSet.<String>mmap()
                .persistent(TEST_FILE)
                .elementCodec(new StringCodec())
                .build();

        try {
            assertEquals(3, set2.size());
            assertTrue(set2.contains("persistence"));
            assertTrue(set2.contains("test"));
            assertTrue(set2.contains("data"));
            assertFalse(set2.contains("notexist"));
        } finally {
            set2.close();
        }
    }

    @Test
    public void testPersistenceWithLong() {
        String file = "target/test-set-long.db";
        try {
            // 写入
            RogueSet<Long> set1 = RogueSet.<Long>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .elementCodec(PrimitiveCodecs.LONG)
                    .build();

            try {
                for (long i = 0; i < 1000; i++) {
                    set1.add(i);
                }
                assertEquals(1000, set1.size());
            } finally {
                set1.close();
            }

            // 读取
            RogueSet<Long> set2 = RogueSet.<Long>mmap()
                    .persistent(file)
                    .elementCodec(PrimitiveCodecs.LONG)
                    .build();

            try {
                assertEquals(1000, set2.size());
                for (long i = 0; i < 1000; i++) {
                    assertTrue(set2.contains(i), "Should contain " + i);
                }
            } finally {
                set2.close();
            }
        } finally {
            new File(file).delete();
        }
    }

    // ========== 边界条件测试 ==========

    @Test
    public void testEmptySet() {
        RogueSet<String> set = RogueSet.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            assertEquals(0, set.size());
            assertTrue(set.isEmpty());
            assertFalse(set.contains("anything"));
            assertFalse(set.remove("anything"));
        } finally {
            set.close();
        }
    }

    @Test
    public void testNullElement() {
        RogueSet<String> set = RogueSet.<String>mmap()
                .temporary()
                .elementCodec(new StringCodec())
                .build();

        try {
            assertThrows(IllegalArgumentException.class, () -> set.add(null));
            assertFalse(set.contains(null));
            assertFalse(set.remove(null));
        } finally {
            set.close();
        }
    }

    // ========== 大数据量测试 ==========

    @Test
    public void testLargeDataSet() {
        RogueSet<Long> set = RogueSet.<Long>mmap()
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
                assertTrue(set.add(i));
            }
            long writeTime = System.currentTimeMillis() - startTime;

            assertEquals(count, set.size());

            // 读取验证
            startTime = System.currentTimeMillis();
            for (long i = 0; i < count; i++) {
                assertTrue(set.contains(i), "Should contain " + i);
            }
            long readTime = System.currentTimeMillis() - startTime;

            System.out.printf("Set大数据量测试: 写入 %d 条, 耗时 %d ms; 读取验证耗时 %d ms%n",
                    count, writeTime, readTime);
        } finally {
            set.close();
        }
    }

    // ========== 更新操作持久化测试 ==========

    @Test
    public void testPersistenceAfterRemove() {
        String file = "target/test-set-remove.db";
        try {
            // 写入并删除
            RogueSet<Integer> set1 = RogueSet.<Integer>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .elementCodec(PrimitiveCodecs.INTEGER)
                    .build();

            try {
                for (int i = 0; i < 10; i++) {
                    set1.add(i);
                }
                assertEquals(10, set1.size());

                // 删除部分
                assertTrue(set1.remove(2));
                assertTrue(set1.remove(4));
                assertTrue(set1.remove(6));

                assertEquals(7, set1.size());
            } finally {
                set1.close();
            }

            // 验证删除后的持久化
            RogueSet<Integer> set2 = RogueSet.<Integer>mmap()
                    .persistent(file)
                    .elementCodec(PrimitiveCodecs.INTEGER)
                    .build();

            try {
                assertEquals(7, set2.size());
                assertFalse(set2.contains(2));
                assertFalse(set2.contains(4));
                assertFalse(set2.contains(6));
                assertTrue(set2.contains(0));
                assertTrue(set2.contains(1));
                assertTrue(set2.contains(3));
            } finally {
                set2.close();
            }
        } finally {
            new File(file).delete();
        }
    }

    // ========== KryoObjectCodec 测试 ==========

    @Test
    public void testKryoObjectCodec() {
        RogueSet<TestUser> set = RogueSet.<TestUser>mmap()
                .temporary()
                .allocateSize(10 * 1024 * 1024L)
                .elementCodec(KryoObjectCodec.create(TestUser.class))
                .build();

        try {
            TestUser user1 = new TestUser(1L, "Alice", 25);
            TestUser user2 = new TestUser(2L, "Bob", 30);
            TestUser user3 = new TestUser(1L, "Alice", 25); // 与user1相同

            // 添加元素
            assertTrue(set.add(user1));
            assertTrue(set.add(user2));
            assertFalse(set.add(user3)); // 重复元素，返回false

            assertEquals(2, set.size());

            // 验证contains
            assertTrue(set.contains(new TestUser(1L, "Alice", 25)));
            assertTrue(set.contains(new TestUser(2L, "Bob", 30)));
            assertFalse(set.contains(new TestUser(3L, "Charlie", 35)));

            // 验证remove
            assertTrue(set.remove(new TestUser(1L, "Alice", 25)));
            assertEquals(1, set.size());
            assertFalse(set.contains(new TestUser(1L, "Alice", 25)));
        } finally {
            set.close();
        }
    }

    @Test
    public void testKryoObjectCodecPersistence() {
        String file = "target/test-set-kryo.db";
        try {
            // 写入
            RogueSet<TestUser> set1 = RogueSet.<TestUser>mmap()
                    .persistent(file)
                    .allocateSize(10 * 1024 * 1024L)
                    .elementCodec(KryoObjectCodec.create(TestUser.class))
                    .build();

            try {
                set1.add(new TestUser(1L, "Alice", 25));
                set1.add(new TestUser(2L, "Bob", 30));
                set1.add(new TestUser(3L, "Charlie", 35));
                assertEquals(3, set1.size());
            } finally {
                set1.close();
            }

            // 读取验证
            RogueSet<TestUser> set2 = RogueSet.<TestUser>mmap()
                    .persistent(file)
                    .elementCodec(KryoObjectCodec.create(TestUser.class))
                    .build();

            try {
                assertEquals(3, set2.size());
                assertTrue(set2.contains(new TestUser(1L, "Alice", 25)));
                assertTrue(set2.contains(new TestUser(2L, "Bob", 30)));
                assertTrue(set2.contains(new TestUser(3L, "Charlie", 35)));
                assertFalse(set2.contains(new TestUser(4L, "Dave", 40)));
            } finally {
                set2.close();
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestUser testUser = (TestUser) o;
            return id == testUser.id && age == testUser.age &&
                    (name != null ? name.equals(testUser.name) : testUser.name == null);
        }

        @Override
        public int hashCode() {
            int result = (int) (id ^ (id >>> 32));
            result = 31 * result + (name != null ? name.hashCode() : 0);
            result = 31 * result + age;
            return result;
        }
    }
}
