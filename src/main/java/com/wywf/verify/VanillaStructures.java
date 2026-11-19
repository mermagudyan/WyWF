package com.wywf.verify;

import com.wywf.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import java.util.*;

final class VanillaStructures {
    record Verdict(boolean pass, List<String> evidence) {}

    static Verdict verify(PreviewWorld.Session session, ParsedQuery.Term term, int cx, int cz) {
        var level = session.level(DimensionSpace.of(term.canonical));
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var targets=StructureTargets.resolve(term.canonical,new KeywordDictionary(session.config.queryLanguage()),
                registry.containsKey(net.minecraft.resources.Identifier.parse(term.canonical)));
        int radius=TargetDistance.radius(term,session.config.searchRadiusChunks()*16);
        if (term.modifier == Modifier.AT_LEAST && term.someCount == 0) return new Verdict(true, List.of());
        Collection<ChunkPos> potential = potentialStarts(session, level, targets, cx, cz, radius);
        if (term.modifier == Modifier.AT_LEAST) {
            potential = potential.stream().sorted(Comparator.comparingDouble(pos ->
                    Math.hypot((double) pos.getBlockX(8) - cx, (double) pos.getBlockZ(8) - cz))).toList();
        }
        List<String> notes = new ArrayList<>();
        List<ChunkPos> generated = new ArrayList<>();
        double nearest=Double.POSITIVE_INFINITY;
        int scanned = 0;
        for (var pos : potential) {
            scanned++;
            var chunk = session.chunk(level, pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS);
            boolean valid = false;
            for (var entry : chunk.getAllStarts().entrySet()) {
                String actual = registry.getKey(entry.getKey()).toString();
                var start = entry.getValue();
                if (!targets.contains(actual) || !start.isValid() || start.getPieces().isEmpty()) continue;
                var box = start.getBoundingBox();
                double distance = TargetDistance.footprint(cx, cz, box.minX(), box.minZ(), box.maxX(), box.maxZ());
                if (distance > radius) continue;
                int x = pos.getBlockX(8), z = pos.getBlockZ(8);
                if (actual.equals("minecraft:buried_treasure")) {
                    var chest = buriedTreasureChest(session, level, pos);
                    if (chest == null) {
                        notes.add("Buried treasure chest did not generate in chunk " + pos.x() + ", " + pos.z());
                        continue;
                    }
                    x = chest.getX(); z = chest.getZ();
                }
                var anchor = new VerifiedAnchor(term.canonical, x, z, box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                        start.getPieces().size(), level.dimension().identifier().toString());
                boolean matches = true;
                if(matches&&!term.tags.empty())matches=VanillaTags.verify(session,level,start,term.tags,notes);
                if(matches&&term.loot!=null)matches=VanillaLoot.verify(session,term,start,notes);
                if(matches&&(term.modifier==Modifier.BETWEEN&&distance<term.betweenMin||term.modifier==Modifier.FAR&&distance<500))
                    return new Verdict(false,List.of("Nearest qualifying footprint is below the minimum distance"));
                if(matches){nearest=Math.min(nearest,distance);if(!session.anchors.contains(anchor))session.anchors.add(anchor);}
                valid |= matches;
                notes.add(actual + ": " + start.getPieces().size() + " pieces; box "
                        + box.minX() + "," + box.minZ() + ".." + box.maxX() + "," + box.maxZ());
            }
            if (valid) {
                if (term.modifier == Modifier.DEFAULT || term.modifier == Modifier.NEAR || term.modifier == Modifier.IN
                        || term.modifier == Modifier.AT || term.modifier == Modifier.BETWEEN && term.betweenMin == 0) {
                    session.chunk(level, pos.x(), pos.z(), ChunkStatus.FULL);
                    notes.add("Confirmed qualifying structure start in " + level.dimension().identifier());
                    return new Verdict(true, List.copyOf(notes));
                }
                generated.add(pos);
                if (term.modifier == Modifier.AT_LEAST && generated.size() >= term.someCount) break;

                if (term.modifier == Modifier.NEVER) return new Verdict(false, notes);
                if ((term.modifier == Modifier.FAR && nearest < 500)
                        || (term.modifier == Modifier.BETWEEN && nearest < term.betweenMin)) return new Verdict(false, notes);
            }
        }
        boolean pass = term.modifier.counted() ? term.modifier.accepts(generated.size(), term.someCount) : switch (term.modifier) {
            case NEVER -> generated.isEmpty();
            case ONLY -> generated.size() == 1;
            case SOME -> generated.size() >= term.someCount;
            case FAR -> nearest >= 500 && nearest <= 1000;
            case BETWEEN -> nearest >= term.betweenMin && nearest <= term.betweenMax;
            default -> !generated.isEmpty();
        };
        if (pass && term.modifier != Modifier.NEVER) {
            int needed = term.modifier == Modifier.SOME || term.modifier == Modifier.AT_LEAST ? term.someCount : 1;
            for (int i = 0; i < Math.min(needed, generated.size()); i++) {
                var pos = generated.get(i);
                session.chunk(level, pos.x(), pos.z(), ChunkStatus.FULL);
            }
        }
        notes.add("Vanilla placements scanned: " + scanned + "; qualifying starts: " + generated.size()
                + "; nearest: " + (!Double.isFinite(nearest) ? "none" : Math.round(nearest)));
        return new Verdict(pass, List.copyOf(notes));
    }

    static Verdict verifyBiomeLoot(PreviewWorld.Session session, ParsedQuery.Term term, int cx, int cz) {
        var dimension = DimensionSpace.of(term.canonical);
        var level = session.level(dimension);
        int centerX = dimension.coordinate(cx), centerZ = dimension.coordinate(cz);
        int radius = TargetDistance.radius(term, session.config.biomeCheckRadiusChunks() * 16);
        int minimum = term.modifier == Modifier.FAR ? 500 : term.betweenMin;
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Set<String> targets = new HashSet<>();
        registry.listElementIds().map(key -> key.identifier().toString())
                .filter(LootCatalog::hasContainer).forEach(targets::add);
        var starts = potentialStarts(session, level, targets, centerX, centerZ, radius).stream()
                .sorted(Comparator.comparingDouble(pos -> Math.hypot((double) pos.getBlockX(8) - centerX,
                        (double) pos.getBlockZ(8) - centerZ))).toList();
        List<String> notes = new ArrayList<>();
        for (var pos : starts) {
            var chunk = session.chunk(level, pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS);
            for (var entry : chunk.getAllStarts().entrySet()) {
                String actual = registry.getKey(entry.getKey()).toString();
                var start = entry.getValue();
                if (!targets.contains(actual) || !start.isValid() || start.getPieces().isEmpty()) continue;
                var box = start.getBoundingBox();
                if (TargetDistance.footprint(centerX, centerZ, box.minX(), box.minZ(), box.maxX(), box.maxZ()) > radius)
                    continue;
                var structure = new ParsedQuery.Term(actual, KeywordDictionary.Category.STRUCTURE, Modifier.DEFAULT).withLoot(term.loot);
                boolean matches = VanillaLoot.verify(session, structure, start, notes, level, chest -> {
                    double distance = Math.hypot((double) chest.getX() - centerX, (double) chest.getZ() - centerZ);
                    return distance >= minimum && distance <= radius && level.getBiome(chest).unwrapKey()
                            .map(key -> key.identifier().toString().equals(term.canonical)).orElse(false);
                });
                if (!matches) {
                    notes.clear();
                    continue;
                }
                var anchor = new VerifiedAnchor(actual, pos.getBlockX(8), pos.getBlockZ(8), box.minX(), box.minZ(),
                        box.maxX(), box.maxZ(), start.getPieces().size(), dimension.id);
                if (!session.anchors.contains(anchor)) session.anchors.add(anchor);
                notes.add("Confirmed qualifying container in biome " + term.canonical);
                return new Verdict(true, List.copyOf(notes));
            }
        }
        notes.add("No matching container found in " + term.canonical + " within the search area");
        return new Verdict(false, List.copyOf(notes));
    }

    private static Set<ChunkPos> potentialStarts(PreviewWorld.Session session, ServerLevel level, Collection<String> targets,
                                                int cx, int cz, int radius) {
        var state = level.getChunkSource().getGeneratorState();
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        int scanRadius=radius+TargetDistance.PLACEMENT_MARGIN;
        int cr=(scanRadius+31)/16;
        Set<ChunkPos> potential = new LinkedHashSet<>();
        for (var holder : state.possibleStructureSets()) {
            var set = holder.value();
            if (set.structures().stream().noneMatch(e -> targets.contains(registry.getKey(e.structure().value()).toString()))) continue;
            var placement = set.placement();
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                var positions = state.getRingPositionsFor(rings);
                if (positions != null) for (var pos : positions) {
                    if (Math.hypot((double) (pos.x() * 16 + 8) - cx, (double) (pos.z() * 16 + 8) - cz) <= scanRadius)
                        potential.add(pos);
                }
            } else {
                for (int x = (cx >> 4) - cr; x <= (cx >> 4) + cr; x++) {
                    session.checkpoint();
                    for (int z = (cz >> 4) - cr; z <= (cz >> 4) + cr; z++) {
                        if (Math.hypot((double) (x * 16 + 8) - cx, (double) (z * 16 + 8) - cz) > scanRadius) continue;
                        if (placement.isStructureChunk(state, x, z)) potential.add(new ChunkPos(x, z));
                    }
                }
            }
        }
        return potential;
    }

    static BlockPos buriedTreasureChest(PreviewWorld.Session session, ServerLevel level, ChunkPos pos) {
        int x = pos.getBlockX(9), z = pos.getBlockZ(9);
        var chunk = (LevelChunk) session.chunk(level, pos.x(), pos.z(), ChunkStatus.FULL);
        for (var at : chunk.getBlockEntitiesPos()) {
            if (at.getX() != x || at.getZ() != z || !(chunk.getBlockEntity(at) instanceof ChestBlockEntity chest)) continue;
            if (BuiltInLootTables.BURIED_TREASURE.equals(chest.getLootTable())) return at;
            var cached = session.lootContainers.get(List.of(level.dimension(), at));
            if (cached != null && BuiltInLootTables.BURIED_TREASURE.identifier().toString().equals(cached.table())) return at;
        }
        return null;
    }

    private VanillaStructures() {}
}
