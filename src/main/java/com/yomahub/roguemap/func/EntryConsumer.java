package com.yomahub.roguemap.func;

@FunctionalInterface
public interface EntryConsumer {
    void accept(long address, int size);
}