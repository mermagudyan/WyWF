package com.wywf.search;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class WorldContext {

    public final long seed;
    public final BiomeSource biomeSource;
    public final SpawnBlockPredictor spawnPredictor;

    private final HolderLookup.Provider registries;
    private final ResourceKey<NoiseGeneratorSettings> noiseSettingsKey;
    private final NoiseGeneratorSettings noiseSettings;
    private final HolderLookup.RegistryLookup<StructureSet> structureSets;

    public final Map<ResourceKey<Structure>, List<StructurePlacement>> placementsByStructure;

    private final Supplier<Climate.Sampler> samplerSupplier;
    private final Supplier<ReusableTerrainSampler> terrainSamplerSupplier;

    private final int terrainMinY;
    private final int terrainMaxY;

    public WorldContext(long seed,
                        BiomeSource biomeSource,
                        Supplier<Climate.Sampler> samplerSupplier,
                        Map<ResourceKey<Structure>, List<StructurePlacement>> placementsByStructure,
                        SpawnBlockPredictor spawnPredictor,
                        HolderLookup.Provider registries,
                        ResourceKey<NoiseGeneratorSettings> noiseSettingsKey,
                        HolderLookup.RegistryLookup<StructureSet> structureSets,
                        NoiseGeneratorSettings noiseSettings,
                        Supplier<ReusableTerrainSampler> terrainSamplerSupplier) {
        this.seed = seed;
        this.biomeSource = biomeSource;
        this.samplerSupplier = samplerSupplier;
        this.placementsByStructure = placementsByStructure;
        this.spawnPredictor = spawnPredictor;
        this.registries = registries;
        this.noiseSettingsKey = noiseSettingsKey;
        this.structureSets = structureSets;
        this.noiseSettings = noiseSettings;
        this.terrainSamplerSupplier = terrainSamplerSupplier;
        NoiseSettings ns = noiseSettings != null ? noiseSettings.noiseSettings() : null;
        this.terrainMinY = ns != null ? ns.minY() : -64;
        this.terrainMaxY = ns != null ? ns.minY() + ns.height() : 320;
    }

    public Climate.Sampler sampler() {
        return samplerSupplier != null ? samplerSupplier.get() : null;
    }

    public List<StructurePlacement> placementsFor(ResourceKey<Structure> key) {
        return placementsByStructure.getOrDefault(key, List.of());
    }

    public HolderLookup.Provider registries() { return registries; }

    public ResourceKey<NoiseGeneratorSettings> noiseSettingsKey() { return noiseSettingsKey; }

    public HolderLookup.RegistryLookup<StructureSet> structureSets() { return structureSets; }

    public NoiseGeneratorSettings noiseSettings() { return noiseSettings; }

    /**
     * Compute terrain height at (blockX, blockZ) by scanning downward
     * through the finalDensity function. Returns the Y of the highest
     * solid block (where density > 0).
     */
    public int computeHeight(int blockX, int blockZ) {
        ReusableTerrainSampler sampler = terrainSamplerSupplier != null
                ? terrainSamplerSupplier.get() : null;
        if (sampler == null) {
            // Fail visibly instead of masking init errors with a fake height
            throw new IllegalStateException("Terrain sampler not initialized for seed " + seed);
        }
        return sampler.computeHeight(blockX, blockZ);
    }

    /**
     * Check if terrain is flat enough around (blockX, blockZ) for structure generation.
     * Samples a few points in a small radius and checks height delta.
     */
    public boolean isTerrainFlatEnough(int blockX, int blockZ, int maxDelta) {
        int h0 = computeHeight(blockX, blockZ);
        int h1 = computeHeight(blockX + 4, blockZ);
        int h2 = computeHeight(blockX, blockZ + 4);
        int h3 = computeHeight(blockX - 4, blockZ);
        int h4 = computeHeight(blockX, blockZ - 4);
        int min = Math.min(h0, Math.min(h1, Math.min(h2, Math.min(h3, h4))));
        int max = Math.max(h0, Math.max(h1, Math.max(h2, Math.max(h3, h4))));
        return (max - min) <= maxDelta;
    }

    /**
     * Check if terrain at (blockX, blockZ) is above sea level.
     */
    public boolean isAboveSeaLevel(int blockX, int blockZ) {
        return computeHeight(blockX, blockZ) > noiseSettings.seaLevel();
    }
}
