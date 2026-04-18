package com.yomahub.roguemap.memory;

import java.util.*;

public class SearchOptions {
    private final String namespace;
    private final Map<String, List<Filter>> filters;
    private final int rrfConstant;

    private SearchOptions(Builder b) {
        this.namespace = b.namespace;
        Map<String, List<Filter>> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<Filter>> e : b.filters.entrySet()) {
            map.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.filters = Collections.unmodifiableMap(map);
        this.rrfConstant = b.rrfConstant;
    }

    public String getNamespace() { return namespace; }
    public Map<String, List<Filter>> getFilters() { return filters; }
    public int getRrfConstant() { return rrfConstant; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String namespace = null;
        private final Map<String, List<Filter>> filters = new LinkedHashMap<>();
        private int rrfConstant = 60;

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder filter(String key, String value) {
            return filter(key, Filter.eq(value));
        }

        public Builder filter(String key, Filter filter) {
            this.filters.computeIfAbsent(key, k -> new ArrayList<>()).add(filter);
            return this;
        }

        public Builder rrfConstant(int c) {
            this.rrfConstant = c;
            return this;
        }

        public SearchOptions build() { return new SearchOptions(this); }
    }
}
