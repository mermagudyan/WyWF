package com.wywf.core;

public final class SearchResult {

    public final long seed;
    public final int  centerX;
    public final int  centerZ;
    public final String matchedDescription;

    public SearchResult(long seed, int centerX, int centerZ, String matchedDescription) {
        this.seed = seed;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.matchedDescription = matchedDescription;
    }

    @Override public String toString() {
        return "SearchResult{seed=" + seed +
                ", center=(" + centerX + "," + centerZ + ")" +
                ", desc='" + matchedDescription + "'}";
    }
}
