package com.yomahub.roguemap.index;

import com.yomahub.roguemap.func.EntryConsumer;
import com.yomahub.roguemap.memory.UnsafeOps;
import com.yomahub.roguemap.serialization.Codec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用 ConcurrentHashMap 实现的基础哈希索引
 *
 * 存储键到内存地址的映射关系，内存地址指向值的存储位置。
 */
public class HashIndex<K> implements Index<K> {

    private final ConcurrentHashMap<K, Entry> map;
    private final AtomicInteger size;
    private final Codec<K> keyCodec;  // 用于序列化键

    public HashIndex() {
        this(null, 16);
    }

    public HashIndex(int initialCapacity) {
        this(null, initialCapacity);
    }

    public HashIndex(Codec<K> keyCodec) {
        this(keyCodec, 16);
    }

    public HashIndex(Codec<K> keyCodec, int initialCapacity) {
        this.map = new ConcurrentHashMap<>(initialCapacity);
        this.size = new AtomicInteger(0);
        this.keyCodec = keyCodec;
    }

    @Override
    public long put(K key, long address, int valueSize) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (address == 0) {
            throw new IllegalArgumentException("Invalid address: 0");
        }

        Entry newEntry = new Entry(address, valueSize);
        Entry oldEntry = map.put(key, newEntry);

        if (oldEntry == null) {
            size.incrementAndGet();
            return 0;
        } else {
            return oldEntry.address;
        }
    }

    @Override
    public long get(K key) {
        if (key == null) {
            return 0;
        }

        Entry entry = map.get(key);
        return entry != null ? entry.address : 0;
    }

    @Override
    public int getSize(K key) {
        if (key == null) {
            return -1;
        }

        Entry entry = map.get(key);
        return entry != null ? entry.size : -1;
    }

    @Override
    public long remove(K key) {
        if (key == null) {
            return 0;
        }

        Entry entry = map.remove(key);
        if (entry != null) {
            size.decrementAndGet();
            return entry.address;
        }
        return 0;
    }

    @Override
    public boolean containsKey(K key) {
        return key != null && map.containsKey(key);
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public void clear() {
        clear(null);
    }

    @Override
    public void clear(EntryConsumer action) {
        // 使用迭代器逐个移除，确保原子性和回调执行
        map.forEach((k, v) -> {
            if (map.remove(k, v)) { // 原子性移除
                size.decrementAndGet();
                if (action != null) {
                    action.accept(v.address, v.size);
                }
            }
        });
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public int serializedSize() {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Serialization not supported: keyCodec is null");
        }

        int totalSize = 4;  // entry count (4 bytes)

        for (Map.Entry<K, Entry> entry : map.entrySet()) {
            K key = entry.getKey();
            int keySize = keyCodec.calculateSize(key);
            if (keySize < 0) {
                throw new IllegalStateException("Key size cannot be negative");
            }
            // 4 bytes (key size) + key bytes + 8 bytes (address) + 4 bytes (size)
            totalSize += 4 + keySize + 8 + 4;
        }

        return totalSize;
    }

    @Override
    public int serialize(long address) {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Serialization not supported: keyCodec is null");
        }

        long currentAddr = address;

        // 写入 entry count
        UnsafeOps.putInt(currentAddr, map.size());
        currentAddr += 4;

        // 写入每个 entry
        for (Map.Entry<K, Entry> entry : map.entrySet()) {
            K key = entry.getKey();

            // 计算键大小并编码
            int keySize = keyCodec.calculateSize(key);
            if (keySize < 0) {
                throw new IllegalStateException("Key size cannot be negative");
            }

            // 写入 key size
            UnsafeOps.putInt(currentAddr, keySize);
            currentAddr += 4;

            // 写入 key bytes
            int actualKeySize = keyCodec.encode(currentAddr, key);
            currentAddr += actualKeySize;

            // 写入 address
            UnsafeOps.putLong(currentAddr, entry.getValue().address);
            currentAddr += 8;

            // 写入 size
            UnsafeOps.putInt(currentAddr, entry.getValue().size);
            currentAddr += 4;
        }

        return (int) (currentAddr - address);
    }

    @Override
    public void deserialize(long address, int totalSize) {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Deserialization not supported: keyCodec is null");
        }

        map.clear();
        long currentAddr = address;

        // 读取 entry count
        int entryCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 读取每个 entry
        for (int i = 0; i < entryCount; i++) {
            // 读取 key size
            int keySize = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 读取 key
            K key = keyCodec.decode(currentAddr);
            currentAddr += keySize;

            // 读取 address
            long addr = UnsafeOps.getLong(currentAddr);
            currentAddr += 8;

            // 读取 size
            int sz = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 插入到 map
            map.put(key, new Entry(addr, sz));
        }

        this.size.set(entryCount);
    }

    @Override
    public int serializeWithOffsets(long address, long baseAddress) {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Serialization not supported: keyCodec is null");
        }

        long currentAddr = address;

        // 写入 entry count
        UnsafeOps.putInt(currentAddr, map.size());
        currentAddr += 4;

        // 写入每个 entry
        for (Map.Entry<K, Entry> entry : map.entrySet()) {
            K key = entry.getKey();

            // 计算键大小并编码
            int keySize = keyCodec.calculateSize(key);
            if (keySize < 0) {
                throw new IllegalStateException("Key size cannot be negative");
            }

            // 写入 key size
            UnsafeOps.putInt(currentAddr, keySize);
            currentAddr += 4;

            // 写入 key bytes
            int actualKeySize = keyCodec.encode(currentAddr, key);
            currentAddr += actualKeySize;

            // 写入相对偏移量（而不是绝对地址）
            long offset = entry.getValue().address - baseAddress;
            UnsafeOps.putLong(currentAddr, offset);
            currentAddr += 8;

            // 写入 size
            UnsafeOps.putInt(currentAddr, entry.getValue().size);
            currentAddr += 4;
        }

        return (int) (currentAddr - address);
    }

    @Override
    public void deserializeWithOffsets(long address, int totalSize, long baseAddress) {
        if (keyCodec == null) {
            throw new UnsupportedOperationException("Deserialization not supported: keyCodec is null");
        }

        map.clear();
        long currentAddr = address;

        // 读取 entry count
        int entryCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 读取每个 entry
        for (int i = 0; i < entryCount; i++) {
            // 读取 key size
            int keySize = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 读取 key
            K key = keyCodec.decode(currentAddr);
            currentAddr += keySize;

            // 读取相对偏移量
            long offset = UnsafeOps.getLong(currentAddr);
            currentAddr += 8;

            // 重新计算绝对内存地址
            long addr = baseAddress + offset;

            // 读取 size
            int sz = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 插入到 map
            map.put(key, new Entry(addr, sz));
        }

        this.size.set(entryCount);
    }

    @Override
    public void forEach(EntryConsumer action) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }
        for (Entry entry : map.values()) {
            action.accept(entry.address, entry.size);
        }
    }

    /**
     * Entry 保存值的内存地址和大小
     */
    private static class Entry {
        final long address;
        final int size;

        Entry(long address, int size) {
            this.address = address;
            this.size = size;
        }
    }
}
