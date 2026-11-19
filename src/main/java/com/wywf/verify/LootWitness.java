package com.wywf.verify;

import java.util.Map;

public record LootWitness(String structure, int x, int y, int z, String table, long seed, Map<String, Integer> items, java.util.List<com.wywf.core.LootConstraint.Stack> stacks) {
    public String dimension() { return com.wywf.core.DimensionSpace.of(structure).id; }
    public LootWitness { items = Map.copyOf(items); stacks = java.util.List.copyOf(stacks); }
}