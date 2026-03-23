package com.yomahub.roguemap.serialization;

import com.yomahub.roguemap.memory.UnsafeOps;

/**
 * 原始类型的零拷贝编解码器
 *
 * 这些编解码器直接在内存中读写原始类型值，无需序列化开销。
 */
public class PrimitiveCodecs {

    /**
     * Long 类型的编解码器
     */
    public static class LongCodec implements Codec<Long> {
        @Override
        public int encode(long address, Long value) {
            UnsafeOps.putLong(address, value);
            return 8;
        }

        @Override
        public Long decode(long address) {
            return UnsafeOps.getLong(address);
        }

        @Override
        public int calculateSize(Long value) {
            return 8;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 8;
        }
    }

    /**
     * Integer 类型的编解码器
     */
    public static class IntegerCodec implements Codec<Integer> {
        @Override
        public int encode(long address, Integer value) {
            UnsafeOps.putInt(address, value);
            return 4;
        }

        @Override
        public Integer decode(long address) {
            return UnsafeOps.getInt(address);
        }

        @Override
        public int calculateSize(Integer value) {
            return 4;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 4;
        }
    }

    /**
     * Double 类型的编解码器
     */
    public static class DoubleCodec implements Codec<Double> {
        @Override
        public int encode(long address, Double value) {
            UnsafeOps.putDouble(address, value);
            return 8;
        }

        @Override
        public Double decode(long address) {
            return UnsafeOps.getDouble(address);
        }

        @Override
        public int calculateSize(Double value) {
            return 8;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 8;
        }
    }

    /**
     * Float 类型的编解码器
     */
    public static class FloatCodec implements Codec<Float> {
        @Override
        public int encode(long address, Float value) {
            UnsafeOps.putFloat(address, value);
            return 4;
        }

        @Override
        public Float decode(long address) {
            return UnsafeOps.getFloat(address);
        }

        @Override
        public int calculateSize(Float value) {
            return 4;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 4;
        }
    }

    /**
     * Short 类型的编解码器
     */
    public static class ShortCodec implements Codec<Short> {
        @Override
        public int encode(long address, Short value) {
            UnsafeOps.putShort(address, value);
            return 2;
        }

        @Override
        public Short decode(long address) {
            return UnsafeOps.getShort(address);
        }

        @Override
        public int calculateSize(Short value) {
            return 2;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 2;
        }
    }

    /**
     * Byte 类型的编解码器
     */
    public static class ByteCodec implements Codec<Byte> {
        @Override
        public int encode(long address, Byte value) {
            UnsafeOps.putByte(address, value);
            return 1;
        }

        @Override
        public Byte decode(long address) {
            return UnsafeOps.getByte(address);
        }

        @Override
        public int calculateSize(Byte value) {
            return 1;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 1;
        }
    }

    /**
     * Boolean 类型的编解码器（存储为字节）
     */
    public static class BooleanCodec implements Codec<Boolean> {
        @Override
        public int encode(long address, Boolean value) {
            UnsafeOps.putByte(address, (byte) (value ? 1 : 0));
            return 1;
        }

        @Override
        public Boolean decode(long address) {
            return UnsafeOps.getByte(address) != 0;
        }

        @Override
        public int calculateSize(Boolean value) {
            return 1;
        }

        @Override
        public boolean isFixedSize() {
            return true;
        }

        @Override
        public int getFixedSize() {
            return 1;
        }
    }

    // 单例实例
    public static final LongCodec LONG = new LongCodec();
    public static final IntegerCodec INTEGER = new IntegerCodec();
    public static final DoubleCodec DOUBLE = new DoubleCodec();
    public static final FloatCodec FLOAT = new FloatCodec();
    public static final ShortCodec SHORT = new ShortCodec();
    public static final ByteCodec BYTE = new ByteCodec();
    public static final BooleanCodec BOOLEAN = new BooleanCodec();
}
