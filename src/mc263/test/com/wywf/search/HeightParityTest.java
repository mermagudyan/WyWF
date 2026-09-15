package com.wywf.search;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeightParityTest {

    private static NoiseGeneratorSettings settings;
    private static HolderGetter<NormalNoise> noises;
    private static int minY;
    private static int maxY;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var r = VanillaRegistries.createWorldLookup();
        settings = r.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        noises = r.lookupOrThrow(Registries.NOISE);
        minY = settings.noiseSettings().minY();
        maxY = minY + settings.noiseSettings().height();
    }

    @Test
    void volumeHeightsMatchUncachedSampling() {
        ReusableTerrainSampler reusable = new ReusableTerrainSampler(settings, noises);
        long[] seeds = {1L, 42L, 123456789L, -999L};
        int[][] cols = {{0, 0}, {100, -200}, {-500, 700}};
        for (long seed : seeds) {
            reusable.reseed(seed);
            RandomState ref = RandomState.create(noises, seed, settings);
            var fn = settings.noiseRouter().finalDensity();
            for (int[] c : cols) {
                int expected = minY;
                for (int y = maxY; y >= minY; y--) {
                    if (ref.sampleBlockValueUncached(fn, c[0], y, c[1]) > 0.0f) {
                        expected = y;
                        break;
                    }
                }
                assertEquals(expected, reusable.computeHeight(c[0], c[1]),
                        "seed=" + seed + " col=(" + c[0] + "," + c[1] + ")");
            }
        }
    }
}
