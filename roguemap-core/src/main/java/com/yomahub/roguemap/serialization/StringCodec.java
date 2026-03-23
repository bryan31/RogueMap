package com.yomahub.roguemap.serialization;

import com.yomahub.roguemap.memory.UnsafeOps;

import java.nio.charset.StandardCharsets;

/**
 * String 类型的编解码器
 *
 * 格式：[4 字节长度][UTF-8 字节]
 */
public class StringCodec implements Codec<String> {

    public static final StringCodec INSTANCE = new StringCodec();

    @Override
    public int encode(long address, String value) {
        if (value == null) {
            UnsafeOps.putInt(address, -1);
            return 4;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;

        // 写入长度
        UnsafeOps.putInt(address, length);

        // 写入字节
        if (length > 0) {
            UnsafeOps.copyFromArray(bytes, 0, address + 4, length);
        }

        return 4 + length;
    }

    @Override
    public String decode(long address) {
        int length = UnsafeOps.getInt(address);

        if (length == -1) {
            return null;
        }

        if (length == 0) {
            return "";
        }

        byte[] bytes = new byte[length];
        UnsafeOps.copyToArray(address + 4, bytes, 0, length);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int calculateSize(String value) {
        if (value == null) {
            return 4;
        }
        return 4 + value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public boolean isFixedSize() {
        return false;
    }
}
