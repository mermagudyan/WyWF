package com.wywf.core;

import java.util.Set;

public enum DimensionSpace {
    OVERWORLD("minecraft:overworld"), NETHER("minecraft:the_nether"), END("minecraft:the_end");
    public static final int END_VOID_COLOR = 0xFF181318;
    public final String id;
    DimensionSpace(String id) { this.id = id; }
    private static final Set<String> NETHER_TARGETS = Set.of("fortress", "bastion", "bastion_remnant", "ruined_portal_nether",
            "nether_fossil", "nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest", "basalt_deltas");
    private static final Set<String> END_TARGETS = Set.of("end_city", "the_end", "small_end_islands", "end_midlands", "end_highlands", "end_barrens", "wywf:end_return_gateway");
    public static DimensionSpace of(String target) {
        String id = target.startsWith("minecraft:") ? target.substring(10) : target;
        return NETHER_TARGETS.contains(id) ? NETHER : END_TARGETS.contains(id) ? END : OVERWORLD;
    }
    public static boolean external(ParsedQuery.Term term) { return of(term.canonical) != OVERWORLD; }
    public int coordinate(int overworld) { return this == NETHER ? Math.floorDiv(overworld, 8) : overworld; }
}
