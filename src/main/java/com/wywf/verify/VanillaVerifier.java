package com.wywf.verify;

import com.wywf.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.material.MapColor;
import java.util.*;

final class VanillaVerifier {
    static VerificationSummary verify(PreviewWorld.Session session) {
        var level = session.server().overworld();
        BlockPos spawn = session.server().getRespawnData().pos();
        int cx = session.config.searchCenter() == SearchConfig.SearchCenter.SPAWN ? spawn.getX() : session.result.centerX;
        int cz = session.config.searchCenter() == SearchConfig.SearchCenter.SPAWN ? spawn.getZ() : session.result.centerZ;
        List<String> evidence = new ArrayList<>();
        evidence.add("Vanilla seed: " + level.getSeed());
        evidence.add("Spawn: " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
        boolean partial = session.query.isEmpty() || !session.query.ignoredWords().isEmpty(), failed = false;
        if (partial) evidence.add("PARTIAL: empty query or unrecognized words");
        if (level.getSeed() != session.result.seed)
            return new VerificationSummary(VerificationSummary.Status.FAIL, spawn.getX(), spawn.getY(), spawn.getZ(),
                    evidence, "Seed mismatch");
        try {
            QueryLogic.Truth truth = QueryLogic.Truth.YES;
            if (session.query.hasLogic()) truth = QueryLogic.evaluate(session.query.logic(), term -> verifyTerm(session, term, cx, cz, evidence));
            else for (var term : session.query.terms()) {
                var value = verifyTerm(session, term, cx, cz, evidence);
                if(value==QueryLogic.Truth.NO){truth=QueryLogic.Truth.NO;break;}
                else if (value == QueryLogic.Truth.UNKNOWN && truth != QueryLogic.Truth.NO) truth = value;
            }
            failed = truth == QueryLogic.Truth.NO;
            partial |= truth == QueryLogic.Truth.UNKNOWN;

        } catch (PreviewWorld.BudgetExceeded error) {
            evidence.add(error.getMessage());
            partial = true;
        } catch (java.util.concurrent.CancellationException error) {
            return new VerificationSummary(VerificationSummary.Status.CANCELLED, spawn.getX(), spawn.getY(), spawn.getZ(), evidence, error.getMessage());
        }
        var status = failed ? VerificationSummary.Status.FAIL : partial ? VerificationSummary.Status.PARTIAL : VerificationSummary.Status.PASS;
        return new VerificationSummary(status, spawn.getX(), spawn.getY(), spawn.getZ(), evidence,
                failed ? "At least one condition failed; see evidence" : partial ? "Only listed witnesses are confirmed; remaining constraints are not proven" : "All supported conditions confirmed");
    }

    private static QueryLogic.Truth verifyTerm(PreviewWorld.Session session,ParsedQuery.Term term,int cx,int cz,List<String> evidence){
        int anchorCount = session.anchors.size();
        var result = verifyCondition(session, term, cx, cz, evidence);
        if (result != QueryLogic.Truth.YES && session.anchors.size() > anchorCount)
            session.anchors.subList(anchorCount, session.anchors.size()).clear();
        if(session.diagnostics!=null&&result!=QueryLogic.Truth.UNKNOWN)session.diagnostics.add(term.toString(),result==QueryLogic.Truth.YES);
        return result;
    }
    private static QueryLogic.Truth verifyCondition(PreviewWorld.Session session, ParsedQuery.Term term, int cx, int cz, List<String> evidence) {
        try {
            session.checkpoint();
            var level = session.server().overworld();
            if (term.category == KeywordDictionary.Category.SPAWN) {
                if (term.modifier != Modifier.DEFAULT) return QueryLogic.Truth.UNKNOWN;
                var spawn = session.server().getRespawnData().pos();
                var block = level.getBlockState(spawn.below());
                boolean matches = term.canonical.equals("any_solid")
                        ? !block.isAir() && block.getFluidState().isEmpty() && !block.getCollisionShape(level, spawn.below()).isEmpty()
                        : term.canonical.equals(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                evidence.add(term + ": " + matches);
                return QueryLogic.Truth.of(matches);
            }
            if (term.category == KeywordDictionary.Category.STRUCTURE) {
                DimensionSpace dimension = DimensionSpace.of(term.canonical);
                if (dimension != DimensionSpace.OVERWORLD) {
                    level = session.level(dimension);
                    cx = dimension.coordinate(cx);
                    cz = dimension.coordinate(cz);
                    evidence.add("Dimension: " + dimension.id + "; center: " + cx + "," + cz + "; distances are local blocks");
                }
                if (term.loot != null && !LootCatalog.supported(term.canonical)) {
                    evidence.add("Unsupported structure loot tables: " + term.canonical);
                    return QueryLogic.Truth.UNKNOWN;
                }
                if (term.loot != null) for (var item : term.loot.items()) {
                    if (!item.enchantment().isEmpty() && !level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .containsKey(net.minecraft.resources.Identifier.parse(item.enchantment()))) return QueryLogic.Truth.UNKNOWN;
                    if (!BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.Identifier.parse(item.item()))) {
                        evidence.add("Unsupported loot item: " + item.item());
                        return QueryLogic.Truth.UNKNOWN;
                    }
                }
                var verdict = VanillaStructures.verify(session, term, cx, cz);
                evidence.addAll(verdict.evidence());
                evidence.add(term + ": " + verdict.pass());
                return QueryLogic.Truth.of(verdict.pass());
            }
            if (term.category == KeywordDictionary.Category.BIOME) {
                if (term.loot != null) {
                    var loot = VanillaStructures.verifyBiomeLoot(session, term, cx, cz);
                    evidence.addAll(loot.evidence());
                    evidence.add(term + ": " + loot.pass());
                    if (loot.pass()) session.confirmedBiomes.add(term.canonical);
                    return QueryLogic.Truth.of(loot.pass());
                }
                var verdict = verifyBiome(session, term, cx, cz);
                evidence.add(term + ": " + verdict);
                if (verdict == BiomeCoverage.Verdict.PASS && term.modifier != Modifier.NEVER) session.confirmedBiomes.add(term.canonical);
                return switch (verdict) {
                    case PASS -> QueryLogic.Truth.YES;
                    case FAIL -> QueryLogic.Truth.NO;
                    case PARTIAL -> QueryLogic.Truth.UNKNOWN;
                };
            }
            return QueryLogic.Truth.UNKNOWN;
        } catch (PreviewWorld.BudgetExceeded error) {
            evidence.add(error.getMessage());
            return QueryLogic.Truth.UNKNOWN;
        }
    }

    private static BiomeCoverage.Verdict verifyBiome(PreviewWorld.Session session, ParsedQuery.Term term, int cx, int cz) {
        DimensionSpace dimension = DimensionSpace.of(term.canonical);
        var level = session.level(dimension);
        cx = dimension.coordinate(cx);
        cz = dimension.coordinate(cz);
        if (term.biomeRegionCount()) return verifyBiomeRegions(session, level, term, cx, cz);
        if (term.modifier == Modifier.UNDER) {
            session.chunk(level, cx >> 4, cz >> 4, ChunkStatus.FULL);
            int y = com.wywf.map.BiomeMapColors.cave(term.canonical) ? -50
                    : dimension == DimensionSpace.OVERWORLD ? level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz) - 1 : 64;
            boolean match = level.getBiome(new BlockPos(cx, y, cz)).unwrapKey()
                    .map(key -> key.identifier().toString().equals(term.canonical)).orElse(false);
            return match ? BiomeCoverage.Verdict.PASS : BiomeCoverage.Verdict.FAIL;
        }
        int radius = switch (term.modifier) {
            case BETWEEN -> term.betweenMax;
            case FAR -> 1000;
            case ONLY -> 500;
            case IN -> 64;
            case AT -> 128;
            case NEAR -> 200;
            default -> session.config.biomeCheckRadiusChunks() * 16;
        };
        if (com.wywf.client.OnlineSearchBenchmark.biomeProbe() && dimension == DimensionSpace.OVERWORLD
                && term.modifier == Modifier.BETWEEN && term.betweenMin == 0 && radius <= 256
                && session.surface() == null && !com.wywf.map.BiomeMapColors.cave(term.canonical)
                && confirmsSurfaceBiome(session, level, term.canonical, cx, cz, radius)) {
            return BiomeCoverage.Verdict.PASS;
        }
        if(dimension==DimensionSpace.OVERWORLD&&session.surface()==null)buildSurface(session,cx,cz);
        var covered = dimension == DimensionSpace.OVERWORLD ? BiomeCoverage.check(session.surface(), term, cx, cz, radius)
                : BiomeCoverage.Verdict.PARTIAL;
        if (covered != BiomeCoverage.Verdict.PARTIAL) return covered;
        if (term.modifier == Modifier.ONLY || term.modifier == Modifier.SOME) return covered;
        int minimum = term.modifier == Modifier.FAR ? 500 : term.betweenMin;
        if (term.modifier == Modifier.NEVER || minimum > 0)
            return verifyBiomeArea(session, level, dimension, term, cx, cz, minimum, radius);
        var climate = ServerClimate.sampler(level);
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        var resolver = LiveBiomeResolver.create(source, climate);
        int y = com.wywf.map.BiomeMapColors.cave(term.canonical) ? -50 : 64;
        int limit = Math.min(radius, 4096), checked = 0;
        for (int ring = 0; ring <= limit; ring += 16) {
            for (int dx = -ring; dx <= ring; dx += 16) for (int dz = -ring; dz <= ring; dz += 16) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || Math.hypot(dx, dz) > radius) continue;
                if (++checked > 8192) return BiomeCoverage.Verdict.PARTIAL;
                session.checkpoint();
                var predicted = resolver.get((cx + dx) >> 2, y >> 2, (cz + dz) >> 2);
                if (!predicted.unwrapKey().orElseThrow().identifier().toString().equals(term.canonical)) continue;
                session.chunk(level, (cx + dx) >> 4, (cz + dz) >> 4, ChunkStatus.FULL);
                BlockPos position = new BlockPos(cx + dx, y, cz + dz);
                if (dimension == DimensionSpace.OVERWORLD && !com.wywf.map.BiomeMapColors.cave(term.canonical))
                    position = position.atY(level.getHeight(Heightmap.Types.WORLD_SURFACE, position.getX(), position.getZ()) - 1);
                if (level.getBiome(position).unwrapKey().orElseThrow().identifier().toString().equals(term.canonical)) return BiomeCoverage.Verdict.PASS;
            }
        }
        return BiomeCoverage.Verdict.PARTIAL;
    }

    private static BiomeCoverage.Verdict verifyBiomeRegions(PreviewWorld.Session session,
            net.minecraft.server.level.ServerLevel level, ParsedQuery.Term term, int cx, int cz) {
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        var resolver = LiveBiomeResolver.create(source, ServerClimate.sampler(level));
        var target = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                net.minecraft.resources.Identifier.parse(term.canonical));
        int quartRadius = term.betweenMax / 4;
        int side = 2 * quartRadius + 1;
        int radiusSquared = term.betweenMax * term.betweenMax;
        int quartY = (com.wywf.map.BiomeMapColors.cave(term.canonical) ? -50 : 64) >> 2;
        int count = BiomeCoverage.countRegions(side, cell -> {
            int dx = ((cell % side) - quartRadius) * 4;
            int dz = ((cell / side) - quartRadius) * 4;
            return dx * dx + dz * dz <= radiusSquared
                    && resolver.get((cx + dx) >> 2, quartY, (cz + dz) >> 2).is(target);
        }, term.someCount, session::checkpoint);
        return count >= term.someCount ? BiomeCoverage.Verdict.PASS : BiomeCoverage.Verdict.FAIL;
    }

    private static boolean confirmsSurfaceBiome(PreviewWorld.Session session, net.minecraft.server.level.ServerLevel level,
                                                String biome, int cx, int cz, int radius) {
        var target = net.minecraft.resources.Identifier.parse(biome);
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        var resolver = LiveBiomeResolver.create(source, ServerClimate.sampler(level));
        var tested = new HashSet<Long>();
        int radiusSquared = radius * radius;
        for (int ring = 0; ring <= radius; ring += 16) {
            for (int dx = -ring; dx <= ring; dx += 16) for (int dz = -ring; dz <= ring; dz += 16) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || dx * dx + dz * dz > radiusSquared) continue;
                session.checkpoint();
                int x = cx + dx, z = cz + dz;
                if (!resolver.get(x >> 2, 16, z >> 2).unwrapKey().map(key -> key.identifier().equals(target)).orElse(false))
                    continue;
                long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                if (!tested.add(chunkKey)) continue;
                session.chunk(level, x >> 4, z >> 4, ChunkStatus.FULL);
                int minX = x & ~15, minZ = z & ~15;
                for (int wz = minZ; wz < minZ + 16; wz++) for (int wx = minX; wx < minX + 16; wx++) {
                    int offsetX = wx - cx, offsetZ = wz - cz;
                    if (offsetX * offsetX + offsetZ * offsetZ > radiusSquared) continue;
                    BlockPos pos = surfacePosition(level, wx, wz);
                    if (level.getBiome(pos).unwrapKey().map(key -> key.identifier().equals(target)).orElse(false)) return true;
                }
                if (tested.size() == 4) return false;
            }
        }
        return false;
    }

    private static BlockPos surfacePosition(net.minecraft.server.level.ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        while (y > level.getMinY() && (state.isAir() || state.getMapColor(level, pos) == MapColor.NONE)) {
            pos = new BlockPos(x, --y, z);
            state = level.getBlockState(pos);
        }
        return pos;
    }

    private static BiomeCoverage.Verdict verifyBiomeArea(PreviewWorld.Session session, net.minecraft.server.level.ServerLevel level,
                                                       DimensionSpace dimension, ParsedQuery.Term term,
                                                       int cx, int cz, int minimum, int maximum) {
        boolean cave = com.wywf.map.BiomeMapColors.cave(term.canonical);
        var target = net.minecraft.resources.Identifier.parse(term.canonical);
        var chunks = new ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int z = (cz - maximum) >> 4; z <= (cz + maximum) >> 4; z++) {
            for (int x = (cx - maximum) >> 4; x <= (cx + maximum) >> 4; x++) {
                int bx = x * 16, bz = z * 16;
                if (TargetDistance.footprint(cx, cz, bx, bz, bx + 15, bz + 15) <= maximum)
                    chunks.add(new net.minecraft.world.level.ChunkPos(x, z));
            }
        }
        chunks.sort(Comparator.comparingDouble(pos -> TargetDistance.footprint(cx, cz, pos.getMinBlockX(), pos.getMinBlockZ(),
                pos.getMaxBlockX(), pos.getMaxBlockZ())));
        var sample = new BlockPos.MutableBlockPos();
        double nearest = Double.POSITIVE_INFINITY;
        for (var pos : chunks) {
            double bound = TargetDistance.footprint(cx, cz, pos.getMinBlockX(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockZ());
            if (nearest <= maximum && bound >= minimum) return BiomeCoverage.Verdict.PASS;
            session.checkpoint();
            session.chunk(level, pos.x(), pos.z(), ChunkStatus.FULL);
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
                    double distance = Math.hypot((double) x - cx, (double) z - cz);
                    if (distance > maximum || distance >= nearest) continue;
                    int y = cave ? -50 : dimension == DimensionSpace.OVERWORLD
                            ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1 : 64;
                    if (!level.getBiome(sample.set(x, y, z)).unwrapKey().map(key -> key.identifier().equals(target)).orElse(false)) continue;
                    if (term.modifier == Modifier.NEVER || distance < minimum) return BiomeCoverage.Verdict.FAIL;
                    nearest = distance;
                }
            }
        }
        return term.modifier == Modifier.NEVER || nearest <= maximum ? BiomeCoverage.Verdict.PASS : BiomeCoverage.Verdict.FAIL;
    }

    static void buildSurface(PreviewWorld.Session session, int cx, int cz) {
        buildSurface(session, DimensionSpace.OVERWORLD, cx, cz, 128);
    }
    static void buildSurface(PreviewWorld.Session session, DimensionSpace dimension, int cx, int cz, int size) {
        int x0 = Math.floorDiv(cx - size / 2, 16) * 16, z0 = Math.floorDiv(cz - size / 2, 16) * 16;
        int[] colors = new int[size * size], heights = new int[colors.length];
        String[] blocks = new String[colors.length], biomes = new String[colors.length];
        float[] temperatures = new float[colors.length], noise = new float[colors.length];
        var level = session.level(dimension);
        var climate = dimension == DimensionSpace.OVERWORLD ? ServerClimate.sampler(level) : null;
        var cache = session.surfaceCache(dimension);
        var biomeColors = new java.util.HashMap<String, Integer>();
        for (int chunkZ = 0; chunkZ < size; chunkZ += 16) for (int chunkX = 0; chunkX < size; chunkX += 16) {
            var cached=cache.get(x0+chunkX,z0+chunkZ);
            if(cached!=null){
                biomeColors.putAll(cached.biomeColors());
                for(int dz=0;dz<16;dz++){
                    int from=dz*16,to=(chunkZ+dz)*size+chunkX;
                    System.arraycopy(cached.colors(),from,colors,to,16);
                    System.arraycopy(cached.heights(),from,heights,to,16);
                    System.arraycopy(cached.blocks(),from,blocks,to,16);
                    System.arraycopy(cached.biomes(),from,biomes,to,16);
                    System.arraycopy(cached.temperatures(),from,temperatures,to,16);
                    System.arraycopy(cached.climate(),from,noise,to,16);
                }
                continue;
            }
            session.chunk(level, (x0 + chunkX) >> 4, (z0 + chunkZ) >> 4, ChunkStatus.FULL);
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                int x = x0 + chunkX + dx, z = z0 + chunkZ + dz;
                int index = (chunkZ + dz) * size + chunkX + dx;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (dimension == DimensionSpace.END) y = Math.max(level.getMinY(), y);
                BlockPos position = new BlockPos(x, y, z);
                var state = level.getBlockState(position);
                while (y > level.getMinY() && (state.isAir() || state.getMapColor(level, position) == MapColor.NONE)) {
                    position = new BlockPos(x, --y, z);
                    state = level.getBlockState(position);
                }
                colors[index] = ReliefBlocks.surfaceColor(state, level, position, level.getMinY(), level::getBlockState);
                heights[index] = dimension == DimensionSpace.END && state.isAir() ? Integer.MIN_VALUE : y;
                blocks[index] = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                var biome = level.getBiome(position);
                biomes[index] = biome.unwrapKey().orElseThrow().identifier().toString();
                dev.diylib.client.render.DiyBlockColors.recordBiomeColor(biomeColors, biomes[index], biome.value());
                temperatures[index] = biome.value().getBaseTemperature();
                noise[index] = climate == null ? Float.NaN : Climate.unquantizeCoord(climate.sample(x >> 2, 16, z >> 2).temperature());
            }
            var partial=new SurfaceSnapshot(session.result.seed,x0,z0,size,colors,heights,blocks,biomes,temperatures,noise,false,biomeColors);
            session.tile(dimension, SurfaceCache.region(partial,chunkX,chunkZ,16));
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            session.checkpoint();
        }
        session.surface(dimension, new SurfaceSnapshot(session.result.seed, x0, z0, size, colors, heights, blocks, biomes, temperatures, noise, true, biomeColors));
    }

    private VanillaVerifier() {}
}
