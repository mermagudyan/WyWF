package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Spawn surface block from the biome map (no live ServerLevel offline); water skipped like vanilla
public final class SpawnBlockPredictor {

    private final BiomeSource biomeSource;

    private final Set<String> waterBiomes = new HashSet<>();
    private final Map<String, String> biomeToBlock = new HashMap<>();
    private final Set<String> possibleBlocks = new HashSet<>();

    public Set<String> waterBiomes() { return waterBiomes; }

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

        // Solid-capable blocks the player could in principle stand on at spawn.
        allowBlock("minecraft:clay");
        allowBlock("minecraft:terracotta");
        allowBlock("minecraft:red_sand");
        allowBlock("minecraft:netherrack");
        allowBlock("minecraft:soul_sand");
        allowBlock("minecraft:basalt");
        allowBlock("minecraft:blackstone");
        allowBlock("minecraft:end_stone");
        allowBlock("minecraft:obsidian");
        allowBlock("minecraft:ice");
        allowBlock("minecraft:packed_ice");
        allowBlock("minecraft:cobblestone");
        allowBlock("minecraft:moss_block");
        allowBlock("minecraft:rooted_dirt");
    }

    private void allowBlock(String block) {
        possibleBlocks.add(block);
    }

    private void put(String biome, String block) {
        biomeToBlock.put(biome, block);
        possibleBlocks.add(block);
    }

    // True when the block can ever be a spawn surface (else the term is vacuous)
    public boolean isPossibleSurfaceBlock(String blockId) {
        return "any_solid".equals(blockId) || possibleBlocks.contains(blockId);
    }

    // Most likely spawn block in +-2 chunks (vanilla hunts ~20 blocks around spawn)
    public String predict(WorldContext ctx, int centerBlockX, int centerBlockZ) {
        Climate.Sampler sampler = ctx.sampler();
        final int SCAN_CHUNKS = 2;
        for (int z = centerBlockZ - SCAN_CHUNKS * 16; z <= centerBlockZ + SCAN_CHUNKS * 16; z++) {
            for (int x = centerBlockX - SCAN_CHUNKS * 16; x <= centerBlockX + SCAN_CHUNKS * 16; x++) {
                Holder<Biome> biome = biomeSource.getNoiseBiome(x >> 2, 64 >> 2, z >> 2, sampler);
                String biomeId = biome.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
                if (biomeId == null || waterBiomes.contains(biomeId)) continue;
                return biomeToBlock.getOrDefault(biomeId, "minecraft:grass_block");
            }
        }
        return null;
    }

    public String predict(WorldContext ctx) {
        return predict(ctx, 8, 8);
    }
}
