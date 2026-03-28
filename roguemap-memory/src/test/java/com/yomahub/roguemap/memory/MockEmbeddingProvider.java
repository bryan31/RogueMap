package com.yomahub.roguemap.memory;

import com.yomahub.roguemap.embedding.EmbeddingProvider;
import java.util.Random;

/** 测试用，返回固定维度的伪随机向量（基于 text hashCode 保证同文本同向量） */
class MockEmbeddingProvider implements EmbeddingProvider {
    private final int dimension;

    MockEmbeddingProvider(int dimension) { this.dimension = dimension; }

    @Override
    public float[] embed(String text) {
        Random rng = new Random(text.hashCode());
        float[] v = new float[dimension];
        float norm = 0;
        for (int i = 0; i < dimension; i++) {
            v[i] = rng.nextFloat() * 2 - 1;
            norm += v[i] * v[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dimension; i++) v[i] /= norm;
        return v;
    }

    @Override
    public int getDimension() { return dimension; }
}
