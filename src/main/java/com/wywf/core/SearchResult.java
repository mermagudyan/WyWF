package com.wywf.core;

import java.util.List;

public final class SearchResult {

    public final long seed;
    public final int  centerX;
    public final int  centerZ;
    public final String primaryDescription;
    public final List<String> matchedStructures;
    public final List<String> matchedBiomes;
    public final String stopReason;

    public SearchResult(long seed, int centerX, int centerZ, String primaryDescription,
                      List<String> matchedStructures, List<String> matchedBiomes, String stopReason) {
        this.seed = seed;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.primaryDescription = primaryDescription;
        this.matchedStructures = List.copyOf(matchedStructures);
        this.matchedBiomes = List.copyOf(matchedBiomes);
        this.stopReason = stopReason;
    }

    @Override public String toString() {
        return "SearchResult{seed=" + seed +
                ", center=(" + centerX + "," + centerZ + ")" +
                ", desc='" + primaryDescription + "'" +
                ", structures=" + matchedStructures +
                ", biomes=" + matchedBiomes +
                ", stop='" + stopReason + "'}";
    }
}
