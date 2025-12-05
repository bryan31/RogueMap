package com.yomahub.roguemap.serialization;

import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.performance.UserData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KryoObjectCodec 测试类
 */
class KryoObjectCodecTest {

    private long address;
    private static final int BUFFER_SIZE = 1024;

    @BeforeEach
    void setUp() {
        // 分配测试用的堆外内存
        address = UnsafeOps.allocate(BUFFER_SIZE);
    }

    @AfterEach
    void tearDown() {
        // 释放堆外内存
        if (address != 0) {
            UnsafeOps.free(address);
        }
    }

    @Test
    void testEncodeDecodeUserData() {
        KryoObjectCodec<UserData> codec = new KryoObjectCodec<>(UserData.class);

        UserData original = new UserData(
                12345L,
                "john_doe",
                "john@example.com",
                30,
                1000.50,
                System.currentTimeMillis(),
                "123 Main St, City",
                "555-1234"
        );

        // 计算大小
        int size = codec.calculateSize(original);
        assertTrue(size > 0, "Size should be positive");

        // 编码
        int written = codec.encode(address, original);
        assertEquals(size, written, "Written bytes should match calculated size");

        // 解码
        UserData decoded = codec.decode(address);

        // 验证
        assertNotNull(decoded);
        assertEquals(original.getUserId(), decoded.getUserId());
        assertEquals(original.getUsername(), decoded.getUsername());
        assertEquals(original.getEmail(), decoded.getEmail());
        assertEquals(original.getAge(), decoded.getAge());
        assertEquals(original.getBalance(), decoded.getBalance(), 0.001);
        assertEquals(original.getLastLoginTime(), decoded.getLastLoginTime());
        assertEquals(original.getAddress(), decoded.getAddress());
        assertEquals(original.getPhoneNumber(), decoded.getPhoneNumber());
    }

    @Test
    void testEncodeDecodeNull() {
        KryoObjectCodec<UserData> codec = new KryoObjectCodec<>(UserData.class);

        // 计算 null 的大小
        int size = codec.calculateSize(null);
        assertEquals(4, size, "Null should occupy 4 bytes");

        // 编码 null
        int written = codec.encode(address, null);
        assertEquals(4, written, "Null should write 4 bytes");

        // 解码 null
        UserData decoded = codec.decode(address);
        assertNull(decoded, "Decoded value should be null");
    }

    @Test
    void testEncodeDecodeSimpleObject() {
        KryoObjectCodec<TestData> codec = new KryoObjectCodec<>(TestData.class);

        TestData original = new TestData("Hello", 42);

        // 编码和解码
        int size = codec.calculateSize(original);
        int written = codec.encode(address, original);
        assertEquals(size, written);

        TestData decoded = codec.decode(address);

        // 验证
        assertNotNull(decoded);
        assertEquals(original.name, decoded.name);
        assertEquals(original.value, decoded.value);
    }

    @Test
    void testWithoutClassRegistration() {
        // 不注册类
        KryoObjectCodec<TestData> codec = new KryoObjectCodec<>(TestData.class, false);

        TestData original = new TestData("World", 100);

        // 编码和解码
        int size = codec.calculateSize(original);
        int written = codec.encode(address, original);
        assertEquals(size, written);

        TestData decoded = codec.decode(address);

        // 验证
        assertNotNull(decoded);
        assertEquals(original.name, decoded.name);
        assertEquals(original.value, decoded.value);
    }

    @Test
    void testFactoryMethod() {
        KryoObjectCodec<TestData> codec = KryoObjectCodec.create(TestData.class);
        assertNotNull(codec);

        TestData data = new TestData("Factory", 999);
        int size = codec.calculateSize(data);
        assertTrue(size > 0);
    }

    @Test
    void testFactoryMethodWithRegistration() {
        KryoObjectCodec<TestData> codec = KryoObjectCodec.create(TestData.class, true);
        assertNotNull(codec);

        TestData data = new TestData("Factory2", 888);
        int size = codec.calculateSize(data);
        assertTrue(size > 0);
    }

    @Test
    void testMultipleEncodeDecode() {
        KryoObjectCodec<TestData> codec = new KryoObjectCodec<>(TestData.class);

        // 编码多个对象
        TestData data1 = new TestData("First", 1);
        TestData data2 = new TestData("Second", 2);
        TestData data3 = new TestData("Third", 3);

        int size1 = codec.calculateSize(data1);
        int written1 = codec.encode(address, data1);
        assertEquals(size1, written1);

        long address2 = address + written1;
        int size2 = codec.calculateSize(data2);
        int written2 = codec.encode(address2, data2);
        assertEquals(size2, written2);

        long address3 = address2 + written2;
        int size3 = codec.calculateSize(data3);
        int written3 = codec.encode(address3, data3);
        assertEquals(size3, written3);

        // 解码并验证
        TestData decoded1 = codec.decode(address);
        assertEquals("First", decoded1.name);
        assertEquals(1, decoded1.value);

        TestData decoded2 = codec.decode(address2);
        assertEquals("Second", decoded2.name);
        assertEquals(2, decoded2.value);

        TestData decoded3 = codec.decode(address3);
        assertEquals("Third", decoded3.name);
        assertEquals(3, decoded3.value);
    }

    @Test
    void testEmptyObject() {
        KryoObjectCodec<TestData> codec = new KryoObjectCodec<>(TestData.class);

        TestData empty = new TestData();

        int size = codec.calculateSize(empty);
        int written = codec.encode(address, empty);
        assertEquals(size, written);

        TestData decoded = codec.decode(address);
        assertNotNull(decoded);
        assertNull(decoded.name);
        assertEquals(0, decoded.value);
    }

    /**
     * 测试用的简单数据类
     */
    public static class TestData implements Serializable {
        private String name;
        private int value;

        public TestData() {
        }

        public TestData(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestData testData = (TestData) o;
            return value == testData.value && Objects.equals(name, testData.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }

        @Override
        public String toString() {
            return "TestData{name='" + name + "', value=" + value + '}';
        }
    }
}
