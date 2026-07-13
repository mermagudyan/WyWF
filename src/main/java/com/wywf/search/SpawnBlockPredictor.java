package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Predicts the block a player would stand on at world spawn, without altering
 * vanilla spawn selection.
 *
 * <p>Vanilla's {@code PlayerSpawnFinder} requires a live {@code ServerLevel}
 * (async chunk loading) and the concrete chunk generator, neither of which is
 * available offline on the client. This predictor approximates it from the
 * biome at the origin chunk: it scans columns for the first non-water biome and
 * maps that biome to its typical surface block.
 *
 * <p>The surface <i>material</i> is therefore biome-based and may differ from
 * the exact voxel in edge cases (beaches, rivers, lakes, snow layers). Water
 * biomes are skipped, matching vanilla's refusal to spawn on liquid.
 */
public final class SpawnBlockPredictor {

    private final BiomeSource biomeSource;

    private final Set<String> waterBiomes = new HashSet<>();
    private final Map<String, String> biomeToBlock = new HashMap<>();
    private final Set<String> possibleBlocks = new HashSet<>();

    public SpawnBlockPredictor(BiomeSource biomeSource) {
        this.biomeSource = biomeSource;
        buildWaterBiomes();
        buildBiomeMap();
    }

    private void buildWaterBiomes() {
        for (String b : new String[]{
                "minecraft:ocean", "minecraft:deep_ocean", "minecraft:warm_ocean",
                "minecraft:lukewarm_ocean", "minecraft:cold_ocean", "minecraft:frozen_ocean",
                "minecraft:river", "minecraft:frozen_river"
        }) {
            waterBiomes.add(b);
        }
    }

    private void buildBiomeMap() {
        put("minecraft:plains", "minecraft:grass_block");
        put("minecraft:desert", "minecraft:sand");
        put("minecraft:beach", "minecraft:sand");
        put("minecraft:badlands", "minecraft:sand");
        put("minecraft:wooded_badlands", "minecraft:sand");
        put("minecraft:eroded_badlands", "minecraft:sand");
        put("minecraft:snowy_beach", "minecraft:snow_block");
        put("minecraft:snowy_plains", "minecraft:snow_block");
        put("minecraft:snowy_taiga", "minecraft:snow_block");
        put("minecraft:ice_spikes", "minecraft:snow_block");
        put("minecraft:grove", "minecraft:snow_block");
        put("minecraft:frozen_peaks", "minecraft:snow_block");
        put("minecraft:jagged_peaks", "minecraft:stone");
        put("minecraft:stony_peaks", "minecraft:stone");
        put("minecraft:stony_shore", "minecraft:stone");
        put("minecraft:stone_shore", "minecraft:stone");
        put("minecraft:old_growth_pine_taiga", "minecraft:podzol");
        put("minecraft:old_growth_spruce_taiga", "minecraft:podzol");
        put("minecraft:mushroom_fields", "minecraft:mycelium");
    }

    private void put(String biome, String block) {
        biomeToBlock.put(biome, block);
        possibleBlocks.add(block);
    }

    /** True if the requested block can ever be a spawn surface (so the term is a real filter). */
    public boolean isPossibleSurfaceBlock(String blockId) {
        return "any_solid".equals(blockId) || possibleBlocks.contains(blockId);
    }

    public String predict(WorldContext ctx, long seed) {
        Climate.Sampler sampler = ctx.sampler();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                Holder<Biome> biome = biomeSource.getNoiseBiome(x >> 2, 64 >> 2, z >> 2, sampler);
                String biomeId = biome.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
                if (biomeId == null || waterBiomes.contains(biomeId)) continue;
                return biomeToBlock.getOrDefault(biomeId, "minecraft:grass_block");
            }
        }
        return null;
    }
}
