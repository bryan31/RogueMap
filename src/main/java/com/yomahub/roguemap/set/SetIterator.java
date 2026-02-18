package com.yomahub.roguemap.set;

import com.yomahub.roguemap.serialization.Codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * RogueSet的迭代器实现（分段懒加载）
 *
 * 每次只将一个 segment 的元素加载到堆内存，峰值内存从 O(N) 降为 O(N/segmentCount)。
 */
public class SetIterator<E> implements Iterator<E> {

    private final SetIndex<E> index;
    private final Codec<E> elementCodec;
    private final int totalSegments;

    private int currentSegment;
    private List<E> currentBatch;
    private int batchIdx;

    public SetIterator(SetIndex<E> index, Codec<E> elementCodec) {
        this.index = index;
        this.elementCodec = elementCodec;
        this.totalSegments = index.getSegmentCount();
        this.currentSegment = 0;
        this.currentBatch = Collections.emptyList();
        this.batchIdx = 0;

        loadNextNonEmptySegment();
    }

    private void loadNextNonEmptySegment() {
        while (currentSegment < totalSegments) {
            List<E> batch = new ArrayList<>();
            int seg = currentSegment++;
            index.forSegment(seg, (element, address, size) -> {
                batch.add(elementCodec.decode(address));
            });
            if (!batch.isEmpty()) {
                currentBatch = batch;
                batchIdx = 0;
                return;
            }
        }
        currentBatch = Collections.emptyList();
        batchIdx = 0;
    }

    @Override
    public boolean hasNext() {
        return batchIdx < currentBatch.size();
    }

    @Override
    public E next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        E element = currentBatch.get(batchIdx++);

        // 当前 batch 消耗完，预加载下一个非空 segment
        if (batchIdx >= currentBatch.size()) {
            loadNextNonEmptySegment();
        }

        return element;
    }
}
