package com.yomahub.roguemap.set;

/**
 * Set移除操作的结果
 */
public class SetRemoveResult {

    private final boolean wasPresent;
    private final long address;
    private final int size;

    private SetRemoveResult(boolean wasPresent, long address, int size) {
        this.wasPresent = wasPresent;
        this.address = address;
        this.size = size;
    }

    /**
     * 创建元素不存在的结果
     */
    public static SetRemoveResult notPresent() {
        return new SetRemoveResult(false, 0, 0);
    }

    /**
     * 创建移除成功的结果
     */
    public static SetRemoveResult removed(long address, int size) {
        return new SetRemoveResult(true, address, size);
    }

    /**
     * 元素是否存在（是否成功移除）
     */
    public boolean wasPresent() {
        return wasPresent;
    }

    /**
     * 获取被移除元素的地址
     */
    public long getAddress() {
        return address;
    }

    /**
     * 获取被移除元素的大小
     */
    public int getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "SetRemoveResult{" +
                "wasPresent=" + wasPresent +
                ", address=" + address +
                ", size=" + size +
                '}';
    }
}
