package com.wywf.map;

import com.wywf.search.WorldContext;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import java.util.*;

final class MapBiomeSampler {
    final WorldContext context;
    private final BiomeManager blocks;
    private final Map<Long, Holder<Biome>> biomes = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Holder<Biome>> entry) { return size() > 65536; }
    };
    private final Map<Long, Float> climates = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Float> entry) { return size() > 32768; }
    };
    MapBiomeSampler(WorldContext context) {
        this.context = context;
        blocks = new BiomeManager(this::noise, BiomeManager.obfuscateSeed(context.seed));
    }
    private Holder<Biome> noise(int x, int y, int z) {
        return biomes.computeIfAbsent(BlockPos.asLong(x, y, z), key -> context.noiseBiomeAt(x, y, z));
    }
    private Holder<Biome> biome(int x, int y, int z, boolean fine) {
        return fine ? blocks.getBiome(new BlockPos(x,y,z)) : noise(x >> 2,y >> 2,z >> 2);
    }
    private static String id(Holder<Biome> biome) { return biome.unwrapKey().orElseThrow().identifier().toString(); }
    BiomeMapSnapshot.Cell point(int x, int y, int z, boolean fine) {
        var current = biome(x,y,z,fine);
        if (current == null) throw new IllegalStateException("Missing biome");
        boolean overworld = context.noiseSettingsKey().equals(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var upper = current;
        if (overworld && (y < 64 || BiomeMapColors.cave(id(current)))) {
            int reference = 128;
            upper = biome(x,reference,z,fine);
            while (BiomeMapColors.cave(id(upper)) && reference < 384) upper = biome(x,reference += 64,z,fine);
            if (y >= 64 && BiomeMapColors.cave(id(current))) current = upper;
        }
        return new BiomeMapSnapshot.Cell(id(current),current.value().getBaseTemperature(),id(upper),upper.value().getBaseTemperature());
    }
    float climate(int x, int y, int z) {
        return climates.computeIfAbsent(BlockPos.asLong(x >> 2,y >> 2,z >> 2), key ->
                net.minecraft.world.level.biome.Climate.unquantizeCoord(context.sampler().sample(x >> 2,y >> 2,z >> 2).temperature()));
    }
}
