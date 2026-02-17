package com.yomahub.roguemap.set;

import com.yomahub.roguemap.serialization.Codec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * RogueSet的迭代器实现
 *
 * 注意：迭代时会将所有元素加载到内存中
 */
public class SetIterator<E> implements Iterator<E> {

    private final List<E> elements;
    private int currentIndex;

    public SetIterator(SetIndex<E> index, Codec<E> elementCodec) {
        this.elements = new ArrayList<>();
        this.currentIndex = 0;

        // 收集所有元素
        index.forEach((element, address, size) -> {
            E decoded = elementCodec.decode(address);
            elements.add(decoded);
        });
    }

    @Override
    public boolean hasNext() {
        return currentIndex < elements.size();
    }

    @Override
    public E next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        return elements.get(currentIndex++);
    }
}
