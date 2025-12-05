package com.yomahub.roguemap.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.yomahub.roguemap.memory.UnsafeOps;

import java.io.ByteArrayOutputStream;

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

    private final Class<T> type;

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
        this.type = type;
        final Class<T> finalType = type;
        final boolean shouldRegister = registerClass;

        this.kryoHolder = ThreadLocal.withInitial(() -> {
            KryoHolder holder = new KryoHolder();
            if (shouldRegister) {
                // 预先注册类以提升性能
                holder.kryo.register(finalType);
            }
            return holder;
        });
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
        Input input = holder.input;
        input.setBuffer(data);

        return holder.kryo.readObject(input, type);
    }

    /**
     * 使用 Kryo 将对象序列化为字节数组
     */
    private byte[] serializeToBytes(Kryo kryo, Output output, T value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        output.setOutputStream(baos);
        kryo.writeObject(output, value);
        output.flush();

        return baos.toByteArray();
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
}
