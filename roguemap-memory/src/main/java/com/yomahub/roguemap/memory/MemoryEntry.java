package com.yomahub.roguemap.memory;

import java.util.Collections;
import java.util.Map;

/** 一条记忆的完整数据，包括原始向量（内部使用） */
public class MemoryEntry {
    private final String id;
    private final String content;
    private final Map<String, String> metadata;
    private final String namespace;
    private final long createdAt;
    private final long expireTime;    // 0 = 永不过期
    private final float[] vector;     // 原始 embedding，可为 null（KEYWORD_ONLY 模式）

    public MemoryEntry(String id, String content, Map<String, String> metadata,
                       String namespace, long createdAt, long expireTime, float[] vector) {
        this.id = id;
        this.content = content;
        this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : null;
        this.namespace = namespace;
        this.createdAt = createdAt;
        this.expireTime = expireTime;
        this.vector = vector;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public Map<String, String> getMetadata() { return metadata; }
    public String getNamespace() { return namespace; }
    public long getCreatedAt() { return createdAt; }
    public long getExpireTime() { return expireTime; }
    public float[] getVector() { return vector; }

    public boolean isExpired() {
        return expireTime > 0 && System.currentTimeMillis() > expireTime;
    }
}
