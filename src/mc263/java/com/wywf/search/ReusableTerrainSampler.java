package com.wywf.search;

import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

// Final-density heights via one volume sample per column on the 26.3 density API
final class ReusableTerrainSampler {

    private final NoiseGeneratorSettings settings;
    private final HolderGetter<NormalNoise> noises;
    private final int minY;
    private final int sizeY;
    private DensitySampler.Bound finalBound;
    private DensityBuffer columnBuffer;
    private long currentSeed;
    private boolean seeded;

    ReusableTerrainSampler(NoiseGeneratorSettings settings,
                           HolderGetter<NormalNoise> noises) {
        this.settings = settings;
        this.noises = noises;
        NoiseSettings ns = settings.noiseSettings();
        this.minY = ns.minY();
        this.sizeY = ns.height() + 1;
        reseed(0L);
    }

    void reseed(long seed) {
        if (seeded && seed == currentSeed) return;
        RandomState state = RandomState.create(noises, seed, settings);
        SamplerContext ctx = SamplerContext.builder().enableCaches().build();
        finalBound = state.getSampler(settings.noiseRouter().finalDensity()).bind(ctx);
        columnBuffer = DensityBuffer.createUnpooled(sizeY);
        currentSeed = seed;
        seeded = true;
    }

    // Top solid-block Y at (blockX, blockZ): one volume sample, scan down for density > 0
    int computeHeight(int blockX, int blockZ) {
        // Volume layout is (sizeX, sizeY, sizeZ, minX, minY, minZ, cellX, cellY, cellZ)
        DensityVolume volume = new DensityVolume(1, sizeY, 1, blockX, minY, blockZ, 1, 1, 1);
        finalBound.sampleVolume(columnBuffer, volume);
        for (int i = sizeY - 1; i >= 0; i--) {
            if (columnBuffer.get(volume.indexUnchecked(0, i, 0)) > 0.0f) {
                return volume.blockY(i);
            }
        }
        return minY;
    }
}
