package com.wywf.search;

import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

// Per-seed vanilla climate sampling on the 26.3 density API
final class ReusableClimateSampler {

    private final NoiseGeneratorSettings settings;
    private final HolderGetter<NormalNoise> noises;
    private RandomState state;
    private Climate.Sampler sampler;
    private long currentSeed;
    private boolean seeded;

    ReusableClimateSampler(NoiseGeneratorSettings settings,
                           HolderGetter<NormalNoise> noises) {
        this.settings = settings;
        this.noises = noises;
        reseed(0L);
    }

    Climate.Sampler sampler() {
        return sampler;
    }

    RandomState state() {
        return state;
    }

    void reseed(long seed) {
        if (seeded && seed == currentSeed) return;
        state = RandomState.create(noises, seed, settings);
        sampler = state.createClimateSampler(SamplerContext.builder().enableCaches().build());
        currentSeed = seed;
        seeded = true;
    }
}
