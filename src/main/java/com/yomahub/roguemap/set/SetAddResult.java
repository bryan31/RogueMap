package com.yomahub.roguemap.set;

/**
 * Set添加操作的结果
 */
public class SetAddResult {

    private final boolean newlyAdded;
    private final long oldAddress;
    private final int oldSize;

    private SetAddResult(boolean newlyAdded, long oldAddress, int oldSize) {
        this.newlyAdded = newlyAdded;
        this.oldAddress = oldAddress;
        this.oldSize = oldSize;
    }

    /**
     * 创建新增成功的结果
     */
    public static SetAddResult newlyAdded() {
        return new SetAddResult(true, 0, 0);
    }

    /**
     * 创建元素已存在的结果
     */
    public static SetAddResult alreadyExists(long oldAddress, int oldSize) {
        return new SetAddResult(false, oldAddress, oldSize);
    }

    /**
     * 是否新增添加（之前不存在）
     */
    public boolean isNewlyAdded() {
        return newlyAdded;
    }

    /**
     * 是否已存在
     */
    public boolean wasPresent() {
        return !newlyAdded;
    }

    /**
     * 获取旧值的地址（如果存在）
     */
    public long getOldAddress() {
        return oldAddress;
    }

    /**
     * 获取旧值的大小（如果存在）
     */
    public int getOldSize() {
        return oldSize;
    }

    @Override
    public String toString() {
        return "SetAddResult{" +
                "newlyAdded=" + newlyAdded +
                ", oldAddress=" + oldAddress +
                ", oldSize=" + oldSize +
                '}';
    }
}
