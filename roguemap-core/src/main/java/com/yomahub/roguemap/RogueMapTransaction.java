package com.yomahub.roguemap;

import com.yomahub.roguemap.index.BatchEntry;
import com.yomahub.roguemap.index.IndexUpdateResult;
import com.yomahub.roguemap.index.SegmentedHashIndex;
import com.yomahub.roguemap.memory.Allocator;
import com.yomahub.roguemap.serialization.Codec;
import com.yomahub.roguemap.util.TTLUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RogueMap 事务实现。
 *
 * <p>提供对多个 key 操作的原子性保证：要么全部提交，要么全部回滚。
 *
 * <p>隔离级别：<b>Read Committed</b>。事务内调用 {@link RogueMap#get} 读取的是已提交数据，
 * 而非本事务尚未提交的写入。
 *
 * <p>使用示例：
 * <pre>{@code
 * try (RogueMap.Transaction<String, Long> txn = map.beginTransaction()) {
 *     txn.put("counter", 1L);
 *     txn.put("total", 100L);
 *     txn.remove("old-key");
 *     txn.commit();  // 原子提交
 * }  // 若未调用 commit()，close() 时自动 rollback
 * }</pre>
 *
 * <p>持久化：commit 后调用 {@link RogueMap#checkpoint()} 可保证崩溃安全。
 *
 * <p><b>限制</b>：
 * <ul>
 *   <li>仅支持 {@link SegmentedHashIndex}（默认索引）；
 *       其他 Index 实现不支持事务（调用 commit 会抛出异常）</li>
 *   <li>不支持跨进程或分布式事务</li>
 *   <li>无 WAL，commit 中途崩溃可能导致部分操作丢失</li>
 * </ul>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class RogueMapTransaction<K, V> implements AutoCloseable {

    private enum OpType { PUT, REMOVE }

    private static class PendingOp<V> {
        final OpType type;
        final V value;  // PUT 时有值，REMOVE 时为 null

        PendingOp(OpType type, V value) {
            this.type = type;
            this.value = value;
        }
    }

    private final RogueMap<K, V> map;
    private final SegmentedHashIndex<K> index;
    private final Allocator allocator;
    private final Codec<V> valueCodec;

    /** 缓冲区：保持插入顺序（LinkedHashMap），同一 key 后操作覆盖前操作 */
    private final LinkedHashMap<K, PendingOp<V>> buffer = new LinkedHashMap<>();
    private boolean committed = false;
    private boolean closed = false;

    RogueMapTransaction(RogueMap<K, V> map, SegmentedHashIndex<K> index,
                        Allocator allocator, Codec<V> valueCodec) {
        this.map = map;
        this.index = index;
        this.allocator = allocator;
        this.valueCodec = valueCodec;
    }

    /**
     * 在事务中写入键值对。
     * 若该 key 在此事务内之前有操作，后操作覆盖前操作。
     *
     * @param key   键（不能为 null）
     * @param value 值（不能为 null）
     */
    public void put(K key, V value) {
        checkState();
        if (key == null) throw new IllegalArgumentException("键不能为 null");
        if (value == null) throw new IllegalArgumentException("值不能为 null");
        buffer.put(key, new PendingOp<>(OpType.PUT, value));
    }

    /**
     * 在事务中删除键。
     * 若该 key 不存在，commit 时忽略（不抛异常）。
     *
     * @param key 键（不能为 null）
     */
    public void remove(K key) {
        checkState();
        if (key == null) throw new IllegalArgumentException("键不能为 null");
        buffer.put(key, new PendingOp<V>(OpType.REMOVE, null));
    }

    /**
     * 原子提交事务。
     *
     * <p>流程：
     * <ol>
     *   <li>在段锁外，为所有 PUT 操作预分配内存并编码</li>
     *   <li>将所有操作按 segmentIndex 升序排序（防止死锁）</li>
     *   <li>逐段加写锁，批量更新索引</li>
     *   <li>释放所有锁；释放被替换的旧值内存</li>
     * </ol>
     *
     * @throws IllegalStateException 若事务已提交或已关闭
     * @throws RuntimeException      若内存分配失败（空间不足）；此时已预分配的内存已释放
     */
    public void commit() {
        checkState();
        if (buffer.isEmpty()) {
            committed = true;
            return;
        }

        // ===== Phase 1：在锁外预分配所有 PUT 操作的内存 =====
        // key → 预分配地址（PUT 才有；REMOVE 为 0）
        List<BatchEntry<K>> batchOps = new ArrayList<>(buffer.size());
        // 记录预分配地址，方便回滚时释放
        List<long[]> preAllocated = new ArrayList<>(); // [0]=addr, [1]=size

        try {
            for (Map.Entry<K, PendingOp<V>> entry : buffer.entrySet()) {
                K key = entry.getKey();
                PendingOp<V> op = entry.getValue();

                if (op.type == OpType.PUT) {
                    int valueSize = valueCodec.calculateSize(op.value);
                    if (valueSize < 0) {
                        throw new IllegalStateException("无法确定值的大小：key=" + key);
                    }
                    // 分配包含 TTL header 的内存
                    int totalSize = TTLUtils.totalSize(valueSize);
                    long newAddress = allocator.allocate(totalSize);
                    if (newAddress == 0) {
                        throw new OutOfMemoryError("事务提交：分配 " + totalSize + " 字节失败，空间不足");
                    }
                    // 写入过期时间（0 表示永不过期）
                    TTLUtils.writeExpireTime(newAddress, 0);
                    // 编码数据到 TTL header 之后
                    int actualSize = valueCodec.encode(TTLUtils.getDataAddress(newAddress), op.value);
                    int actualTotalSize = TTLUtils.totalSize(actualSize);
                    preAllocated.add(new long[]{newAddress, actualTotalSize});
                    batchOps.add(BatchEntry.put(key, newAddress, actualTotalSize));
                } else {
                    batchOps.add(BatchEntry.remove(key));
                }
            }
        } catch (Exception e) {
            // 释放已预分配的内存，然后重新抛出
            for (long[] pair : preAllocated) {
                allocator.free(pair[0], (int) pair[1]);
            }
            throw new RuntimeException("事务提交失败（预分配阶段）：" + e.getMessage(), e);
        }

        // ===== Phase 2：按 segmentIndex 升序排序，原子提交 =====
        batchOps.sort(Comparator.comparingInt(op -> index.getSegmentIndex(op.key)));

        List<IndexUpdateResult> results;
        try {
            results = index.applyBatch(batchOps);
        } catch (Exception e) {
            // applyBatch 内部已回滚索引，此处释放预分配内存
            for (long[] pair : preAllocated) {
                allocator.free(pair[0], (int) pair[1]);
            }
            throw new RuntimeException("事务提交失败（批量提交阶段）：" + e.getMessage(), e);
        }

        // ===== Phase 3：释放被替换/删除的旧值内存 =====
        for (IndexUpdateResult result : results) {
            if (result != null && result.wasPresent) {
                allocator.free(result.oldAddress, result.oldSize);
            }
        }

        committed = true;
        buffer.clear();
    }

    /**
     * 回滚事务，丢弃所有缓冲的操作。
     * 若事务已提交，此操作无效。
     */
    public void rollback() {
        if (committed) return;
        buffer.clear();
        closed = true;
    }

    /**
     * 实现 AutoCloseable：若未提交则自动回滚。
     */
    @Override
    public void close() {
        if (!closed && !committed) {
            rollback();
        }
        closed = true;
    }

    /**
     * 获取事务缓冲区中的待写 key 数量（不含已提交或已回滚的）
     */
    public int pendingSize() {
        return buffer.size();
    }

    private void checkState() {
        if (committed) throw new IllegalStateException("事务已提交，不能继续操作");
        if (closed) throw new IllegalStateException("事务已关闭，不能继续操作");
    }
}
