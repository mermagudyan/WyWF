package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
                    "minecraft:ruined_portal_mountain", "minecraft:ruined_portal_ocean",
                    "minecraft:ruined_portal_nether")),
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
        Map<String, Set<ResourceKey<Biome>>> out = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : STRUCTURE_BIOMES.entrySet()) {
            Set<ResourceKey<Biome>> keys = new HashSet<>();
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

    /** Standalone re-implementation of {@code StructurePlacement.isStructureChunk},
     *  so we don't need a fully-tagged {@code ChunkGeneratorStructureState}
     *  (offline / test registries lack the {@code has_structure} biome tags). */
    private boolean isStructureChunk(WorldContext ctx, StructurePlacement placement,
                                     long seed, int x, int z) {
        return isPlacementChunk(ctx, placement, seed, x, z)
                && placement.applyAdditionalChunkRestrictions(x, z, seed);
    }

    private boolean isPlacementChunk(WorldContext ctx, StructurePlacement placement,
                                     long seed, int x, int z) {
        if (placement instanceof RandomSpreadStructurePlacement rsp) {
            ChunkPos pos = rsp.getPotentialStructureChunk(seed, x, z);
            return pos.x() == x && pos.z() == z;
        }
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            List<ChunkPos> positions = ringPositions(ctx, rings, seed);
            return positions != null && positions.contains(new ChunkPos(x, z));
        }
        return false;
    }

    private final Map<Long, List<ChunkPos>> ringCache = new ConcurrentHashMap<>();

    private List<ChunkPos> ringPositions(WorldContext ctx,
                                         ConcentricRingsStructurePlacement placement,
                                         long seed) {
        long k = ((long) System.identityHashCode(placement) << 32) ^ (seed & 0xffffffffL);
        return ringCache.computeIfAbsent(k, key -> generateRingPositions(ctx, placement, seed));
    }

    private List<ChunkPos> generateRingPositions(WorldContext ctx,
                                                 ConcentricRingsStructurePlacement placement,
                                                 long seed) {
        if (placement.count() == 0) return List.of();
        int distance = placement.distance();
        int count = placement.count();
        int spread = placement.spread();
        RandomSource random = RandomSource.create();
        random.setSeed(seed);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int positionInCircle = 0;
        int circle = 0;
        List<ChunkPos> positions = new ArrayList<>();
        var preferred = safePreferredBiomes(placement);
        Climate.Sampler sampler = ctx.sampler();
        for (int i = 0; i < count; i++) {
            double dist = (double) (4 * distance + distance * circle * 6)
                    + (random.nextDouble() - 0.5) * ((double) distance * 2.5);
            int initialX = (int) Math.round(Math.cos(angle) * dist);
            int initialZ = (int) Math.round(Math.sin(angle) * dist);
            RandomSource searchRandom = random.fork();
            var found = ctx.biomeSource.findBiomeHorizontal(
                    SectionPos.sectionToBlockCoord(initialX, 8), 0,
                    SectionPos.sectionToBlockCoord(initialZ, 8), 112,
                    preferred, searchRandom, sampler);
            ChunkPos pos;
            if (found != null) {
                pos = new ChunkPos(SectionPos.blockToSectionCoord(found.getFirst().getX()),
                        SectionPos.blockToSectionCoord(found.getFirst().getZ()));
            } else {
                pos = new ChunkPos(initialX, initialZ);
            }
            positions.add(pos);
            angle += Math.PI * 2 / (double) spread;
            if (++positionInCircle != spread) continue;
            positionInCircle = 0;
            spread += 2 * spread / (++circle + 1);
            spread = Math.min(spread, count - i);
            angle += random.nextDouble() * Math.PI * 2.0;
        }
        return positions;
    }

    /** Resolve the ring's preferred biomes; falls back to "any biome" when the
     *  vanilla {@code has_structure} tag is unbound (offline registries). */
    private java.util.function.Predicate<Holder<Biome>> safePreferredBiomes(
            ConcentricRingsStructurePlacement placement) {
        try {
            HolderSet<Biome> set = placement.preferredBiomes();
            Set<ResourceKey<Biome>> keys = new HashSet<>();
            for (Holder<Biome> h : set) {
                h.unwrapKey().ifPresent(keys::add);
            }
            if (!keys.isEmpty()) {
                return h -> h.unwrapKey().map(keys::contains).orElse(false);
            }
        } catch (UnsupportedOperationException ignored) {
            // tags not bound in offline registry
        }
        return h -> true;
    }

    private boolean structureChunk(WorldContext ctx, ResourceKey<Structure> key,
                                  int cx, int cz) {
        for (Holder<StructureSet> set : ctx.structureSets().listElements().toList()) {
            StructureSet s = set.value();
            boolean owns = false;
            for (StructureSet.StructureSelectionEntry entry : s.structures()) {
                if (entry.structure().unwrapKey().equals(Optional.of(key))) { owns = true; break; }
            }
            if (!owns) continue;
            StructurePlacement placement = s.placement();
            if (isStructureChunk(ctx, placement, ctx.seed, cx, cz)) {
                Set<ResourceKey<Biome>> allowed = STRUCTURE_BIOME_KEYS.get(key.identifier().toString());
                if (allowed == null) return true;
                int bx = cx * 16 + 8, bz = cz * 16 + 8;
                return VanillaBiomeChecker.quartYForSurfaceMatches(ctx, bx, bz, allowed);
            }
        }
        return false;
    }

    @Override
    public List<int[]> positions(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        for (String realId : expand(canonical)) {
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (!structureChunk(ctx, key, cx, cz)) continue;
                    long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(pk)) continue;
                    out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
                }
            }
        }
        return out;
    }

    @Override
    public int[] firstPosition(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        for (String realId : expand(canonical)) {
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, key, cx, cz)) {
                        return new int[]{cx * 16 + 8, cz * 16 + 8};
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean hasAnyPlacementWithin(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        for (String realId : expand(canonical)) {
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, key, cx, cz)) return true;
                }
            }
        }
        return false;
    }
}
