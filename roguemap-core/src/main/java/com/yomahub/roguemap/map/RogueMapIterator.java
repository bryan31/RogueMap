package com.yomahub.roguemap.map;

import com.yomahub.roguemap.index.Index;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.util.TTLUtils;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * RogueMap 的键值对迭代器实现。
 *
 * <p>构造时通过 {@link Index#forEach} 对索引做一次快照，只把 <b>键</b> 和 <b>值地址</b>
 * 拉到堆上（值本身不解码），随后在 {@link #next()} 中按需惰性解码每个值。这样可以避免一次性
 * 把所有值都加载到 JVM 堆，保留堆外存储的内存优势。
 *
 * <p>语义与 {@link com.yomahub.roguemap.RogueMap#forEach} 一致：已过期的条目在快照阶段被跳过。
 *
 * <p><b>遍历期间不应修改 map</b>（添加 / 更新 / 删除条目）。由于底层为 append-only 分配器，
 * 即便条目在遍历过程中被删除，快照地址上的旧数据仍然可安全读取（不会越界），但读到的是删除前的旧值。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class RogueMapIterator<K, V> implements Iterator<Map.Entry<K, V>> {

    private final Codec<V> valueCodec;
    private final List<K> keys;
    private final long[] addresses;
    private int idx;

    @SuppressWarnings("unchecked")
    public RogueMapIterator(Index<K> index, Codec<V> valueCodec) {
        this.valueCodec = valueCodec;

        int hint = Math.max(16, index.size());
        List<K> keyList = new ArrayList<>(hint);
        List<Long> addrList = new ArrayList<>(hint);
        index.forEach((key, address, size) -> {
            // 跳过已过期条目，与 RogueMap.forEach 语义保持一致
            if (TTLUtils.isDataExpired(address)) {
                return;
            }
            keyList.add((K) key);
            addrList.add(address);
        });

        this.keys = keyList;
        this.addresses = new long[addrList.size()];
        for (int i = 0; i < addrList.size(); i++) {
            this.addresses[i] = addrList.get(i);
        }
        this.idx = 0;
    }

    @Override
    public boolean hasNext() {
        return idx < keys.size();
    }

    @Override
    public Map.Entry<K, V> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        K key = keys.get(idx);
        long address = addresses[idx];
        idx++;
        // 惰性解码：仅在真正访问该条目时才反序列化值
        V value = valueCodec.decode(TTLUtils.getDataAddress(address));
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
