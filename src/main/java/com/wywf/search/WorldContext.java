package com.wywf.search;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
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
    private final HolderLookup.RegistryLookup<StructureSet> structureSets;

    private Climate.Sampler sampler;
    private final Supplier<Climate.Sampler> samplerSupplier;

    public final Map<ResourceKey<Structure>, List<StructurePlacement>> placementsByStructure;

    public WorldContext(long seed,
                        BiomeSource biomeSource,
                        Supplier<Climate.Sampler> samplerSupplier,
                        Map<ResourceKey<Structure>, List<StructurePlacement>> placementsByStructure,
                        SpawnBlockPredictor spawnPredictor,
                        HolderLookup.Provider registries,
                        ResourceKey<NoiseGeneratorSettings> noiseSettingsKey,
                        HolderLookup.RegistryLookup<StructureSet> structureSets) {
        this.seed = seed;
        this.biomeSource = biomeSource;
        this.samplerSupplier = samplerSupplier;
        this.placementsByStructure = placementsByStructure;
        this.spawnPredictor = spawnPredictor;
        this.registries = registries;
        this.noiseSettingsKey = noiseSettingsKey;
        this.structureSets = structureSets;
    }

    public Climate.Sampler sampler() {
        Climate.Sampler s = sampler;
        if (s == null && samplerSupplier != null) {
            s = samplerSupplier.get();
            sampler = s;
        }
        return s;
    }

    public List<StructurePlacement> placementsFor(ResourceKey<Structure> key) {
        return placementsByStructure.getOrDefault(key, List.of());
    }

    public HolderLookup.Provider registries() { return registries; }

    public ResourceKey<NoiseGeneratorSettings> noiseSettingsKey() { return noiseSettingsKey; }

    public HolderLookup.RegistryLookup<StructureSet> structureSets() { return structureSets; }
}
