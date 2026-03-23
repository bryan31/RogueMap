package com.yomahub.roguemap.memory.index;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public interface VectorIndex {

    class ScoredId {
        public final String id;
        public final float score;
        public ScoredId(String id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    /** 添加向量 */
    void add(String id, float[] vector);

    /** 标记删除（tombstone；compact 时物理移除） */
    void markDeleted(String id);

    /**
     * 近似最近邻搜索，返回不包含已删除节点的结果
     * @param queryVector 查询向量
     * @param topK 返回结果数量
     */
    List<ScoredId> search(float[] queryVector, int topK);

    /**
     * 序列化到输出流。
     * 格式：[generation: 8 bytes long][deletedCount: 4 bytes int]
     *       [deletedId_1_len: 2 bytes][deletedId_1: UTF-8]...
     *       [hnswData: 剩余字节，由具体实现写入]
     */
    void serialize(OutputStream out) throws IOException;

    void close();
}
