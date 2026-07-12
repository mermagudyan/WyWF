package com.wywf.search;

// This is a test file. These files have no effect on the main gameplay.

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightweightSamplerTest {

    private static HolderLookup.Provider registries;
    private static NoiseGeneratorSettings settings;
    private static HolderGetter<NormalNoise.NoiseParameters> noiseParams;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        noiseParams = registries.lookupOrThrow(Registries.NOISE);
    }

    @Test
    void reusableSamplerMatchesRandomStateAcrossSeeds() {
        long[] seeds = {0L, 1L, 42L, -1L, 123456789L, Long.MIN_VALUE, 8675309L, 7L, -999L};
        int[] coords = {-2000, -256, -16, 0, 16, 256, 2000};

        // Build the reusable graph ONCE, then reseed for every seed.
        ReusableClimateSampler reusable = new ReusableClimateSampler(settings, noiseParams);

        for (long seed : seeds) {
            Climate.Sampler ref = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed).sampler();
            reusable.reseed(seed);
            Climate.Sampler light = reusable.sampler();

            for (int x : coords) {
                for (int z : coords) {
                    for (int y : new int[]{0, 64}) {
                        int qx = net.minecraft.core.QuartPos.fromBlock(x);
                        int qy = net.minecraft.core.QuartPos.fromBlock(y);
                        int qz = net.minecraft.core.QuartPos.fromBlock(z);
                        Climate.TargetPoint a = ref.sample(qx, qy, qz);
                        Climate.TargetPoint b = light.sample(qx, qy, qz);
                        String at = "seed=" + seed + " (" + x + "," + y + "," + z + ")";
                        assertEquals(a.temperature(), b.temperature(), "temperature " + at);
                        assertEquals(a.humidity(), b.humidity(), "humidity " + at);
                        assertEquals(a.continentalness(), b.continentalness(), "continentalness " + at);
                        assertEquals(a.erosion(), b.erosion(), "erosion " + at);
                        assertEquals(a.depth(), b.depth(), "depth " + at);
                        assertEquals(a.weirdness(), b.weirdness(), "weirdness " + at);
                    }
                }
            }
        }
    }
}
