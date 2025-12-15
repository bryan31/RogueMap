package com.yomahub.roguemap.index;

import com.yomahub.roguemap.func.EntryConsumer;
import com.yomahub.roguemap.memory.UnsafeOps;
import java.util.concurrent.locks.StampedLock;

/**
 * 极致优化的Integer键索引 - 使用原始类型数组
 * <p>
 * 内存占用（100万条，负载因子0.75）：
 * - keys: 4 bytes × 1,333,333 = 5.3 MB
 * - addresses: 8 bytes × 1,333,333 = 10.7 MB
 * - sizes: 4 bytes × 1,333,333 = 5.3 MB
 * - 总计: ~21.3 MB（实际存储100万条约16MB）
 * <p>
 * 相比Long键索引，再节省25%内存
 */
public class IntPrimitiveIndex implements Index<Integer> {

    private static final int EMPTY_KEY = 0;
    private static final int DELETED_KEY = Integer.MIN_VALUE;
    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private int[] keys;            // 键数组（原始类型）
    private long[] addresses;      // 地址数组
    private int[] sizes;           // 大小数组
    private int size;              // 当前元素数量
    private int threshold;         // 扩容阈值
    private final StampedLock lock;

    public IntPrimitiveIndex() {
        this(DEFAULT_CAPACITY);
    }

    public IntPrimitiveIndex(int initialCapacity) {
        int capacity = tableSizeFor(initialCapacity);
        this.keys = new int[capacity];
        this.addresses = new long[capacity];
        this.sizes = new int[capacity];
        this.size = 0;
        this.threshold = (int) (capacity * LOAD_FACTOR);
        this.lock = new StampedLock();
    }

    @Override
    public long put(Integer key, long address, int valueSize) {
        if (key == null || key == EMPTY_KEY || key == DELETED_KEY) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        if (address == 0) {
            throw new IllegalArgumentException("Invalid address: 0");
        }

        long stamp = lock.writeLock();
        try {
            return putInternal(key, address, valueSize);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private long putInternal(int key, long address, int valueSize) {
        if (size >= threshold) {
            resize();
        }

        int index = findSlot(key);
        long oldAddress = addresses[index];

        if (keys[index] == EMPTY_KEY || keys[index] == DELETED_KEY) {
            size++;
        }

        keys[index] = key;
        addresses[index] = address;
        sizes[index] = valueSize;

        return oldAddress;
    }

    @Override
    public long get(Integer key) {
        if (key == null || key == EMPTY_KEY || key == DELETED_KEY) {
            return 0;
        }

        long stamp = lock.tryOptimisticRead();
        int index = probe(key);
        long addr = (index >= 0) ? addresses[index] : 0;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                index = probe(key);
                addr = (index >= 0) ? addresses[index] : 0;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return addr;
    }

    @Override
    public int getSize(Integer key) {
        if (key == null || key == EMPTY_KEY || key == DELETED_KEY) {
            return -1;
        }

        long stamp = lock.tryOptimisticRead();
        int index = probe(key);
        int sz = (index >= 0) ? sizes[index] : -1;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                index = probe(key);
                sz = (index >= 0) ? sizes[index] : -1;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return sz;
    }

    @Override
    public long remove(Integer key) {
        if (key == null || key == EMPTY_KEY || key == DELETED_KEY) {
            return 0;
        }

        long stamp = lock.writeLock();
        try {
            int index = probe(key);
            if (index < 0) {
                return 0;
            }

            long oldAddress = addresses[index];
            keys[index] = DELETED_KEY;
            addresses[index] = 0;
            sizes[index] = 0;
            size--;

            return oldAddress;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public boolean containsKey(Integer key) {
        return get(key) != 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        clear(null);
    }

    @Override
    public void clear(EntryConsumer action) {
        long stamp = lock.writeLock();
        try {
            if (action != null) {
                for (int i = 0; i < keys.length; i++) {
                    int key = keys[i];
                    if (key != EMPTY_KEY && key != DELETED_KEY) {
                        action.accept(addresses[i], sizes[i]);
                    }
                }
            }
            keys = new int[DEFAULT_CAPACITY];
            addresses = new long[DEFAULT_CAPACITY];
            sizes = new int[DEFAULT_CAPACITY];
            size = 0;
            threshold = (int) (DEFAULT_CAPACITY * LOAD_FACTOR);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public int serializedSize() {
        // 序列化格式：
        // - 4 bytes: entry count
        // - 对于每个有效 entry:
        //   - 4 bytes: key (int)
        //   - 8 bytes: offset (long)
        //   - 4 bytes: size (int)
        // 总计：4 + size * 16
        return 4 + size * 16;
    }

    @Override
    public int serialize(long address) {
        // 原始类型索引暂不支持序列化
        throw new UnsupportedOperationException("IntPrimitiveIndex does not support serialization temporarily");
    }

    @Override
    public void deserialize(long address, int size) {
        // 原始类型索引暂不支持序列化
        throw new UnsupportedOperationException("IntPrimitiveIndex does not support serialization temporarily");
    }

    @Override
    public void forEach(EntryConsumer action) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }
        long stamp = lock.readLock();
        try {
            for (int i = 0; i < keys.length; i++) {
                int key = keys[i];
                if (key != EMPTY_KEY && key != DELETED_KEY) {
                    long addr = addresses[i];
                    int sz = sizes[i];
                    action.accept(addr, sz);
                }
            }
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private int probe(int key) {
        int index = hash(key) & (keys.length - 1);
        int start = index;

        do {
            int k = keys[index];
            if (k == key) {
                return index;
            }
            if (k == EMPTY_KEY) {
                return -1;
            }
            index = (index + 1) & (keys.length - 1);
        } while (index != start);

        return -1;
    }

    private int findSlot(int key) {
        int index = hash(key) & (keys.length - 1);
        int start = index;
        int firstDeleted = -1;

        do {
            int k = keys[index];
            if (k == key) {
                return index;
            }
            if (k == EMPTY_KEY) {
                return firstDeleted >= 0 ? firstDeleted : index;
            }
            if (k == DELETED_KEY && firstDeleted < 0) {
                firstDeleted = index;
            }
            index = (index + 1) & (keys.length - 1);
        } while (index != start);

        return firstDeleted >= 0 ? firstDeleted : index;
    }

    private void resize() {
        int newCapacity = keys.length * 2;
        int[] oldKeys = keys;
        long[] oldAddresses = addresses;
        int[] oldSizes = sizes;

        keys = new int[newCapacity];
        addresses = new long[newCapacity];
        sizes = new int[newCapacity];
        threshold = (int) (newCapacity * LOAD_FACTOR);
        size = 0;

        for (int i = 0; i < oldKeys.length; i++) {
            int k = oldKeys[i];
            if (k != EMPTY_KEY && k != DELETED_KEY) {
                putInternal(k, oldAddresses[i], oldSizes[i]);
            }
        }
    }

    private int hash(int key) {
        int h = key;
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    @Override
    public int serializeWithOffsets(long address, long baseAddress) {
        long currentAddr = address;

        // 写入 entry count
        UnsafeOps.putInt(currentAddr, size);
        currentAddr += 4;

        // 写入每个有效 entry
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            if (key != EMPTY_KEY && key != DELETED_KEY) {
                long addr = addresses[i];
                if (addr != 0) {
                    // 写入 key
                    UnsafeOps.putInt(currentAddr, key);
                    currentAddr += 4;

                    // 写入相对偏移量
                    long offset = addr - baseAddress;
                    UnsafeOps.putLong(currentAddr, offset);
                    currentAddr += 8;

                    // 写入 size
                    UnsafeOps.putInt(currentAddr, sizes[i]);
                    currentAddr += 4;
                }
            }
        }

        return (int) (currentAddr - address);
    }

    @Override
    public void deserializeWithOffsets(long address, int totalSize, long baseAddress) {
        long currentAddr = address;

        // 读取 entry count
        int entryCount = UnsafeOps.getInt(currentAddr);
        currentAddr += 4;

        // 清空当前数据
        clear();

        // 读取每个 entry
        for (int i = 0; i < entryCount; i++) {
            // 读取 key
            int key = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 读取相对偏移量
            long offset = UnsafeOps.getLong(currentAddr);
            currentAddr += 8;

            // 重新计算绝对内存地址
            long addr = baseAddress + offset;

            // 读取 size
            int sz = UnsafeOps.getInt(currentAddr);
            currentAddr += 4;

            // 插入到表中
            put(key, addr, sz);
        }
    }

    private static int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : n + 1;
    }
}
