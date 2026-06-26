package com.yomahub.roguemap.index;

import com.yomahub.roguemap.memory.AddressTranslator;
import com.yomahub.roguemap.memory.MmapAllocator;
import com.yomahub.roguemap.memory.UnsafeOps;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * 超低堆占用 String 索引。
 *
 * <p>槽位表与 key 字节都存放在 mmap 区域，堆上仅保留段元数据与锁对象。
 */
public class LowHeapStringIndex implements Index<String> {

    private static final int SLOT_SIZE = 32;
    private static final int SLOT_FP_OFFSET = 0;
    private static final int SLOT_KEY_OFFSET = 8;
    private static final int SLOT_VALUE_OFFSET = 16;
    private static final int SLOT_VALUE_SIZE = 24;
    private static final int SLOT_STATE = 28;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_USED = 1;
    private static final int STATE_DELETED = 2;

    private static final int FORMAT_VERSION = 1;

    private final Segment[] segments;
    private final int segmentMask;
    private final AtomicInteger size = new AtomicInteger(0);
    private final MmapAllocator allocator;
    private final LowHeapOptions options;
    private final ThreadLocal<EncoderBuffer> encoderBuffer = ThreadLocal.withInitial(EncoderBuffer::new);

    private final AtomicLong slotBytesAllocated = new AtomicLong(0);
    private final AtomicLong activeKeyBytes = new AtomicLong(0);

    public LowHeapStringIndex(MmapAllocator allocator, LowHeapOptions options, int initialCapacityPerSegment) {
        if (allocator == null) {
            throw new IllegalArgumentException("allocator 不能为 null");
        }
        this.allocator = allocator;
        this.options = options != null ? options : LowHeapOptions.defaults();

        int segmentCount = this.options.getSegmentCount();
        this.segments = new Segment[segmentCount];
        this.segmentMask = segmentCount - 1;

        int perSegmentCapacity = Math.max(16, tableSizeFor(initialCapacityPerSegment));
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new Segment(allocateSlots(perSegmentCapacity), perSegmentCapacity);
        }
    }

    @Override
    public long put(String key, long address, int valueSize) {
        IndexUpdateResult result = putAndGetOld(key, address, valueSize);
        return result.wasPresent ? result.oldAddress : 0;
    }

    @Override
    public long get(String key) {
        if (key == null) {
            return 0;
        }

        EncodedKey encoded = encodeKey(key);
        Segment segment = getSegment(encoded.hash);

        long stamp = segment.lock.tryOptimisticRead();
        long valueFileOffset = findValueOffset(segment, encoded);
        if (!segment.lock.validate(stamp)) {
            stamp = segment.lock.readLock();
            try {
                valueFileOffset = findValueOffset(segment, encoded);
            } finally {
                segment.lock.unlockRead(stamp);
            }
        }

        return valueFileOffset == 0 ? 0 : allocator.getAddressForOffsetFast(valueFileOffset);
    }

    @Override
    public int getSize(String key) {
        if (key == null) {
            return -1;
        }

        EncodedKey encoded = encodeKey(key);
        Segment segment = getSegment(encoded.hash);

        long stamp = segment.lock.tryOptimisticRead();
        int valueSize = findValueSize(segment, encoded);
        if (!segment.lock.validate(stamp)) {
            stamp = segment.lock.readLock();
            try {
                valueSize = findValueSize(segment, encoded);
            } finally {
                segment.lock.unlockRead(stamp);
            }
        }

        return valueSize;
    }

    @Override
    public long remove(String key) {
        IndexRemoveResult result = removeAndGet(key);
        return result.wasPresent ? result.address : 0;
    }

    @Override
    public IndexUpdateResult putAndGetOld(String key, long newAddress, int newSize) {
        if (key == null) {
            throw new IllegalArgumentException("键不能为 null");
        }
        if (newAddress == 0) {
            throw new IllegalArgumentException("无效地址: 0");
        }

        EncodedKey encoded = encodeKey(key);
        Segment segment = getSegment(encoded.hash);
        long newValueOffset = allocator.getFileOffsetForAddressFast(newAddress);

        long stamp = segment.lock.writeLock();
        try {
            while (true) {
                if (shouldResize(segment)) {
                    resize(segment, segment.capacity << 1);
                }

                int firstDeleted = -1;
                int slotIdx = initialSlot(encoded.hash, segment.capacity);
                boolean resizeForProbe = false;

                for (int probe = 0; probe < segment.capacity; probe++) {
                    if (probe >= options.getMaxProbe()) {
                        resizeForProbe = true;
                        break;
                    }

                    long slotAddr = slotAddress(segment, slotIdx);
                    int state = UnsafeOps.getInt(slotAddr + SLOT_STATE);

                    if (state == STATE_EMPTY) {
                        int target = firstDeleted >= 0 ? firstDeleted : slotIdx;
                        long targetAddr = slotAddress(segment, target);

                        long keyOffset = writeKeyRecord(encoded.bytes, encoded.length);
                        writeUsedSlot(targetAddr, encoded.hash, keyOffset, newValueOffset, newSize);

                        segment.size++;
                        if (firstDeleted < 0) {
                            segment.used++;
                        }
                        size.incrementAndGet();

                        activeKeyBytes.addAndGet(4L + encoded.length);
                        return IndexUpdateResult.noOldValue();
                    }

                    if (state == STATE_DELETED) {
                        if (firstDeleted < 0) {
                            firstDeleted = slotIdx;
                        }
                    } else if (state == STATE_USED) {
                        long slotHash = UnsafeOps.getLong(slotAddr + SLOT_FP_OFFSET);
                        if (slotHash == encoded.hash) {
                            long keyOffset = UnsafeOps.getLong(slotAddr + SLOT_KEY_OFFSET);
                            if (keyEquals(keyOffset, encoded.bytes, encoded.length)) {
                                long oldValueOffset = UnsafeOps.getLong(slotAddr + SLOT_VALUE_OFFSET);
                                int oldSize = UnsafeOps.getInt(slotAddr + SLOT_VALUE_SIZE);

                                UnsafeOps.putLong(slotAddr + SLOT_VALUE_OFFSET, newValueOffset);
                                UnsafeOps.putInt(slotAddr + SLOT_VALUE_SIZE, newSize);

                                return IndexUpdateResult.withOldValue(
                                        oldValueOffset == 0 ? 0 : allocator.getAddressForOffsetFast(oldValueOffset),
                                        oldSize);
                            }
                        }
                    }

                    slotIdx = nextSlot(slotIdx, segment.capacity);
                }

                if (resizeForProbe) {
                    resize(segment, segment.capacity << 1);
                    continue;
                }

                if (firstDeleted >= 0) {
                    long targetAddr = slotAddress(segment, firstDeleted);
                    long keyOffset = writeKeyRecord(encoded.bytes, encoded.length);
                    writeUsedSlot(targetAddr, encoded.hash, keyOffset, newValueOffset, newSize);

                    segment.size++;
                    size.incrementAndGet();
                    activeKeyBytes.addAndGet(4L + encoded.length);
                    return IndexUpdateResult.noOldValue();
                }

                resize(segment, segment.capacity << 1);
            }
        } finally {
            segment.lock.unlockWrite(stamp);
        }
    }

    @Override
    public IndexRemoveResult removeAndGet(String key) {
        if (key == null) {
            return IndexRemoveResult.notPresent();
        }

        EncodedKey encoded = encodeKey(key);
        Segment segment = getSegment(encoded.hash);

        long stamp = segment.lock.writeLock();
        try {
            int slotIdx = initialSlot(encoded.hash, segment.capacity);
            for (int probe = 0; probe < segment.capacity; probe++) {
                long slotAddr = slotAddress(segment, slotIdx);
                int state = UnsafeOps.getInt(slotAddr + SLOT_STATE);

                if (state == STATE_EMPTY) {
                    return IndexRemoveResult.notPresent();
                }

                if (state == STATE_USED) {
                    long slotHash = UnsafeOps.getLong(slotAddr + SLOT_FP_OFFSET);
                    if (slotHash == encoded.hash) {
                        long keyOffset = UnsafeOps.getLong(slotAddr + SLOT_KEY_OFFSET);
                        if (keyEquals(keyOffset, encoded.bytes, encoded.length)) {
                            long valueOffset = UnsafeOps.getLong(slotAddr + SLOT_VALUE_OFFSET);
                            int valueSize = UnsafeOps.getInt(slotAddr + SLOT_VALUE_SIZE);

                            long keyAddr = allocator.getAddressForOffsetFast(keyOffset);
                            int keyLen = UnsafeOps.getInt(keyAddr);
                            activeKeyBytes.addAndGet(-(4L + keyLen));

                            markDeleted(slotAddr);
                            segment.size--;
                            size.decrementAndGet();

                            long oldAddress = valueOffset == 0 ? 0 : allocator.getAddressForOffsetFast(valueOffset);
                            return IndexRemoveResult.removed(oldAddress, valueSize);
                        }
                    }
                }

                slotIdx = nextSlot(slotIdx, segment.capacity);
            }

            return IndexRemoveResult.notPresent();
        } finally {
            segment.lock.unlockWrite(stamp);
        }
    }

    @Override
    public void forEach(IndexEntryConsumer consumer) {
        if (consumer == null) {
            return;
        }

        for (Segment segment : segments) {
            forEachSegmentInternal(segment, consumer);
        }
    }

    public void forEachSegment(int segmentIdx, IndexEntryConsumer consumer) {
        if (consumer == null) {
            return;
        }
        if (segmentIdx < 0 || segmentIdx >= segments.length) {
            throw new IllegalArgumentException("segmentIdx 越界: " + segmentIdx);
        }
        forEachSegmentInternal(segments[segmentIdx], consumer);
    }

    public int getSegmentCount() {
        return segments.length;
    }

    @Override
    public boolean containsKey(String key) {
        return get(key) != 0;
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public void clear() {
        for (Segment segment : segments) {
            long stamp = segment.lock.writeLock();
            try {
                UnsafeOps.setMemory(segment.slotsAddress, (long) segment.capacity * SLOT_SIZE, (byte) 0);
                segment.size = 0;
                segment.used = 0;
            } finally {
                segment.lock.unlockWrite(stamp);
            }
        }
        size.set(0);
        activeKeyBytes.set(0);
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public int serialize(long address) {
        return serializeWithOffsets(address, allocator);
    }

    @Override
    public void deserialize(long address, int totalSize) {
        deserializeWithOffsets(address, totalSize, allocator);
    }

    @Override
    public int serializedSize() {
        return 12 + size.get() * SLOT_SIZE;
    }

    @Override
    public int serializeWithOffsets(long address, AddressTranslator translator) {
        // 槽位中存储的是文件偏移量（put 时已通过 getFileOffsetForAddressFast 转换），
        // 跨会话稳定，因此此处直接原样拷贝，无需再用 translator 转换。
        long current = address;

        UnsafeOps.putInt(current, FORMAT_VERSION);
        current += 4;
        UnsafeOps.putInt(current, segments.length);
        current += 4;
        UnsafeOps.putInt(current, size.get());
        current += 4;

        for (Segment segment : segments) {
            long stamp = segment.lock.readLock();
            try {
                for (int i = 0; i < segment.capacity; i++) {
                    long slotAddr = slotAddress(segment, i);
                    if (UnsafeOps.getInt(slotAddr + SLOT_STATE) != STATE_USED) {
                        continue;
                    }
                    UnsafeOps.putLong(current + SLOT_FP_OFFSET, UnsafeOps.getLong(slotAddr + SLOT_FP_OFFSET));
                    UnsafeOps.putLong(current + SLOT_KEY_OFFSET, UnsafeOps.getLong(slotAddr + SLOT_KEY_OFFSET));
                    UnsafeOps.putLong(current + SLOT_VALUE_OFFSET, UnsafeOps.getLong(slotAddr + SLOT_VALUE_OFFSET));
                    UnsafeOps.putInt(current + SLOT_VALUE_SIZE, UnsafeOps.getInt(slotAddr + SLOT_VALUE_SIZE));
                    UnsafeOps.putInt(current + SLOT_STATE, STATE_USED);
                    current += SLOT_SIZE;
                }
            } finally {
                segment.lock.unlockRead(stamp);
            }
        }

        return (int) (current - address);
    }

    @Override
    public void deserializeWithOffsets(long address, int totalSize, AddressTranslator translator) {
        clear();

        long current = address;
        int version = UnsafeOps.getInt(current);
        current += 4;
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("LowHeapStringIndex 版本不兼容: " + version);
        }

        int segmentCount = UnsafeOps.getInt(current);
        current += 4;
        if (segmentCount != segments.length) {
            throw new IllegalStateException("segmentCount 不匹配: 期望 " + segments.length + "，实际 " + segmentCount);
        }

        int totalEntries = UnsafeOps.getInt(current);
        current += 4;

        for (int i = 0; i < totalEntries; i++) {
            long hash = UnsafeOps.getLong(current + SLOT_FP_OFFSET);
            long keyOffset = UnsafeOps.getLong(current + SLOT_KEY_OFFSET);
            long valueOffset = UnsafeOps.getLong(current + SLOT_VALUE_OFFSET);
            int valueSize = UnsafeOps.getInt(current + SLOT_VALUE_SIZE);
            int state = UnsafeOps.getInt(current + SLOT_STATE);
            current += SLOT_SIZE;

            if (state == STATE_USED) {
                insertSerialized(hash, keyOffset, valueOffset, valueSize);
            }
        }
    }

    public long estimateHeapBytes() {
        return segments.length * 256L + 1024L;
    }

    public long getIndexMmapBytes() {
        return slotBytesAllocated.get() + activeKeyBytes.get();
    }

    public double getAverageKeyBytes() {
        int currentSize = size.get();
        if (currentSize == 0) {
            return 0.0;
        }
        return (double) activeKeyBytes.get() / currentSize;
    }

    private void insertSerialized(long hash, long keyOffset, long valueOffset, int valueSize) {
        Segment segment = getSegment(hash);
        long stamp = segment.lock.writeLock();
        try {
            while (true) {
                if (shouldResize(segment)) {
                    resize(segment, segment.capacity << 1);
                }

                int slotIdx = initialSlot(hash, segment.capacity);
                int firstDeleted = -1;
                boolean resizeForProbe = false;

                for (int probe = 0; probe < segment.capacity; probe++) {
                    if (probe >= options.getMaxProbe()) {
                        resizeForProbe = true;
                        break;
                    }

                    long slotAddr = slotAddress(segment, slotIdx);
                    int state = UnsafeOps.getInt(slotAddr + SLOT_STATE);
                    if (state == STATE_EMPTY) {
                        int target = firstDeleted >= 0 ? firstDeleted : slotIdx;
                        long targetAddr = slotAddress(segment, target);
                        writeUsedSlot(targetAddr, hash, keyOffset, valueOffset, valueSize);

                        segment.size++;
                        if (firstDeleted < 0) {
                            segment.used++;
                        }
                        size.incrementAndGet();

                        long keyAddr = allocator.getAddressForOffsetFast(keyOffset);
                        int keyLen = UnsafeOps.getInt(keyAddr);
                        activeKeyBytes.addAndGet(4L + keyLen);
                        return;
                    }
                    if (state == STATE_DELETED && firstDeleted < 0) {
                        firstDeleted = slotIdx;
                    }
                    slotIdx = nextSlot(slotIdx, segment.capacity);
                }

                if (resizeForProbe) {
                    resize(segment, segment.capacity << 1);
                    continue;
                }

                if (firstDeleted >= 0) {
                    long targetAddr = slotAddress(segment, firstDeleted);
                    writeUsedSlot(targetAddr, hash, keyOffset, valueOffset, valueSize);
                    segment.size++;
                    size.incrementAndGet();

                    long keyAddr = allocator.getAddressForOffsetFast(keyOffset);
                    int keyLen = UnsafeOps.getInt(keyAddr);
                    activeKeyBytes.addAndGet(4L + keyLen);
                    return;
                }

                resize(segment, segment.capacity << 1);
            }
        } finally {
            segment.lock.unlockWrite(stamp);
        }
    }

    private long findValueOffset(Segment segment, EncodedKey encoded) {
        int slotIdx = initialSlot(encoded.hash, segment.capacity);
        for (int probe = 0; probe < segment.capacity; probe++) {
            long slotAddr = slotAddress(segment, slotIdx);
            int state = UnsafeOps.getInt(slotAddr + SLOT_STATE);

            if (state == STATE_EMPTY) {
                return 0;
            }
            if (state == STATE_USED) {
                long slotHash = UnsafeOps.getLong(slotAddr + SLOT_FP_OFFSET);
                if (slotHash == encoded.hash) {
                    long keyOffset = UnsafeOps.getLong(slotAddr + SLOT_KEY_OFFSET);
                    if (keyEquals(keyOffset, encoded.bytes, encoded.length)) {
                        return UnsafeOps.getLong(slotAddr + SLOT_VALUE_OFFSET);
                    }
                }
            }
            slotIdx = nextSlot(slotIdx, segment.capacity);
        }
        return 0;
    }

    private int findValueSize(Segment segment, EncodedKey encoded) {
        int slotIdx = initialSlot(encoded.hash, segment.capacity);
        for (int probe = 0; probe < segment.capacity; probe++) {
            long slotAddr = slotAddress(segment, slotIdx);
            int state = UnsafeOps.getInt(slotAddr + SLOT_STATE);

            if (state == STATE_EMPTY) {
                return -1;
            }
            if (state == STATE_USED) {
                long slotHash = UnsafeOps.getLong(slotAddr + SLOT_FP_OFFSET);
                if (slotHash == encoded.hash) {
                    long keyOffset = UnsafeOps.getLong(slotAddr + SLOT_KEY_OFFSET);
                    if (keyEquals(keyOffset, encoded.bytes, encoded.length)) {
                        return UnsafeOps.getInt(slotAddr + SLOT_VALUE_SIZE);
                    }
                }
            }
            slotIdx = nextSlot(slotIdx, segment.capacity);
        }
        return -1;
    }

    private long writeKeyRecord(byte[] bytes, int length) {
        int recordSize = 4 + length;
        long address = allocator.allocate(recordSize);
        if (address == 0) {
            throw new OutOfMemoryError("无法分配 key 记录: " + recordSize + " 字节");
        }

        UnsafeOps.putInt(address, length);
        if (length > 0) {
            UnsafeOps.copyFromArray(bytes, 0, address + 4, length);
        }

        return allocator.getFileOffsetForAddressFast(address);
    }

    private String decodeKey(long keyOffset) {
        long keyAddr = allocator.getAddressForOffsetFast(keyOffset);
        int len = UnsafeOps.getInt(keyAddr);
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        UnsafeOps.copyToArray(keyAddr + 4, bytes, 0, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean keyEquals(long keyOffset, byte[] bytes, int length) {
        long keyAddr = allocator.getAddressForOffsetFast(keyOffset);
        int keyLen = UnsafeOps.getInt(keyAddr);
        if (keyLen != length) {
            return false;
        }
        long keyBytesAddr = keyAddr + 4;
        for (int i = 0; i < length; i++) {
            if (UnsafeOps.getByte(keyBytesAddr + i) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldResize(Segment segment) {
        return segment.used >= (int) (segment.capacity * options.getLoadFactor());
    }

    private void resize(Segment segment, int newCapacity) {
        int targetCapacity = tableSizeFor(Math.max(16, newCapacity));
        long newSlotsAddress = allocateSlots(targetCapacity);

        for (int i = 0; i < segment.capacity; i++) {
            long oldSlotAddr = slotAddress(segment, i);
            if (UnsafeOps.getInt(oldSlotAddr + SLOT_STATE) != STATE_USED) {
                continue;
            }

            long hash = UnsafeOps.getLong(oldSlotAddr + SLOT_FP_OFFSET);
            long keyOffset = UnsafeOps.getLong(oldSlotAddr + SLOT_KEY_OFFSET);
            long valueOffset = UnsafeOps.getLong(oldSlotAddr + SLOT_VALUE_OFFSET);
            int valueSize = UnsafeOps.getInt(oldSlotAddr + SLOT_VALUE_SIZE);

            int slotIdx = initialSlot(hash, targetCapacity);
            while (true) {
                long newSlotAddr = newSlotsAddress + (long) slotIdx * SLOT_SIZE;
                int state = UnsafeOps.getInt(newSlotAddr + SLOT_STATE);
                if (state == STATE_EMPTY) {
                    writeUsedSlot(newSlotAddr, hash, keyOffset, valueOffset, valueSize);
                    break;
                }
                slotIdx = nextSlot(slotIdx, targetCapacity);
            }
        }

        segment.slotsAddress = newSlotsAddress;
        segment.capacity = targetCapacity;
        segment.used = segment.size;
    }

    private long allocateSlots(int capacity) {
        int bytes = capacity * SLOT_SIZE;
        long address = allocator.allocate(bytes);
        if (address == 0) {
            throw new OutOfMemoryError("无法分配索引槽位: " + bytes + " 字节");
        }
        UnsafeOps.setMemory(address, bytes, (byte) 0);
        slotBytesAllocated.addAndGet(bytes);
        return address;
    }

    private void writeUsedSlot(long slotAddr, long hash, long keyOffset, long valueOffset, int valueSize) {
        UnsafeOps.putLong(slotAddr + SLOT_FP_OFFSET, hash);
        UnsafeOps.putLong(slotAddr + SLOT_KEY_OFFSET, keyOffset);
        UnsafeOps.putLong(slotAddr + SLOT_VALUE_OFFSET, valueOffset);
        UnsafeOps.putInt(slotAddr + SLOT_VALUE_SIZE, valueSize);
        UnsafeOps.putInt(slotAddr + SLOT_STATE, STATE_USED);
    }

    private void forEachSegmentInternal(Segment segment, IndexEntryConsumer consumer) {
        long stamp = segment.lock.readLock();
        try {
            for (int i = 0; i < segment.capacity; i++) {
                long slotAddr = slotAddress(segment, i);
                if (UnsafeOps.getInt(slotAddr + SLOT_STATE) != STATE_USED) {
                    continue;
                }
                long keyOffset = UnsafeOps.getLong(slotAddr + SLOT_KEY_OFFSET);
                String key = decodeKey(keyOffset);

                long valueOffset = UnsafeOps.getLong(slotAddr + SLOT_VALUE_OFFSET);
                int valueSize = UnsafeOps.getInt(slotAddr + SLOT_VALUE_SIZE);
                long valueAddress = valueOffset == 0 ? 0 : allocator.getAddressForOffsetFast(valueOffset);

                consumer.accept(key, valueAddress, valueSize);
            }
        } finally {
            segment.lock.unlockRead(stamp);
        }
    }

    private void markDeleted(long slotAddr) {
        UnsafeOps.putLong(slotAddr + SLOT_FP_OFFSET, 0L);
        UnsafeOps.putLong(slotAddr + SLOT_KEY_OFFSET, 0L);
        UnsafeOps.putLong(slotAddr + SLOT_VALUE_OFFSET, 0L);
        UnsafeOps.putInt(slotAddr + SLOT_VALUE_SIZE, 0);
        UnsafeOps.putInt(slotAddr + SLOT_STATE, STATE_DELETED);
    }

    private Segment getSegment(long hash) {
        return segments[(int) hash & segmentMask];
    }

    private long slotAddress(Segment segment, int slotIdx) {
        return segment.slotsAddress + (long) slotIdx * SLOT_SIZE;
    }

    private int initialSlot(long hash, int capacity) {
        return spread(hash) & (capacity - 1);
    }

    private int nextSlot(int slotIdx, int capacity) {
        return (slotIdx + 1) & (capacity - 1);
    }

    private EncodedKey encodeKey(String key) {
        return encoderBuffer.get().encode(key);
    }

    private long hash64(byte[] bytes, int length) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < length; i++) {
            h ^= (bytes[i] & 0xff);
            h *= 0x100000001b3L;
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h == 0 ? 1 : h;
    }

    private int spread(long hash) {
        long h = hash ^ (hash >>> 32);
        h ^= (h >>> 16);
        return (int) h;
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

    private final class EncoderBuffer {
        private CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        private byte[] bytes = new byte[128];
        private ByteBuffer buffer = ByteBuffer.wrap(bytes);
        private final EncodedKey encodedKey = new EncodedKey();

        EncodedKey encode(String key) {
            int required = Math.max(32, key.length() * 4 + 8);
            ensureCapacity(required);

            while (true) {
                encoder.reset();
                buffer.clear();
                CharBuffer chars = CharBuffer.wrap(key);

                CoderResult result = encoder.encode(chars, buffer, true);
                if (result.isOverflow()) {
                    ensureCapacity(bytes.length << 1);
                    continue;
                }
                if (result.isError()) {
                    throw characterCoding(result);
                }

                result = encoder.flush(buffer);
                if (result.isOverflow()) {
                    ensureCapacity(bytes.length << 1);
                    continue;
                }
                if (result.isError()) {
                    throw characterCoding(result);
                }

                int len = buffer.position();
                encodedKey.bytes = bytes;
                encodedKey.length = len;
                encodedKey.hash = hash64(bytes, len);
                return encodedKey;
            }
        }

        private void ensureCapacity(int required) {
            if (bytes.length >= required) {
                return;
            }
            int newSize = bytes.length;
            while (newSize < required) {
                newSize <<= 1;
            }
            bytes = new byte[newSize];
            buffer = ByteBuffer.wrap(bytes);
        }

        private RuntimeException characterCoding(CoderResult result) {
            try {
                result.throwException();
            } catch (CharacterCodingException e) {
                return new RuntimeException("UTF-8 编码失败", e);
            }
            return new RuntimeException("UTF-8 编码失败");
        }
    }

    private static final class EncodedKey {
        private byte[] bytes;
        private int length;
        private long hash;
    }

    private static final class Segment {
        private final StampedLock lock;
        private long slotsAddress;
        private int capacity;
        private int size;
        private int used;

        private Segment(long slotsAddress, int capacity) {
            this.lock = new StampedLock();
            this.slotsAddress = slotsAddress;
            this.capacity = capacity;
            this.size = 0;
            this.used = 0;
        }
    }
}
