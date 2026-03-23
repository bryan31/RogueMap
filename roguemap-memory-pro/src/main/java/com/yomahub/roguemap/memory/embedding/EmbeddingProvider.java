package com.yomahub.roguemap.memory.embedding;

public interface EmbeddingProvider {
    /**
     * 将文本转换为向量
     * @param text 输入文本
     * @return 向量（float 数组）
     */
    float[] embed(String text);

    /**
     * 返回向量维度，构建 HNSW 索引时使用
     */
    int getDimension();
}
