package com.yomahub.roguemap.embedding;

public interface EmbeddingProvider {
    /**
     * Convert text to embedding vector
     * @param text input text
     * @return embedding vector (float array)
     */
    float[] embed(String text);

    /**
     * Return vector dimension, used when building HNSW index
     */
    int getDimension();
}
