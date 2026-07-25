package com.wywf.core;

import java.util.List;
import java.util.Map;

public final class SearchResult {

    public final long seed;
    public final int  centerX;
    public final int  centerZ;
    public final int  structureX;
    public final int  structureZ;
    public final String primaryDescription;
    public final List<String> matchedStructures;
    public final List<String> matchedBiomes;
    public final String stopReason;
    public final Map<String, int[]> structurePositions;
    public final Map<String, Integer> biomeDistances;

    public SearchResult(long seed, int centerX, int centerZ,
                        int structureX, int structureZ,
                        String primaryDescription,
                        List<String> matchedStructures, List<String> matchedBiomes, String stopReason) {
        this(seed, centerX, centerZ, structureX, structureZ, primaryDescription,
                matchedStructures, matchedBiomes, stopReason, Map.of(), Map.of());
    }

    public SearchResult(long seed, int centerX, int centerZ,
                        int structureX, int structureZ,
                        String primaryDescription,
                        List<String> matchedStructures, List<String> matchedBiomes,
                        String stopReason, Map<String, int[]> structurePositions) {
        this(seed, centerX, centerZ, structureX, structureZ, primaryDescription,
                matchedStructures, matchedBiomes, stopReason, structurePositions, Map.of());
    }

    public SearchResult(long seed, int centerX, int centerZ,
                        int structureX, int structureZ,
                        String primaryDescription,
                        List<String> matchedStructures, List<String> matchedBiomes,
                        String stopReason, Map<String, int[]> structurePositions,
                        Map<String, Integer> biomeDistances) {
        this.seed = seed;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.structureX = structureX;
        this.structureZ = structureZ;
        this.primaryDescription = primaryDescription;
        this.matchedStructures = List.copyOf(matchedStructures);
        this.matchedBiomes = List.copyOf(matchedBiomes);
        this.stopReason = stopReason;
        this.structurePositions = Map.copyOf(structurePositions);
        this.biomeDistances = Map.copyOf(biomeDistances);
    }

    /** Distance from the search center (origin) to the nearest structure position. */
    public double distanceToStructure() {
        double dx = structureX - centerX;
        double dz = structureZ - centerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override public String toString() {
        return "SearchResult{seed=" + seed +
                ", center=(" + centerX + "," + centerZ + ")" +
                ", struct=(" + structureX + "," + structureZ + ")" +
                ", desc='" + primaryDescription + "'" +
                ", structures=" + matchedStructures +
                ", biomes=" + matchedBiomes +
                ", stop='" + stopReason + "'}";
    }
}
