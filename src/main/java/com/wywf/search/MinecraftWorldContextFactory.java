package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MinecraftWorldContextFactory implements WorldContextFactory {

    private volatile HolderLookup.Provider registries;
    private volatile BiomeSource biomeSource;
    private volatile HolderLookup.RegistryLookup<StructureSet> structureSets;
    private volatile HolderLookup.RegistryLookup<Structure> structures;
    private volatile Map<ResourceKey<Structure>, List<StructurePlacement>> placementsByStructure;
    private volatile Holder<NoiseGeneratorSettings> noiseSettingsHolder;
    private volatile NoiseGeneratorSettings noiseSettings;
    private volatile HolderGetter<NormalNoise.NoiseParameters> noiseParameters;
    private volatile SpawnBlockPredictor spawnPredictor;

    private final ThreadLocal<ReusableClimateSampler> reusableSampler =
            ThreadLocal.withInitial(() -> new ReusableClimateSampler(noiseSettings, noiseParameters));

    private synchronized void ensureInit() {
        if (registries != null) return;

        HolderLookup.Provider r = VanillaRegistries.createLookup();

        HolderLookup.RegistryLookup<MultiNoiseBiomeSourceParameterList> presets =
                r.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        Holder<MultiNoiseBiomeSourceParameterList> overworldPreset =
                presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);

        this.biomeSource    = MultiNoiseBiomeSource.createFromPreset(overworldPreset);
        this.structureSets  = r.lookupOrThrow(Registries.STRUCTURE_SET);
        this.structures     = r.lookupOrThrow(Registries.STRUCTURE);

        this.placementsByStructure = buildPlacements(this.structureSets);

        this.noiseSettingsHolder = r.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        this.noiseSettings    = this.noiseSettingsHolder.value();
        this.noiseParameters  = r.lookupOrThrow(Registries.NOISE);

        this.spawnPredictor = new SpawnBlockPredictor(biomeSource);

        this.registries     = r;
    }

    private static Map<ResourceKey<Structure>, List<StructurePlacement>> buildPlacements(
            HolderLookup.RegistryLookup<StructureSet> sets) {
        Map<ResourceKey<Structure>, List<StructurePlacement>> map = new HashMap<>();
        sets.listElements().forEach(ref -> {
            StructureSet set = ref.value();
            StructurePlacement placement = set.placement();
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                entry.structure().unwrapKey().ifPresent(key ->
                        map.computeIfAbsent(key, k -> new ArrayList<>()).add(placement));
            }
        });
        return Map.copyOf(map);
    }

    @Override
    public WorldContext create(long seed, boolean accurateRings) {
        ensureInit();
        return new WorldContext(seed, biomeSource, () -> samplerFor(seed), placementsByStructure, spawnPredictor);
    }

    @Override
    public Climate.Sampler samplerFor(long seed) {
        ensureInit();
        ReusableClimateSampler s = reusableSampler.get();
        s.reseed(seed);
        return s.sampler();
    }

    @Override
    public boolean isStructureAvailable(String structureId) {
        ensureInit();
        Identifier id = Identifier.tryParse(structureId);
        if (id == null) return false;
        return structures.get(ResourceKey.create(Registries.STRUCTURE, id)).isPresent();
    }

    @Override
    public boolean isBiomeAvailable(String biomeId) {
        ensureInit();
        Identifier id = Identifier.tryParse(biomeId);
        if (id == null) return false;
        return registries.lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, id)).isPresent();
    }
}
