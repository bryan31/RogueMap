package com.yomahub.roguemap.memory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slab 内存分配器，用于高效的堆外内存管理
 * <p>
 * 该分配器通过将分配分组到大小类别来减少碎片。
 * 每个大小类别维护一个空闲内存块池，用于快速分配/释放。
 */
public class SlabAllocator implements Allocator {

    private static final int[] SIZE_CLASSES = {16, 64, 256, 1024, 4096, 16384};
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB 块

    private final Slab[] slabs;
    private final long maxMemory;
    private final AtomicLong totalAllocated;
    private final AtomicLong usedMemory;

    public SlabAllocator(long maxMemory) {
        if (maxMemory <= 0) {
            throw new IllegalArgumentException("maxMemory must be positive");
        }
        this.maxMemory = maxMemory;
        this.totalAllocated = new AtomicLong(0);
        this.usedMemory = new AtomicLong(0);
        this.slabs = new Slab[SIZE_CLASSES.length];
        for (int i = 0; i < SIZE_CLASSES.length; i++) {
            slabs[i] = new Slab(SIZE_CLASSES[i]);
        }
    }

    @Override
    public long allocate(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }

        // 检查内存限制
        if (usedMemory.get() + size > maxMemory) {
            return 0; // 内存不足
        }

        // 查找合适的 slab
        Slab slab = findSlab(size);
        if (slab != null) {
            long address = slab.allocate();
            if (address != 0) {
                usedMemory.addAndGet(slab.slabSize);
                return address;
            }
        }

        // 后备方案：对大对象直接分配
        if (size > SIZE_CLASSES[SIZE_CLASSES.length - 1]) {
            try {
                long address = UnsafeOps.allocate(size);
                totalAllocated.addAndGet(size);
                usedMemory.addAndGet(size);
                return address;
            } catch (OutOfMemoryError e) {
                return 0;
            }
        }

        return 0;
    }

    @Override
    public void free(long address, int size) {
        if (address == 0) {
            return;
        }

        Slab slab = findSlab(size);
        if (slab != null) {
            slab.free(address);
            usedMemory.addAndGet(-slab.slabSize);
        } else if (size > SIZE_CLASSES[SIZE_CLASSES.length - 1]) {
            // 直接释放大对象
            UnsafeOps.free(address);
            totalAllocated.addAndGet(-size);
            usedMemory.addAndGet(-size);
        }
    }

    @Override
    public long totalAllocated() {
        long total = totalAllocated.get();
        for (Slab slab : slabs) {
            total += slab.totalAllocated();
        }
        return total;
    }

    @Override
    public long usedMemory() {
        return usedMemory.get();
    }

    @Override
    public long availableMemory() {
        return maxMemory - usedMemory.get();
    }

    @Override
    public void close() {
        for (Slab slab : slabs) {
            slab.close();
        }
    }

    private Slab findSlab(int size) {
        for (Slab slab : slabs) {
            if (size <= slab.slabSize) {
                return slab;
            }
        }
        return null;
    }

    /**
     * Slab 管理固定大小的内存块
     */
    private static class Slab {
        private final int slabSize;
        private final ConcurrentLinkedQueue<Long> freeList;
        private final AtomicLong totalAllocated;

        Slab(int slabSize) {
            this.slabSize = slabSize;
            this.freeList = new ConcurrentLinkedQueue<>();
            this.totalAllocated = new AtomicLong(0);
        }

        long allocate() {
            // 尝试从空闲列表重用
            Long address = freeList.poll();
            if (address != null) {
                return address;
            }

            // 分配新块
            try {
                long newAddress = UnsafeOps.allocate(slabSize);
                totalAllocated.addAndGet(slabSize);
                return newAddress;
            } catch (OutOfMemoryError e) {
                return 0;
            }
        }

        void free(long address) {
            // 返回到空闲列表以供重用
            freeList.offer(address);
        }

        long totalAllocated() {
            return totalAllocated.get();
        }

        void close() {
            // 释放空闲列表中的所有内存
            Long address;
            while ((address = freeList.poll()) != null) {
                UnsafeOps.free(address);
            }
        }
    }

    /**
     * 创建具有默认最大内存（1GB）的 SlabAllocator
     */
    public static SlabAllocator createDefault() {
        return new SlabAllocator(1024L * 1024 * 1024);
    }

    /**
     * 获取内存使用统计信息
     */
    public String getStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("SlabAllocator Stats:\n");
        sb.append("  Total Allocated: ").append(totalAllocated()).append(" bytes\n");
        sb.append("  Used Memory: ").append(usedMemory()).append(" bytes\n");
        sb.append("  Available Memory: ").append(availableMemory()).append(" bytes\n");
        sb.append("  Max Memory: ").append(maxMemory).append(" bytes\n");
        sb.append("  Utilization: ").append(String.format("%.2f%%", 100.0 * usedMemory() / maxMemory));
        return sb.toString();
    }
}
