package com.yomahub.roguemap.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.yomahub.roguemap.memory.UnsafeOps;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;

/**
 * 基于 Kryo 的通用对象编解码器
 * <p>
 * 使用 Kryo 序列化框架对任意对象进行序列化/反序列化处理。
 * 使用线程本地的 Kryo 实例以确保线程安全。
 * </p>
 *
 * @param <T> 要编码/解码的对象类型
 */
public class KryoObjectCodec<T> implements Codec<T> {

    private static final String KRYO_UNSAFE_PROPERTY = "kryo.unsafe";

    static {
        configureKryoUnsafeForFeatureVersion(currentFeatureVersion());
    }

    private final Class<T> type;
    private final Class<?> rawClass;
    private final Mode mode;

    private enum Mode {
        CLASS_MODE,
        TYPE_REF_MODE
    }

    /**
     * 线程本地的 Kryo 实例,避免多线程竞争
     * Kryo 不是线程安全的,必须每个线程使用独立的实例
     */
    private final ThreadLocal<KryoHolder> kryoHolder;

    /**
     * 创建 KryoObjectCodec 实例
     *
     * @param type 要序列化的类类型
     */
    public KryoObjectCodec(Class<T> type) {
        this(type, true);
    }

    /**
     * 创建 KryoObjectCodec 实例
     *
     * @param type 要序列化的类类型
     * @param registerClass 是否注册类(注册后性能更好,序列化数据更小)
     */
    public KryoObjectCodec(Class<T> type, boolean registerClass) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }

        this.type = type;
        this.rawClass = type;
        this.mode = Mode.CLASS_MODE;
        final Class<?> classToRegister = type;
        final boolean shouldRegister = registerClass;

        this.kryoHolder = createKryoHolder(classToRegister, shouldRegister);
    }

    /**
     * 创建 KryoObjectCodec 实例（支持复杂泛型）
     *
     * @param typeReference 目标类型引用
     */
    public KryoObjectCodec(TypeReference<T> typeReference) {
        this(typeReference, true);
    }

    /**
     * 创建 KryoObjectCodec 实例（支持复杂泛型）
     *
     * @param typeReference 目标类型引用
     * @param registerClass 是否注册原始类型（若是接口/抽象类则自动跳过）
     */
    public KryoObjectCodec(TypeReference<T> typeReference, boolean registerClass) {
        if (typeReference == null) {
            throw new IllegalArgumentException("typeReference 不能为 null");
        }

        this.type = null;
        this.rawClass = typeReference.getRawClass();
        this.mode = Mode.TYPE_REF_MODE;

        final Class<?> classToRegister = canRegister(rawClass) ? rawClass : null;
        final boolean shouldRegister = registerClass;
        this.kryoHolder = createKryoHolder(classToRegister, shouldRegister);
    }

    @Override
    public int calculateSize(T value) {
        if (value == null) {
            return 4; // 只存储长度字段,值为 -1
        }

        // 使用缓存避免重复序列化
        KryoHolder holder = kryoHolder.get();
        byte[] serialized = serializeToBytes(holder.kryo, holder.output, value);
        holder.cachedBytes = serialized;

        // 4 字节长度 + 实际数据
        return 4 + serialized.length;
    }

    @Override
    public int encode(long address, T value) {
        if (value == null) {
            // null 值写入 -1 作为长度标记
            UnsafeOps.putInt(address, -1);
            return 4;
        }

        KryoHolder holder = kryoHolder.get();
        byte[] serialized = holder.cachedBytes;

        // 如果没有缓存,重新序列化
        if (serialized == null) {
            serialized = serializeToBytes(holder.kryo, holder.output, value);
        }

        // 写入长度
        UnsafeOps.putInt(address, serialized.length);
        // 写入数据
        UnsafeOps.copyFromArray(serialized, 0, address + 4, serialized.length);

        // 清除缓存
        holder.cachedBytes = null;

        return 4 + serialized.length;
    }

    @Override
    public T decode(long address) {
        // 读取长度
        int length = UnsafeOps.getInt(address);

        if (length < 0) {
            return null;
        }

        if (length == 0) {
            return null;
        }

        // 读取数据到字节数组
        byte[] data = new byte[length];
        UnsafeOps.copyToArray(address + 4, data, 0, length);

        // 反序列化
        KryoHolder holder = kryoHolder.get();
        holder.cachedBytes = null; // 清除残留缓存，防止后续 encode() 误用旧数据
        holder.kryo.reset();  // 重置引用追踪表，确保每次解码从干净状态开始
        Input input = holder.input;
        input.setBuffer(data);

        if (mode == Mode.CLASS_MODE) {
            return holder.kryo.readObject(input, type);
        }

        Object decoded = holder.kryo.readClassAndObject(input);
        if (decoded == null) {
            return null;
        }
        if (!rawClass.isInstance(decoded)) {
            throw new IllegalStateException("解码类型不匹配，期望: " + rawClass.getTypeName() +
                    ", 实际: " + decoded.getClass().getTypeName());
        }
        @SuppressWarnings("unchecked")
        T casted = (T) decoded;
        return casted;
    }

    /**
     * 使用 Kryo 将对象序列化为字节数组
     *
     * 每次调用前重置 Kryo 引用追踪表（kryo.reset()）和 Output 位置（output.reset()），
     * 确保两次调用（calculateSize 和 encode）序列化结果确定性一致，防止 BufferUnderflow。
     */
    private byte[] serializeToBytes(Kryo kryo, Output output, T value) {
        kryo.reset();  // 重置引用追踪表，确保幂等性
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        output.reset();
        output.setOutputStream(baos);
        if (mode == Mode.CLASS_MODE) {
            kryo.writeObject(output, value);
        } else {
            kryo.writeClassAndObject(output, value);
        }
        output.flush();

        return baos.toByteArray();
    }

    private static boolean canRegister(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        int modifiers = clazz.getModifiers();
        return !clazz.isInterface() && !Modifier.isAbstract(modifiers);
    }

    private static ThreadLocal<KryoHolder> createKryoHolder(Class<?> classToRegister, boolean shouldRegister) {
        return ThreadLocal.withInitial(() -> {
            KryoHolder holder = new KryoHolder();
            if (shouldRegister && classToRegister != null) {
                // 预先注册类以提升性能
                holder.kryo.register(classToRegister);
            }
            return holder;
        });
    }

    static void configureKryoUnsafeForFeatureVersion(int featureVersion) {
        if (featureVersion >= 25 && System.getProperty(KRYO_UNSAFE_PROPERTY) == null) {
            System.setProperty(KRYO_UNSAFE_PROPERTY, "false");
        }
    }

    private static int currentFeatureVersion() {
        String specVersion = System.getProperty("java.specification.version", "8");
        if (specVersion.startsWith("1.")) {
            specVersion = specVersion.substring(2);
        }
        int dot = specVersion.indexOf('.');
        if (dot >= 0) {
            specVersion = specVersion.substring(0, dot);
        }
        try {
            return Integer.parseInt(specVersion);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /**
     * Kryo 持有者,包含线程本地的 Kryo 实例和相关资源
     */
    private static class KryoHolder {
        final Kryo kryo;
        final Output output;
        final Input input;
        byte[] cachedBytes; // 用于缓存 calculateSize 的结果

        KryoHolder() {
            this.kryo = new Kryo();
            // 设置引用处理,提升性能
            this.kryo.setReferences(true);
            // 设置注册要求为 false,允许序列化未注册的类
            this.kryo.setRegistrationRequired(false);

            // 预分配缓冲区
            this.output = new Output(4096, -1);
            this.input = new Input();
        }
    }

    /**
     * 创建一个 KryoObjectCodec 实例的便捷工厂方法
     *
     * @param type 要序列化的类类型
     * @param <T> 类型参数
     * @return KryoObjectCodec 实例
     */
    public static <T> KryoObjectCodec<T> create(Class<T> type) {
        return new KryoObjectCodec<>(type);
    }

    /**
     * 创建一个 KryoObjectCodec 实例的便捷工厂方法
     *
     * @param type 要序列化的类类型
     * @param registerClass 是否注册类
     * @param <T> 类型参数
     * @return KryoObjectCodec 实例
     */
    public static <T> KryoObjectCodec<T> create(Class<T> type, boolean registerClass) {
        return new KryoObjectCodec<>(type, registerClass);
    }

    /**
     * 创建一个 KryoObjectCodec 实例的便捷工厂方法（支持复杂泛型）
     *
     * @param typeReference 目标类型引用
     * @param <T> 类型参数
     * @return KryoObjectCodec 实例
     */
    public static <T> KryoObjectCodec<T> create(TypeReference<T> typeReference) {
        return new KryoObjectCodec<>(typeReference);
    }

    /**
     * 创建一个 KryoObjectCodec 实例的便捷工厂方法（支持复杂泛型）
     *
     * @param typeReference 目标类型引用
     * @param registerClass 是否注册原始类型
     * @param <T> 类型参数
     * @return KryoObjectCodec 实例
     */
    public static <T> KryoObjectCodec<T> create(TypeReference<T> typeReference, boolean registerClass) {
        return new KryoObjectCodec<>(typeReference, registerClass);
    }
}
