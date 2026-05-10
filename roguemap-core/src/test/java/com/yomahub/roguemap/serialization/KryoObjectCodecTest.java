package com.yomahub.roguemap.serialization;

import com.yomahub.roguemap.memory.UnsafeOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KryoObjectCodec 测试类
 */
class KryoObjectCodecTest {

    private static final String KRYO_UNSAFE_PROPERTY = "kryo.unsafe";

    private long address;
    private String originalKryoUnsafe;
    private static final int BUFFER_SIZE = 1024;

    @BeforeEach
    void setUp() {
        originalKryoUnsafe = System.getProperty(KRYO_UNSAFE_PROPERTY);
        // 分配测试用的堆外内存
        address = UnsafeOps.allocate(BUFFER_SIZE);
    }

    @AfterEach
    void tearDown() {
        // 释放堆外内存
        if (address != 0) {
            UnsafeOps.free(address);
        }
        restoreKryoUnsafeProperty();
    }

    @Test
    void testDisableKryoUnsafeOnJdk25WhenUserDidNotConfigureIt() {
        System.clearProperty(KRYO_UNSAFE_PROPERTY);

        KryoObjectCodec.configureKryoUnsafeForFeatureVersion(25);

        assertEquals("false", System.getProperty(KRYO_UNSAFE_PROPERTY));
    }

    @Test
    void testKeepExplicitKryoUnsafeConfigurationOnJdk25() {
        System.setProperty(KRYO_UNSAFE_PROPERTY, "true");

        KryoObjectCodec.configureKryoUnsafeForFeatureVersion(25);

        assertEquals("true", System.getProperty(KRYO_UNSAFE_PROPERTY));
    }

    @Test
    void testKeepKryoDefaultBeforeJdk25() {
        System.clearProperty(KRYO_UNSAFE_PROPERTY);

        KryoObjectCodec.configureKryoUnsafeForFeatureVersion(24);

        assertNull(System.getProperty(KRYO_UNSAFE_PROPERTY));
    }

    private void restoreKryoUnsafeProperty() {
        if (originalKryoUnsafe == null) {
            System.clearProperty(KRYO_UNSAFE_PROPERTY);
        } else {
            System.setProperty(KRYO_UNSAFE_PROPERTY, originalKryoUnsafe);
        }
    }

    @Test
    void testEncodeDecodeComplexObject() {
        KryoObjectCodec<ComplexData> codec = new KryoObjectCodec<>(ComplexData.class);

        ComplexData original = new ComplexData(
                12345L,
                "john_doe",
                "john@example.com",
                30,
                1000.50
        );

        // 计算大小
        int size = codec.calculateSize(original);
        assertTrue(size > 0, "Size should be positive");

        // 编码
        int written = codec.encode(address, original);
        assertEquals(size, written, "Written bytes should match calculated size");

        // 解码
        ComplexData decoded = codec.decode(address);

        // 验证
        assertNotNull(decoded);
        assertEquals(original.id, decoded.id);
        assertEquals(original.name, decoded.name);
        assertEquals(original.email, decoded.email);
        assertEquals(original.age, decoded.age);
        assertEquals(original.balance, decoded.balance, 0.001);
    }

    @Test
    void testEncodeDecodeNull() {
        KryoObjectCodec<ComplexData> codec = new KryoObjectCodec<>(ComplexData.class);

        // 计算 null 的大小
        int size = codec.calculateSize(null);
        assertEquals(4, size, "Null should occupy 4 bytes");

        // 编码 null
        int written = codec.encode(address, null);
        assertEquals(4, written, "Null should write 4 bytes");

        // 解码 null
        ComplexData decoded = codec.decode(address);
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
    void testTypeReferenceListRoundTrip() {
        KryoObjectCodec<List<TestData>> codec = KryoObjectCodec.create(new TypeReference<List<TestData>>() {});

        List<TestData> original = new ArrayList<>();
        original.add(new TestData("Alice", 1));
        original.add(new TestData("Bob", 2));

        int size = codec.calculateSize(original);
        int written = codec.encode(address, original);
        assertEquals(size, written);

        List<TestData> decoded = codec.decode(address);
        assertNotNull(decoded);
        assertEquals(2, decoded.size());
        assertEquals(original, decoded);
    }

    @Test
    void testTypeReferenceInterfaceRoot() {
        KryoObjectCodec<List<TestData>> codec = KryoObjectCodec.create(new TypeReference<List<TestData>>() {});

        List<TestData> original = new ArrayList<>();
        original.add(new TestData("Interface", 100));

        codec.calculateSize(original);
        codec.encode(address, original);

        List<TestData> decoded = codec.decode(address);
        assertNotNull(decoded);
        assertTrue(decoded instanceof ArrayList);
        assertEquals("Interface", decoded.get(0).name);
        assertEquals(100, decoded.get(0).value);
    }

    @Test
    void testTypeReferenceNullAndEmpty() {
        KryoObjectCodec<List<TestData>> codec = KryoObjectCodec.create(new TypeReference<List<TestData>>() {});

        int nullSize = codec.calculateSize(null);
        assertEquals(4, nullSize);
        int nullWritten = codec.encode(address, null);
        assertEquals(4, nullWritten);
        assertNull(codec.decode(address));

        List<TestData> empty = new ArrayList<>();
        int emptySize = codec.calculateSize(empty);
        int emptyWritten = codec.encode(address, empty);
        assertEquals(emptySize, emptyWritten);

        List<TestData> decodedEmpty = codec.decode(address);
        assertNotNull(decodedEmpty);
        assertTrue(decodedEmpty.isEmpty());
    }

    @Test
    void testClassModeBackwardBehavior() {
        KryoObjectCodec<TestData> codec = KryoObjectCodec.create(TestData.class);
        TestData original = new TestData("ClassMode", 777);

        int size = codec.calculateSize(original);
        int written = codec.encode(address, original);
        assertEquals(size, written);

        TestData decoded = codec.decode(address);
        assertNotNull(decoded);
        assertEquals(original, decoded);
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

    /**
     * 测试用的复杂数据类
     */
    public static class ComplexData implements Serializable {
        private long id;
        private String name;
        private String email;
        private int age;
        private double balance;

        public ComplexData() {
        }

        public ComplexData(long id, String name, String email, int age, double balance) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.age = age;
            this.balance = balance;
        }
    }
}
