package com.yomahub.roguemap.memory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SearchOptions {
    private final String namespace;                  // null = 搜索所有 namespace
    private final Map<String, String> filters;       // metadata 精确过滤，空 map = 不过滤
    private final int rrfConstant;                   // RRF 公式中的 C 值，默认 60

    private SearchOptions(Builder b) {
        this.namespace = b.namespace;
        this.filters = Collections.unmodifiableMap(b.filters);
        this.rrfConstant = b.rrfConstant;
    }

    public String getNamespace() { return namespace; }
    public Map<String, String> getFilters() { return filters; }
    public int getRrfConstant() { return rrfConstant; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String namespace = null;
        private final Map<String, String> filters = new HashMap<>();
        private int rrfConstant = 60;

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder filter(String key, String value) {
            this.filters.put(key, value);
            return this;
        }

        public Builder rrfConstant(int c) {
            this.rrfConstant = c;
            return this;
        }

        public SearchOptions build() { return new SearchOptions(this); }
    }
}
