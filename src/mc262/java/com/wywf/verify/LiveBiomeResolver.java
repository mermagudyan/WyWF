package com.wywf.verify;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;

interface LiveBiomeResolver {
    Holder<Biome> get(int x, int y, int z);
    static LiveBiomeResolver create(BiomeSource source, Climate.Sampler sampler) {
        return (x, y, z) -> source.getNoiseBiome(x, y, z, sampler);
    }
}
