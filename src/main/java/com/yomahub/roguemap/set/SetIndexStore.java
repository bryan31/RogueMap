package com.yomahub.roguemap.set;

import com.yomahub.roguemap.memory.MmapAllocator;

/**
 * Set 索引抽象，支持不同索引实现（普通堆索引、低堆索引）。
 */
public interface SetIndexStore<E> {

    SetAddResult add(E element, long address, int elementSize);

    boolean contains(E element);

    long get(E element);

    int getSize(E element);

    SetRemoveResult remove(E element);

    void forEach(SetEntryConsumer<E> consumer);

    void forSegment(int segmentIdx, SetEntryConsumer<E> consumer);

    int getSegmentCount();

    int getModCount();

    int size();

    boolean isEmpty();

    void clear();

    void close();

    int serializedSize();

    int serializeWithFileOffsets(long address, MmapAllocator mmapAllocator);

    void deserializeWithFileOffsets(long address, int totalSize, MmapAllocator mmapAllocator);
}
