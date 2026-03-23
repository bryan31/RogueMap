package com.yomahub.roguemap.list;

import com.yomahub.roguemap.serialization.Codec;

import java.util.ConcurrentModificationException;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/**
 * RogueList的双向迭代器实现
 */
public class RogueListIterator<E> implements java.util.ListIterator<E> {

    private final ListIndex index;
    private final Codec<E> elementCodec;
    private final long baseAddress;
    private final int expectedModCount;

    private int currentIndex;
    private int size;

    public RogueListIterator(ListIndex index, Codec<E> elementCodec, long baseAddress) {
        this(index, elementCodec, baseAddress, 0);
    }

    public RogueListIterator(ListIndex index, Codec<E> elementCodec, long baseAddress, int startIndex) {
        this.index = index;
        this.elementCodec = elementCodec;
        this.baseAddress = baseAddress;
        this.currentIndex = startIndex;
        this.size = index.size();
        this.expectedModCount = index.getModCount();
    }

    @Override
    public boolean hasNext() {
        return currentIndex < size;
    }

    @Override
    public E next() {
        if (index.getModCount() != expectedModCount) {
            throw new ConcurrentModificationException();
        }
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        long nodeOffset = index.get(currentIndex);
        if (nodeOffset == 0) {
            throw new NoSuchElementException("Invalid node offset");
        }

        long elementAddr = ListIndex.getElementAddress(nodeOffset);
        E element = elementCodec.decode(elementAddr);
        currentIndex++;
        return element;
    }

    @Override
    public boolean hasPrevious() {
        return currentIndex > 0;
    }

    @Override
    public E previous() {
        if (index.getModCount() != expectedModCount) {
            throw new ConcurrentModificationException();
        }
        if (!hasPrevious()) {
            throw new NoSuchElementException();
        }

        currentIndex--;
        long nodeOffset = index.get(currentIndex);
        if (nodeOffset == 0) {
            throw new NoSuchElementException("Invalid node offset");
        }

        long elementAddr = ListIndex.getElementAddress(nodeOffset);
        return elementCodec.decode(elementAddr);
    }

    @Override
    public int nextIndex() {
        return currentIndex;
    }

    @Override
    public int previousIndex() {
        return currentIndex - 1;
    }

    @Override
    public void set(E e) {
        throw new UnsupportedOperationException("ListIterator.set() not supported");
    }

    @Override
    public void add(E e) {
        throw new UnsupportedOperationException("ListIterator.add() not supported");
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("ListIterator.remove() not supported");
    }
}
