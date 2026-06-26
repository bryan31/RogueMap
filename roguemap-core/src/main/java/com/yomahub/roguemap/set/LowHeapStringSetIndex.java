package com.yomahub.roguemap.set;

import com.yomahub.roguemap.index.IndexRemoveResult;
import com.yomahub.roguemap.index.IndexUpdateResult;
import com.yomahub.roguemap.index.LowHeapOptions;
import com.yomahub.roguemap.index.LowHeapStringIndex;
import com.yomahub.roguemap.memory.MmapAllocator;

/**
 * Set 的低堆 String 索引实现。
 */
public class LowHeapStringSetIndex implements SetIndexStore<String> {

    private final LowHeapStringIndex delegate;
    private volatile int modCount = 0;

    public LowHeapStringSetIndex(MmapAllocator allocator, LowHeapOptions options, int initialCapacityPerSegment) {
        this.delegate = new LowHeapStringIndex(allocator, options, initialCapacityPerSegment);
    }

    @Override
    public SetAddResult add(String element, long address, int elementSize) {
        if (element == null) {
            throw new IllegalArgumentException("元素不能为 null");
        }
        IndexUpdateResult result = delegate.putAndGetOld(element, address, elementSize);
        if (result.wasPresent) {
            return SetAddResult.alreadyExists(result.oldAddress, result.oldSize);
        }
        modCount++;
        return SetAddResult.newlyAdded();
    }

    @Override
    public boolean contains(String element) {
        return element != null && delegate.containsKey(element);
    }

    @Override
    public long get(String element) {
        return element == null ? 0 : delegate.get(element);
    }

    @Override
    public int getSize(String element) {
        return element == null ? -1 : delegate.getSize(element);
    }

    @Override
    public SetRemoveResult remove(String element) {
        if (element == null) {
            return SetRemoveResult.notPresent();
        }
        IndexRemoveResult result = delegate.removeAndGet(element);
        if (!result.wasPresent) {
            return SetRemoveResult.notPresent();
        }
        modCount++;
        return SetRemoveResult.removed(result.address, result.size);
    }

    @Override
    public void forEach(SetEntryConsumer<String> consumer) {
        if (consumer == null) {
            return;
        }
        delegate.forEach((key, address, size) -> consumer.accept((String) key, address, size));
    }

    @Override
    public void forSegment(int segmentIdx, SetEntryConsumer<String> consumer) {
        if (consumer == null) {
            return;
        }
        delegate.forEachSegment(segmentIdx, (key, address, size) -> consumer.accept((String) key, address, size));
    }

    @Override
    public int getSegmentCount() {
        return delegate.getSegmentCount();
    }

    @Override
    public int getModCount() {
        return modCount;
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.size() == 0;
    }

    @Override
    public void clear() {
        delegate.clear();
        modCount++;
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public int serializedSize() {
        return delegate.serializedSize();
    }

    @Override
    public int serializeWithFileOffsets(long address, MmapAllocator mmapAllocator) {
        return delegate.serializeWithOffsets(address, mmapAllocator);
    }

    @Override
    public void deserializeWithFileOffsets(long address, int totalSize, MmapAllocator mmapAllocator) {
        delegate.deserializeWithOffsets(address, totalSize, mmapAllocator);
    }

    public long estimateHeapBytes() {
        return delegate.estimateHeapBytes();
    }

    public long getIndexMmapBytes() {
        return delegate.getIndexMmapBytes();
    }

    public double getAverageElementBytes() {
        return delegate.getAverageKeyBytes();
    }
}
