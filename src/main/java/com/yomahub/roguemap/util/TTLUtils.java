package com.yomahub.roguemap.util;

import com.yomahub.roguemap.memory.UnsafeOps;

import java.util.concurrent.TimeUnit;

/**
 * TTL (Time-To-Live) 工具类
 *
 * <p>提供过期时间的计算、存储和检查功能。
 * 过期时间作为数据前缀存储在 mmap 内存中。
 *
 * <p>存储格式：[expireTime(8 bytes)][实际数据]
 * expireTime = 0 表示永不过期
 */
public class TTLUtils {

    /**
     * TTL 头大小（8 字节用于存储过期时间戳）
     */
    public static final int TTL_HEADER_SIZE = 8;

    /**
     * 默认 TTL 值（0 表示永不过期）
     */
    public static final long DEFAULT_TTL = 0;

    private TTLUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算 TTL 对应的过期时间戳
     *
     * @param ttlMillis TTL 毫秒数，<= 0 表示永不过期
     * @return 过期时间戳（毫秒），0 表示永不过期
     */
    public static long calculateExpireTime(long ttlMillis) {
        if (ttlMillis <= 0) {
            return 0;
        }
        return System.currentTimeMillis() + ttlMillis;
    }

    /**
     * 将 TimeUnit 转换为毫秒数
     *
     * @param ttl  TTL 值
     * @param unit 时间单位
     * @return 毫秒数，<= 0 表示永不过期
     */
    public static long toMillis(long ttl, TimeUnit unit) {
        if (ttl <= 0 || unit == null) {
            return 0;
        }
        return unit.toMillis(ttl);
    }

    /**
     * 检查是否已过期
     *
     * @param expireTime 过期时间戳（毫秒），0 表示永不过期
     * @return true 如果已过期，false 如果未过期或永不过期
     */
    public static boolean isExpired(long expireTime) {
        // expireTime <= 0 表示永不过期
        if (expireTime <= 0) {
            return false;
        }
        return System.currentTimeMillis() > expireTime;
    }

    /**
     * 从内存地址读取过期时间
     *
     * @param dataAddress 数据起始地址（TTL header 位置）
     * @return 过期时间戳（毫秒），0 表示永不过期
     */
    public static long readExpireTime(long dataAddress) {
        return UnsafeOps.getLong(dataAddress);
    }

    /**
     * 将过期时间写入内存地址
     *
     * @param dataAddress 数据起始地址（TTL header 位置）
     * @param expireTime  过期时间戳（毫秒），0 表示永不过期
     */
    public static void writeExpireTime(long dataAddress, long expireTime) {
        UnsafeOps.putLong(dataAddress, expireTime);
    }

    /**
     * 获取实际数据的起始地址（跳过 TTL header）
     *
     * @param dataAddress 数据起始地址（TTL header 位置）
     * @return 实际数据的起始地址
     */
    public static long getDataAddress(long dataAddress) {
        return dataAddress + TTL_HEADER_SIZE;
    }

    /**
     * 计算包含 TTL header 的总数据大小
     *
     * @param dataSize 实际数据大小
     * @return 包含 TTL header 的总大小
     */
    public static int totalSize(int dataSize) {
        return TTL_HEADER_SIZE + dataSize;
    }

    /**
     * 检查数据是否过期（从内存读取过期时间并检查）
     *
     * @param dataAddress 数据起始地址（TTL header 位置）
     * @return true 如果已过期，false 如果未过期或永不过期
     */
    public static boolean isDataExpired(long dataAddress) {
        long expireTime = readExpireTime(dataAddress);
        return isExpired(expireTime);
    }
}
