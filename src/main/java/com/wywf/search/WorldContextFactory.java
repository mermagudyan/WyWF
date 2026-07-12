package com.wywf.search;

public interface WorldContextFactory {

    WorldContext create(long seed, boolean accurateRings);

    net.minecraft.world.level.biome.Climate.Sampler samplerFor(long seed);

    boolean isStructureAvailable(String structureId);

    boolean isBiomeAvailable(String biomeId);
}
