package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VanillaStructureChecker implements StructureChecker {

    private static final Map<String, List<String>> EXPANSIONS = Map.ofEntries(
            Map.entry("minecraft:village", List.of(
                    "minecraft:village_plains", "minecraft:village_desert",
                    "minecraft:village_savanna", "minecraft:village_snowy",
                    "minecraft:village_taiga")),
            Map.entry("minecraft:smithy", List.of(
                    "minecraft:village_plains", "minecraft:village_desert",
                    "minecraft:village_savanna", "minecraft:village_snowy",
                    "minecraft:village_taiga")),
            Map.entry("minecraft:jungle_temple", List.of("minecraft:jungle_pyramid")),
            Map.entry("minecraft:shipwreck", List.of(
                    "minecraft:shipwreck", "minecraft:shipwreck_beached")),
            Map.entry("minecraft:ruined_portal", List.of(
                    "minecraft:ruined_portal", "minecraft:ruined_portal_desert",
                    "minecraft:ruined_portal_jungle", "minecraft:ruined_portal_swamp",
                    "minecraft:ruined_portal_mountain", "minecraft:ruined_portal_ocean")),
            Map.entry("minecraft:mineshaft", List.of(
                    "minecraft:mineshaft", "minecraft:mineshaft_mesa")),
            Map.entry("minecraft:ocean_monument", List.of("minecraft:monument")),
            Map.entry("minecraft:ocean_ruins", List.of(
                    "minecraft:ocean_ruin_cold", "minecraft:ocean_ruin_warm"))
    );

    private static final Map<String, Set<String>> STRUCTURE_BIOMES = Map.ofEntries(
            Map.entry("minecraft:mansion", Set.of("minecraft:dark_forest")),
            Map.entry("minecraft:village_plains", Set.of("minecraft:plains", "minecraft:meadow")),
            Map.entry("minecraft:village_desert", Set.of("minecraft:desert")),
            Map.entry("minecraft:village_savanna", Set.of("minecraft:savanna")),
            Map.entry("minecraft:village_snowy", Set.of("minecraft:snowy_plains")),
            Map.entry("minecraft:village_taiga", Set.of("minecraft:taiga")),
            Map.entry("minecraft:desert_pyramid", Set.of("minecraft:desert")),
            Map.entry("minecraft:jungle_pyramid", Set.of("minecraft:jungle", "minecraft:bamboo_jungle")),
            Map.entry("minecraft:swamp_hut", Set.of("minecraft:swamp")),
            Map.entry("minecraft:igloo", Set.of(
                    "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:snowy_slopes"))
    );

    private static final Map<String, Set<ResourceKey<Biome>>> STRUCTURE_BIOME_KEYS = buildBiomeKeys();

    private static Map<String, Set<ResourceKey<Biome>>> buildBiomeKeys() {
        Map<String, Set<ResourceKey<Biome>>> out = new java.util.HashMap<>();
        for (Map.Entry<String, Set<String>> e : STRUCTURE_BIOMES.entrySet()) {
            Set<ResourceKey<Biome>> keys = new java.util.HashSet<>();
            for (String id : e.getValue()) {
                Identifier ident = Identifier.tryParse(id);
                if (ident != null) keys.add(ResourceKey.create(Registries.BIOME, ident));
            }
            out.put(e.getKey(), Set.copyOf(keys));
        }
        return Map.copyOf(out);
    }

    public static List<String> expand(String canonical) {
        return EXPANSIONS.getOrDefault(canonical, List.of(canonical));
    }

    private final Map<String, Optional<ResourceKey<Structure>>> keyCache = new ConcurrentHashMap<>();

    private ResourceKey<Structure> resolveKey(String realId) {
        return keyCache.computeIfAbsent(realId, rid -> {
            Identifier id = Identifier.tryParse(rid);
            if (id == null) return Optional.empty();
            return Optional.of(ResourceKey.create(Registries.STRUCTURE, id));
        }).orElse(null);
    }

    @Override
    public List<int[]> positions(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int chunkMinX = centerChunkX - radiusChunks, chunkMaxX = centerChunkX + radiusChunks;
        int chunkMinZ = centerChunkZ - radiusChunks, chunkMaxZ = centerChunkZ + radiusChunks;
        long seed = ctx.seed;

        for (String realId : expand(canonical)) {
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            Set<ResourceKey<Biome>> allowedBiomes = STRUCTURE_BIOME_KEYS.get(realId);

            for (StructurePlacement placement : ctx.placementsFor(key)) {
                if (!(placement instanceof RandomSpreadStructurePlacement rsp)) continue;
                int spacing = rsp.spacing();
                if (spacing <= 0) continue;

                int regMinX = Math.floorDiv(chunkMinX, spacing);
                int regMaxX = Math.floorDiv(chunkMaxX, spacing);
                int regMinZ = Math.floorDiv(chunkMinZ, spacing);
                int regMaxZ = Math.floorDiv(chunkMaxZ, spacing);

                for (int rx = regMinX; rx <= regMaxX; rx++) {
                    for (int rz = regMinZ; rz <= regMaxZ; rz++) {
                        ChunkPos c = rsp.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                        int cx = c.x();
                        int cz = c.z();
                        if (cx < chunkMinX || cx > chunkMaxX || cz < chunkMinZ || cz > chunkMaxZ) continue;

                        long posKey = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                        if (!seen.add(posKey)) continue;

                        int blockX = cx * 16 + 8;
                        int blockZ = cz * 16 + 8;
                        if (allowedBiomes != null && !biomeMatches(ctx, blockX, blockZ, allowedBiomes)) {
                            continue;
                        }
                        out.add(new int[]{blockX, blockZ});
                    }
                }
            }
        }
        return out;
    }

    @Override
    public int[] firstPosition(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int chunkMinX = centerChunkX - radiusChunks, chunkMaxX = centerChunkX + radiusChunks;
        int chunkMinZ = centerChunkZ - radiusChunks, chunkMaxZ = centerChunkZ + radiusChunks;
        long seed = ctx.seed;

        for (String realId : expand(canonical)) {
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            Set<ResourceKey<Biome>> allowedBiomes = STRUCTURE_BIOME_KEYS.get(realId);

            for (StructurePlacement placement : ctx.placementsFor(key)) {
                if (!(placement instanceof RandomSpreadStructurePlacement rsp)) continue;
                int spacing = rsp.spacing();
                if (spacing <= 0) continue;

                int regMinX = Math.floorDiv(chunkMinX, spacing);
                int regMaxX = Math.floorDiv(chunkMaxX, spacing);
                int regMinZ = Math.floorDiv(chunkMinZ, spacing);
                int regMaxZ = Math.floorDiv(chunkMaxZ, spacing);

                for (int rx = regMinX; rx <= regMaxX; rx++) {
                    for (int rz = regMinZ; rz <= regMaxZ; rz++) {
                        ChunkPos c = rsp.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                        int cx = c.x();
                        int cz = c.z();
                        if (cx < chunkMinX || cx > chunkMaxX || cz < chunkMinZ || cz > chunkMaxZ) continue;

                        int blockX = cx * 16 + 8;
                        int blockZ = cz * 16 + 8;
                        if (allowedBiomes != null && !biomeMatches(ctx, blockX, blockZ, allowedBiomes)) {
                            continue;
                        }
                        return new int[]{blockX, blockZ};
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean hasAnyPlacementWithin(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int chunkMinX = centerChunkX - radiusChunks, chunkMaxX = centerChunkX + radiusChunks;
        int chunkMinZ = centerChunkZ - radiusChunks, chunkMaxZ = centerChunkZ + radiusChunks;
        long seed = ctx.seed;

        for (String realId : expand(canonical)) {
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;

            for (StructurePlacement placement : ctx.placementsFor(key)) {
                if (!(placement instanceof RandomSpreadStructurePlacement rsp)) continue;
                int spacing = rsp.spacing();
                if (spacing <= 0) continue;

                int regMinX = Math.floorDiv(chunkMinX, spacing);
                int regMaxX = Math.floorDiv(chunkMaxX, spacing);
                int regMinZ = Math.floorDiv(chunkMinZ, spacing);
                int regMaxZ = Math.floorDiv(chunkMaxZ, spacing);

                for (int rx = regMinX; rx <= regMaxX; rx++) {
                    for (int rz = regMinZ; rz <= regMaxZ; rz++) {
                        ChunkPos c = rsp.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                        int cx = c.x();
                        int cz = c.z();
                        if (cx >= chunkMinX && cx <= chunkMaxX && cz >= chunkMinZ && cz <= chunkMaxZ) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean biomeMatches(WorldContext ctx, int blockX, int blockZ, Set<ResourceKey<Biome>> allowedBiomes) {
        Climate.Sampler sampler = ctx.sampler();
        if (sampler == null || ctx.biomeSource == null) return false;

        Holder<Biome> holder = ctx.biomeSource.getNoiseBiome(blockX >> 2, 16, blockZ >> 2, sampler);
        if (holder == null) return false;

        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        return key != null && allowedBiomes.contains(key);
    }
}
