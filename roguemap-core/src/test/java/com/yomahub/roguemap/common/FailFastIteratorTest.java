package com.yomahub.roguemap.common;

import com.yomahub.roguemap.RogueList;
import com.yomahub.roguemap.RogueSet;
import com.yomahub.roguemap.serialization.PrimitiveCodecs;
import com.yomahub.roguemap.serialization.StringCodec;
import org.junit.jupiter.api.Test;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.ListIterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迭代器 fail-fast 测试
 *
 * 验证 RogueSet 和 RogueList 的迭代器在遍历期间检测到并发修改时
 * 抛出 ConcurrentModificationException。
 */
public class FailFastIteratorTest {

    // ========== RogueSet fail-fast 测试 ==========

    @Test
    public void testSetIteratorFailsOnAddDuringIteration() {
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                set.add(i);
            }

            Iterator<Long> it = set.iterator();
            assertTrue(it.hasNext());
            it.next(); // 正常读取第一个

            // 在迭代中添加元素
            set.add(100L);

            // 再次调用 next() 应抛出 ConcurrentModificationException
            assertThrows(ConcurrentModificationException.class, it::next);
        } finally {
            set.close();
        }
    }

    @Test
    public void testSetIteratorFailsOnRemoveDuringIteration() {
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                set.add(i);
            }

            Iterator<Long> it = set.iterator();
            assertTrue(it.hasNext());
            it.next();

            // 在迭代中移除元素
            set.remove(5L);

            assertThrows(ConcurrentModificationException.class, it::next);
        } finally {
            set.close();
        }
    }

    @Test
    public void testSetIteratorFailsOnClearDuringIteration() {
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                set.add(i);
            }

            Iterator<Long> it = set.iterator();
            it.next();

            set.clear();

            assertThrows(ConcurrentModificationException.class, it::next);
        } finally {
            set.close();
        }
    }

    @Test
    public void testSetIteratorSucceedsWithoutModification() {
        RogueSet<Long> set = RogueSet.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 100; i++) {
                set.add(i);
            }

            int count = 0;
            for (Long value : set) {
                assertNotNull(value);
                count++;
            }
            assertEquals(100, count);
        } finally {
            set.close();
        }
    }

    // ========== RogueList fail-fast 测试 ==========

    @Test
    public void testListIteratorFailsOnAddLastDuringIteration() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                list.addLast(i);
            }

            Iterator<Long> it = list.iterator();
            assertTrue(it.hasNext());
            it.next();

            list.addLast(100L);

            assertThrows(ConcurrentModificationException.class, it::next);
        } finally {
            list.close();
        }
    }

    @Test
    public void testListIteratorFailsOnRemoveLastDuringIteration() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                list.addLast(i);
            }

            Iterator<Long> it = list.iterator();
            it.next();

            list.removeLast();

            assertThrows(ConcurrentModificationException.class, it::next);
        } finally {
            list.close();
        }
    }

    @Test
    public void testListIteratorFailsOnAddFirstDuringIteration() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                list.addLast(i);
            }

            ListIterator<Long> it = list.listIterator();
            it.next();

            list.addFirst(100L);

            assertThrows(ConcurrentModificationException.class, it::next);
        } finally {
            list.close();
        }
    }

    @Test
    public void testListIteratorPreviousFailsOnModification() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 10; i++) {
                list.addLast(i);
            }

            ListIterator<Long> it = list.listIterator(5);
            it.previous(); // 正常

            list.addLast(100L);

            assertThrows(ConcurrentModificationException.class, it::previous);
        } finally {
            list.close();
        }
    }

    @Test
    public void testListIteratorSucceedsWithoutModification() {
        RogueList<Long> list = RogueList.<Long>mmap()
                .temporary()
                .elementCodec(PrimitiveCodecs.LONG)
                .build();

        try {
            for (long i = 0; i < 100; i++) {
                list.addLast(i);
            }

            // 正向遍历
            int count = 0;
            for (Long value : list) {
                assertEquals(count, value.longValue());
                count++;
            }
            assertEquals(100, count);

            // 反向遍历
            ListIterator<Long> it = list.listIterator(100);
            count = 99;
            while (it.hasPrevious()) {
                assertEquals(count, it.previous().longValue());
                count--;
            }
            assertEquals(-1, count);
        } finally {
            list.close();
        }
    }
}
