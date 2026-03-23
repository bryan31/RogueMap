package com.yomahub.roguemap.memory;

public enum SearchMode {
    /** 向量搜索 + BM25 关键词搜索，RRF 合并，效果最佳（默认） */
    HYBRID,
    /** 仅向量搜索，需要 EmbeddingProvider */
    VECTOR_ONLY,
    /** 仅 BM25 关键词搜索，不需要 EmbeddingProvider */
    KEYWORD_ONLY
}
