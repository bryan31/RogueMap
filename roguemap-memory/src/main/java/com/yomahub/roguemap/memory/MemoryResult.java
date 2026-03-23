package com.yomahub.roguemap.memory;

import java.util.Collections;
import java.util.Map;

/** 搜索结果 */
public class MemoryResult {
    private final String id;
    private final String content;
    private final Map<String, String> metadata;
    private final String namespace;
    private final float score;
    private final long createdAt;
    private final long expireTime;

    public MemoryResult(String id, String content, Map<String, String> metadata,
                        String namespace, float score, long createdAt, long expireTime) {
        this.id = id;
        this.content = content;
        this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : null;
        this.namespace = namespace;
        this.score = score;
        this.createdAt = createdAt;
        this.expireTime = expireTime;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public Map<String, String> getMetadata() { return metadata; }
    public String getNamespace() { return namespace; }
    public float getScore() { return score; }
    public long getCreatedAt() { return createdAt; }
    public long getExpireTime() { return expireTime; }

    @Override
    public String toString() {
        return "MemoryResult{id='" + id + "', score=" + score + ", content='" + content + "'}";
    }
}
