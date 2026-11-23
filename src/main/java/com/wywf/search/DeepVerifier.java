package com.wywf.search;

import com.wywf.core.QueryLogic;
import com.wywf.core.KeywordDictionary;
import com.wywf.core.Modifier;
import com.wywf.core.ParsedQuery;
import com.wywf.core.SearchConfig;
import com.wywf.core.SearchResult;
import com.wywf.core.WyWFDebug;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Re-checks candidates exhaustively before showing
public final class DeepVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");

    private static final int NEAR_BLOCKS = 200;
    private static final int IN_BLOCKS   = 64;
    private static final int ONLY_BLOCKS = 500;
    private static final int FAR_MIN     = 500;
    private static final int FAR_MAX     = 1000;
    private static final int SOME_BLOCKS = 320;
    private static final int SOME_BLOCKS_LARGE = 640;

    private static final Set<String> UNDERGROUND_STRUCTURES = Set.of(
            "minecraft:stronghold", "minecraft:mineshaft",
            "minecraft:ancient_city", "minecraft:trial_chambers");

    private DeepVerifier() {}

    // Re-verify up to 8 candidates, return best survivor (distanceFirst picks closest, else best match)
    public static SearchResult pickBest(WorldContextFactory factory,
                                        StructureChecker structureChecker,
                                        ParsedQuery query,
                                        SearchConfig config,
                                        List<SearchResult> candidates,
                                        int biomeRadiusChunks,
                                        boolean distanceFirst) {
        if (candidates == null || candidates.isEmpty()) return null;

        VanillaBiomeChecker dense = new VanillaBiomeChecker();
        dense.stepChunks(1);

        long t0 = System.currentTimeMillis();
        SearchResult best = null;
        double bestRank = Double.MAX_VALUE;
        double bestTie = Double.MAX_VALUE;
        int passed = 0;
        int limit = Math.min(candidates.size(), 8);

        for (int i = 0; i < limit; i++) {
            SearchResult r = candidates.get(i);
            Verdict v = verify(factory, structureChecker, dense, query, config, r, biomeRadiusChunks);
            if (WyWFDebug.ENABLED) LOGGER.info("[deep-verify] seed {} -> {} ({})", r.seed, v.pass() ? "PASS" : "FAIL", v.note());
            if (v.pass()) {
                passed++;
                double rank = distanceFirst ? r.distanceToStructure() : v.score();
                double tie  = distanceFirst ? v.score() : r.distanceToStructure();
                if (rank < bestRank || (rank == bestRank && tie < bestTie)) {
                    bestRank = rank;
                    bestTie = tie;
                    best = r;
                }
            }
        }
        if (WyWFDebug.ENABLED) LOGGER.info("[deep-verify] {}/{} candidates verified in {} ms; ranking={}; winner={}",
                passed, limit, System.currentTimeMillis() - t0,
                distanceFirst ? "strict(distance)" : "soft(score)",
                best == null ? "none" : best.seed);
        return best;
    }

    public static List<SearchResult> survivors(WorldContextFactory factory, StructureChecker checker, ParsedQuery query,
                                               SearchConfig config, List<SearchResult> input, int radius,
                                               java.util.function.BooleanSupplier cancelled) {
        VanillaBiomeChecker dense = new VanillaBiomeChecker();
        dense.stepChunks(1);
        List<SearchResult> result = new java.util.ArrayList<>();
        try(var scope=com.wywf.core.SearchCancellation.bind(cancelled)){
            for(var candidate:input){
                com.wywf.core.SearchCancellation.checkpoint();
                var verdict=verify(factory,checker,dense,query,config,candidate,radius);
                com.wywf.core.SearchCancellation.checkpoint();
                if(WyWFDebug.ENABLED)LOGGER.info("[deep-verify] seed {} -> {} ({})",candidate.seed,verdict.pass()?"PASS":"FAIL",verdict.note());
                if(verdict.pass())result.add(candidate);
            }
        }catch(java.util.concurrent.CancellationException cancelledCheck){}
        return List.copyOf(result);
    }
    private record Verdict(boolean pass, String note, double score) {}
    private static Verdict ok(double score)  { return new Verdict(true, "ok", score); }
    private static Verdict fail(String why)  { return new Verdict(false, why, Double.MAX_VALUE); }

    private static Verdict verify(WorldContextFactory factory,
                                   StructureChecker sc,
                                   VanillaBiomeChecker dense,
                                   ParsedQuery query,
                                   SearchConfig config,
                                   SearchResult r,
                                   int biomeRadiusChunks) {
        if (query.error() != null) return fail(query.error());

        boolean accurateRings = query.structures().contains("minecraft:stronghold");
        WorldContext ctx;
        try {
            ctx = factory.create(r.seed, accurateRings);
        } catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch (Throwable t) {
            return fail("context failed: " + t);
        }
        if (CubiomesBridge.isActive()) {
            try { CubiomesBridge.applySeed(r.seed); } catch (Throwable ignored) {}
        }
        return verifyContext(ctx, sc, dense, query, config, r, biomeRadiusChunks);
    }
    private static Verdict verifyContext(WorldContext ctx, StructureChecker sc, VanillaBiomeChecker dense,
                                         ParsedQuery query, SearchConfig config, SearchResult r, int biomeRadiusChunks) {
        if (query.hasLogic()) {
            var truth = QueryLogic.evaluate(query.logic(), term -> {
                if (com.wywf.core.DimensionSpace.external(term)||query.estimated()&&com.wywf.core.TargetDistance.deferred(term)) return QueryLogic.Truth.UNKNOWN;
                try {
                    var leaf = new ParsedQuery(term.toString(), List.of(term));if(query.estimated())leaf=leaf.estimate();
                                        boolean pass = verifyContext(ctx, sc, dense, leaf, config, r, biomeRadiusChunks).pass();
                    return pass && (term.biomeRegionCount() || term.loot != null || !term.tags.empty()) ? QueryLogic.Truth.UNKNOWN : QueryLogic.Truth.of(pass);
                }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
                catch (RuntimeException unavailable) { return QueryLogic.Truth.UNKNOWN; }
            });
            return truth == QueryLogic.Truth.YES || truth == QueryLogic.Truth.UNKNOWN && query.terms().stream().anyMatch(t -> t.biomeRegionCount() || t.loot != null || !t.tags.empty() || com.wywf.core.DimensionSpace.external(t)||query.estimated()&&com.wywf.core.TargetDistance.deferred(t)) ? ok(0) : fail("logical conditions rejected or unavailable");
        }
        int cx = r.centerX, cz = r.centerZ;
        double score = 0;

        for (ParsedQuery.Term term : query.terms()) {
            com.wywf.core.SearchCancellation.checkpoint();
            if (com.wywf.core.DimensionSpace.external(term) || term.biomeLoot()) continue;
            switch (term.category) {
                case STRUCTURE -> {
                    Verdict v = query.estimated() ? estimateStructure(sc,ctx,term,cx,cz,config.searchRadiusChunks()) : checkStructure(sc,ctx,term,cx,cz,config.searchRadiusChunks());
                    if (!v.pass()) return v;
                    score += v.score();
                }
                case BIOME -> {
                    Verdict v = checkBiome(dense, ctx, term, cx, cz, biomeRadiusChunks);
                    if (!v.pass()) return v;
                    score += v.score();
                }
                case SPAWN -> {
                    if (!spawnBlockOk(ctx, term, cx, cz)) return fail("spawn block mismatch");
                }
                default -> { }
            }
        }
        return ok(score);
    }

    private static Verdict estimateStructure(StructureChecker sc,WorldContext ctx,ParsedQuery.Term term,int cx,int cz,int radius){
        if(com.wywf.core.TargetDistance.deferred(term))return ok(0);
        int limit=com.wywf.core.TargetDistance.radius(term,radius*16)+com.wywf.core.TargetDistance.PLACEMENT_MARGIN;
        int chunks = (limit + 15) / 16;
        int needed = term.modifier == Modifier.SOME || term.modifier.counted() ? term.someCount : 1;
        if (sc.positions(ctx, cx, cz, chunks, term.canonical).size() >= needed
                || sc.positionsPlacementOnly(ctx, cx, cz, chunks, term.canonical).size() >= needed) return ok(0);
        return fail("No placement within conservative footprint search");
    }
    private static Verdict checkStructure(StructureChecker sc, WorldContext ctx,
                                          ParsedQuery.Term term, int cx, int cz,
                                          int searchRadius) {
        Modifier mod = term.modifier;
        String canon = term.canonical;

        if (mod.counted()) {
            int radius = term.betweenMax > 0 ? term.betweenMax : 500;
            int count = (int) sc.positions(ctx, cx, cz, (radius + 15) / 16, canon).stream()
                    .filter(p -> Math.hypot((double) p[0] - cx, (double) p[1] - cz) <= radius).count();
            return mod.accepts(count, term.someCount) ? ok(0) : fail("count=" + count);
        }
        if (mod == Modifier.NEVER) {
            // Same verdict as bulk: reject on biome-viable presence, mere placement can't generate
            List<int[]> any = sc.positions(ctx, cx, cz, searchRadius, canon);
            return any.isEmpty() ? ok(0) : fail("forbidden structure present at ~" + nearest(any, cx, cz));
        }

        int scan = switch (mod) {
            case FAR     -> (FAR_MAX + 15) / 16;
            case BETWEEN -> (Math.max(1, term.betweenMax) + 15) / 16;
            case AT -> 8;
            case NEAR    -> (NEAR_BLOCKS + 15) / 16;
            case IN      -> UNDERGROUND_STRUCTURES.contains(canon) ? searchRadius : (IN_BLOCKS + 15) / 16;
            default      -> searchRadius;
        };

        List<int[]> pos = sc.positions(ctx, cx, cz, scan, canon);
        if (pos.isEmpty()) return fail("no viable structure position");

        int min = nearest(pos, cx, cz);

        return switch (mod) {
            case AT -> min <= 128 ? ok(rel(min, 128)) : fail("nearest > 128");
            case NEAR    -> min <= NEAR_BLOCKS ? ok(rel(min, NEAR_BLOCKS))
                                               : fail("nearest " + min + " > 200");
            case IN      -> min <= IN_BLOCKS   ? ok(rel(min, IN_BLOCKS))
                                               : fail("nearest " + min + " > 64");
            case FAR     -> (min >= FAR_MIN && min <= FAR_MAX) ? ok(rel(min, FAR_MAX))
                                                               : fail("nearest " + min + " outside far band");
            case ONLY    -> { int c = countWithin(pos, cx, cz, ONLY_BLOCKS);
                              yield c == 1 ? ok(0) : fail("only: count=" + c); }
            case SOME    -> { int eff = term.someCount >= 5 ? SOME_BLOCKS_LARGE : SOME_BLOCKS;
                              int c = countWithin(pos, cx, cz, eff);
                              yield c >= Math.max(2, term.someCount) ? ok(rel(min, eff))
                                                                     : fail("some: count=" + c + " < " + term.someCount); }
            case BETWEEN -> (min >= term.betweenMin && min <= term.betweenMax)
                                ? ok(rel(min, Math.max(1, term.betweenMax)))
                                : fail("nearest " + min + " outside between range");
            default      -> ok(rel(min, searchRadius * 16));
        };
    }

    private static Verdict checkBiome(VanillaBiomeChecker bc, WorldContext ctx,
                                      ParsedQuery.Term term, int cx, int cz,
                                      int biomeRadiusChunks) {
        String b = term.canonical;
        Modifier mod = term.modifier;

        if (term.biomeRegionCount()) {
            return bc.exists(ctx, cx, cz, (term.betweenMax + 15) / 16, b)
                    ? ok(0) : fail("required biome absent in region count radius");
        }
        if (mod == Modifier.AT) {
            int distance = bc.nearestDistanceBlocks(ctx, cx, cz, 8, b);
            return distance >= 0 && distance <= 128 ? ok(rel(distance, 128)) : fail("biome distance > 128");
        }
        if (bc.isUnderground(b) && mod == Modifier.BETWEEN) {
            int qy = bc.quartYFor(b);
            int radius = (Math.max(1, term.betweenMax) + 15) / 16;
            var field = bc.sampleField(ctx, cx, cz, qy, radius, bc.effectiveStep(qy, radius));
            var key = bc.keyOf(b);
            int distance = key != null ? field.nearestDistanceBlocks(key, term.betweenMin, term.betweenMax) : -1;
            return distance >= 0 ? ok(rel(distance, Math.max(1, term.betweenMax))) : fail("cave distance range failed");
        }
        if (bc.isUnderground(b)) {
            int qy = bc.quartYFor(b);
            Modifier effective = (mod == Modifier.UNDER || mod == Modifier.DEFAULT) ? Modifier.NEAR : mod;
            if (effective != Modifier.IN && effective != Modifier.NEAR && effective != Modifier.FAR && effective != Modifier.NEVER) {
                effective = Modifier.NEAR;
            }
            int rad = effective == Modifier.FAR ? (FAR_MAX + 15) / 16
                    : effective == Modifier.NEVER ? biomeRadiusChunks
                    : effective == Modifier.IN ? (IN_BLOCKS + 15) / 16 : (NEAR_BLOCKS + 15) / 16;
            int step = bc.effectiveStep(qy, rad);
            var f = bc.sampleField(ctx, cx, cz, qy, rad, step);
            boolean pass = bc.evalUnderground(f, b, effective, NEAR_BLOCKS, FAR_MIN, FAR_MAX, ctx, cx, cz);
            return pass ? ok(0.25) : fail("cave biome condition failed");
        }

        int surfaceQY = VanillaBiomeChecker.SURFACE_Y >> 2;

        return switch (mod) {
            case NEVER -> {
                boolean present = bc.exists(ctx, cx, cz, biomeRadiusChunks, b);
                yield present ? fail("forbidden biome present") : ok(0);
            }
            case ONLY -> {
                var f = bc.sampleField(ctx, cx, cz, surfaceQY, biomeRadiusChunks, 1);
                var k = bc.keyOf(b);
                int c = (k != null) ? f.count(k, biomeRadiusChunks * 16) : -1;
                yield c == 1 ? ok(0) : fail("only: regions=" + c);
            }
            case BETWEEN -> {
                int d = bc.nearestDistanceBlocks(ctx, cx, cz, (Math.max(1, term.betweenMax) + 15) / 16, b);
                yield (d >= term.betweenMin && d <= term.betweenMax)
                        ? ok(rel(d, Math.max(1, term.betweenMax)))
                        : fail("biome distance " + d + " outside range");
            }
            case FAR -> {
                int d = bc.nearestDistanceBlocks(ctx, cx, cz, (FAR_MAX + 15) / 16, b);
                yield (d >= FAR_MIN && d <= FAR_MAX) ? ok(rel(d, FAR_MAX))
                                                     : fail("biome distance " + d + " outside far band");
            }
            case UNDER -> bc.matchesAt(ctx, cx, cz, b)
                    ? ok(0) : fail("surface biome not under spawn point");
            case IN -> {
                int d = bc.nearestDistanceBlocks(ctx, cx, cz, (IN_BLOCKS + 15) / 16, b);
                yield (d >= 0 && d <= IN_BLOCKS) ? ok(rel(d, IN_BLOCKS))
                                                 : fail("biome distance " + d + " > 64");
            }
            case NEAR -> {
                int d = bc.nearestDistanceBlocks(ctx, cx, cz, (NEAR_BLOCKS + 15) / 16, b);
                yield (d >= 0 && d <= NEAR_BLOCKS) ? ok(rel(d, NEAR_BLOCKS))
                                                   : fail("biome distance " + d + " > 200");
            }
            default -> {
                int d = bc.nearestDistanceBlocks(ctx, cx, cz, biomeRadiusChunks, b);
                yield d >= 0 ? ok(rel(d, biomeRadiusChunks * 16))
                             : fail("required biome absent in radius");
            }
        };
    }

    private static boolean spawnBlockOk(WorldContext ctx, ParsedQuery.Term term, int cx, int cz) {
        String requested = term.canonical;
        if ("any_solid".equals(requested)) return true;
        var predictor = ctx.spawnPredictor;
        if (predictor == null) return true;
        if (!predictor.isPossibleSurfaceBlock(requested)) return true;
        String block = predictor.predict(ctx, cx, cz);
        return block == null || requested.equals(block);
    }

    private static int nearest(List<int[]> pos, int cx, int cz) {
        int min = Integer.MAX_VALUE;
        for (int[] p : pos) {
            long dx = p[0] - cx, dz = p[1] - cz;
            int d = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            if (d < min) min = d;
        }
        return min;
    }

    private static int countWithin(List<int[]> pos, int cx, int cz, int blocks) {
        int c = 0;
        long r2 = (long) blocks * blocks;
        for (int[] p : pos) {
            long dx = p[0] - cx, dz = p[1] - cz;
            if (dx * dx + dz * dz <= r2) c++;
        }
        return c;
    }

    // Closeness in [0,1]: 0 = right on top of the target
    private static double rel(int dist, int threshold) {
        return Math.max(0.0, Math.min(1.0, dist / (double) Math.max(1, threshold)));
    }
}
