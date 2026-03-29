package com.yomahub.roguemap.memory.index;

public class ScoredOrdinal {
    public final int ordinal;
    public final double score;

    public ScoredOrdinal(int ordinal, double score) {
        this.ordinal = ordinal;
        this.score = score;
    }
}
