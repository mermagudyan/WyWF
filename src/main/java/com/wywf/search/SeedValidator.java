package com.wywf.search;

import com.wywf.core.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SeedValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");

    public enum Reason {
        ACCEPTED("matches"),
        NO_STRUCTURE("required structure not found near spawn"),
        BIOME_MISMATCH("missing required biomes near spawn"),
        SPAWN_MISMATCH("spawn block does not match"),
        OBJECT_MISMATCH("missing required objects");

        public final String description;
        Reason(String description) { this.description = description; }
    }

    public static final class Outcome {
        public final boolean       accepted;
        public final Reason        reason;
        public final SearchResult  result;
        public final boolean       structuresMatched;
        public final int           structureX;
        public final int           structureZ;
        public final String        structureId;
        public final List<String>  matchedStructures;
        public final List<String>  matchedBiomes;

        private Outcome(boolean accepted, Reason reason, SearchResult result,
                        boolean structuresMatched, int sx, int sz, String sid,
                        List<String> matchedStructures, List<String> matchedBiomes) {
            this.accepted = accepted;
            this.reason = reason;
            this.result = result;
            this.structuresMatched = structuresMatched;
            this.structureX = sx;
            this.structureZ = sz;
            this.structureId = sid;
            this.matchedStructures = matchedStructures;
            this.matchedBiomes = matchedBiomes;
        }

        static Outcome accepted(SearchResult r, StructureChecker.Result s,
                               List<String> matchedStructures, List<String> matchedBiomes) {
            return new Outcome(true, Reason.ACCEPTED, r, s.found, s.structureX, s.structureZ,
                    s.structureId, matchedStructures, matchedBiomes);
        }

        static Outcome rejected(Reason reason, StructureChecker.Result s) {
            return new Outcome(false, reason, null, s.found, s.structureX, s.structureZ,
                    s.structureId, List.of(), List.of());
        }
    }

    private final StructureChecker structureChecker;
    private final BiomeChecker     biomeChecker;
    private final int              searchRadiusChunks;
    private final int              biomeRadiusChunks;

    /** Cached term ordering — computed once per query, reused for every seed. */
    private final List<ParsedQuery.Term> cachedTermOrder;

    /** Per-search cache of structure presence: (seed, canonical, radius) -> found.
     *  Avoids recomputing placement for the same seed/structure/radius pair
     *  (e.g. if a seed is revisited). Keyed by the low-48 bits of the seed,
     *  since structure placement does not depend on the high 16 bits.
     *  Capped at MAX_STRUCTURE_CACHE entries to bound heap usage. */
    private static final int MAX_STRUCTURE_CACHE = 8192;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, StructureChecker.Result>> structureCache = new ConcurrentHashMap<>();

    private void evictStructureCacheIfNeeded() {
        if (structureCache.size() > MAX_STRUCTURE_CACHE) {
            structureCache.clear();
        }
    }

    public SeedValidator(StructureChecker sc, BiomeChecker bc,
                         int searchRadiusChunks, int biomeRadiusChunks) {
        this.structureChecker  = sc;
        this.biomeChecker      = bc;
        this.searchRadiusChunks = searchRadiusChunks;
        this.biomeRadiusChunks = biomeRadiusChunks;
        this.cachedTermOrder = List.of(); // initialized per-query via withQuery()
    }

    private SeedValidator(StructureChecker sc, BiomeChecker bc,
                          int searchRadiusChunks, int biomeRadiusChunks,
                          List<ParsedQuery.Term> cachedTermOrder) {
        this.structureChecker  = sc;
        this.biomeChecker      = bc;
        this.searchRadiusChunks = searchRadiusChunks;
        this.biomeRadiusChunks = biomeRadiusChunks;
        this.cachedTermOrder = cachedTermOrder;
    }

    /** Create a copy with cached term ordering for a specific query. */
    public SeedValidator withQuery(ParsedQuery query) {
        return new SeedValidator(structureChecker, biomeChecker,
                searchRadiusChunks, biomeRadiusChunks, reorder(query.terms()));
    }

    public StructureChecker structureChecker() {
        return structureChecker;
    }

    /**
     * Radius (in chunks) that {@link #validate} will actually scan for the given
     * structure term. Because structure placement depends only on the low 48 bits
     * of the seed (identical for every high-16-bit variant), the prefilter can use
     * this exact radius to reject whole 48-bit bases safely.
     */
    public int structureScanRadiusChunks(ParsedQuery.Term term) {
        return switch (term.modifier) {
            case NEAR    -> chunks(NEAR_BLOCKS);
            case IN      -> UNDERGROUND.contains(term.canonical) ? searchRadiusChunks : chunks(IN_ON_BLOCKS);
            case FAR     -> chunks(FAR_MAX_BLOCKS);
            case BETWEEN -> chunks(term.betweenMax);
            case NEVER   -> searchRadiusChunks;
            case ONLY    -> chunks(ONLY_BLOCKS);
            case SOME    -> chunks(effectiveSomeBlocks(term.someCount));
            default      -> searchRadiusChunks;
        };
    }

    private static final int NEAR_BLOCKS      = 200;
    private static final int FAR_MIN_BLOCKS   = 500;
    private static final int FAR_MAX_BLOCKS   = 1000;
    private static final int IN_ON_BLOCKS     = 64;
    private static final int SOME_BLOCKS      = 320;
    private static final int SOME_BLOCKS_LARGE = 640;
    private static final int ONLY_BLOCKS      = 500;

    static int effectiveSomeBlocks(int someCount) {
        return someCount >= 5 ? SOME_BLOCKS_LARGE : SOME_BLOCKS;
    }

    private static final Set<String> UNDERGROUND = Set.of(
            "minecraft:stronghold", "minecraft:mineshaft",
            "minecraft:ancient_city", "minecraft:trial_chambers");

    /**
     * Vanilla-parity spawn estimate: climate spiral first, terrain-checked,
     * nearby-offset search only in deep mode.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SPAWN_LOG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger SPAWN_FALLBACK_WARN =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final Set<ResourceKey<Biome>> SPAWN_BIOMES = Set.of(
            net.minecraft.world.level.biome.Biomes.PLAINS,
            net.minecraft.world.level.biome.Biomes.FOREST,
            net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS,
            net.minecraft.world.level.biome.Biomes.MEADOW,
            net.minecraft.world.level.biome.Biomes.TAIGA
    );

    /**
     * Spiral land search when both the native finder and the vanilla sampler
     * fail (ocean-heavy origins). Phase 1: classic spawn biomes; phase 2:
     * any non-water land. Height from the seed-correct density sampler.
     */
    private static int[] spiralLandSearch(WorldContext ctx) {
        if (ctx.biomeSource == null || ctx.sampler() == null) return null;
        Climate.Sampler sampler = ctx.sampler();
        int sea = ctx.noiseSettings().seaLevel();
        Set<String> water = ctx.spawnPredictor != null
                ? ctx.spawnPredictor.waterBiomes() : Set.of();
        int x = 0, z = 0, dx = 0, dz = -1, steps = 1, turn = 0;
        for (int i = 0; i < 512; i++) {
            int bx = x * 16, bz = z * 16;
            Holder<Biome> h = ctx.biomeSource.getNoiseBiome(bx >> 2, VanillaBiomeChecker.SURFACE_Y >> 2, bz >> 2, sampler);
            ResourceKey<Biome> k = (h == null) ? null : h.unwrapKey().orElse(null);
            boolean good = false;
            if (k != null) {
                if (SPAWN_BIOMES.contains(k)) good = true;
                else if (i >= 256 && !water.contains(k.identifier().toString())) good = true;
            }
            if (good && ctx.computeHeight(bx, bz) > sea) {
                return new int[]{bx, bz};
            }
            x += dx; z += dz; turn++;
            if (turn == steps) {
                turn = 0;
                int tmp = dx; dx = -dz; dz = tmp;
                if (dz == 0) steps++;
            }
        }
        return null;
    }

    /**
     * @param deep true = also search nearby offsets when the point is below
     *             sea level (expensive); bulk phase passes false and accepts
     *             the raw point — DeepVerifier re-checks finalists precisely.
     */
    public static int[] findApproxSpawnPos(WorldContext ctx, boolean deep) {
        int cx = 0, cz = 0;

        // Priority 1: native cubiomes getSpawn() — vanilla-parity algorithm
        if (CubiomesBridge.isActive()) {
            try {
                int[] nativeSpawn = CubiomesBridge.getSpawn();
                if (nativeSpawn != null && (nativeSpawn[0] != 0 || nativeSpawn[1] != 0)) {
                    cx = nativeSpawn[0];
                    cz = nativeSpawn[1];
                    if (SPAWN_LOG_COUNTER.get() < 30) {
                        SPAWN_LOG_COUNTER.incrementAndGet();
                        LOGGER.debug("[findApproxSpawnPos] native getSpawn -> ({},{})", cx, cz);
                    }
                    return adjustForTerrain(ctx, cx, cz, deep);
                }
                if (SPAWN_LOG_COUNTER.get() < 30) {
                    SPAWN_LOG_COUNTER.incrementAndGet();
                    LOGGER.debug("[findApproxSpawnPos] CubiomesBridge.getSpawn() returned ({},{}), trying MC sampler",
                            nativeSpawn == null ? "null" : nativeSpawn[0],
                            nativeSpawn == null ? "" : nativeSpawn[1]);
                }
            } catch (Throwable t) {
                LOGGER.warn("[findApproxSpawnPos] CubiomesBridge.getSpawn() failed: {}", t.getMessage());
                if (SPAWN_LOG_COUNTER.get() < 30) {
                    SPAWN_LOG_COUNTER.incrementAndGet();
                }
            }
        }

        // Priority 2: MC sampler findSpawnPosition()
        BlockPos pos = null;
        try {
            pos = ctx.sampler().findSpawnPosition();
        } catch (Throwable t) {
            LOGGER.warn("[findApproxSpawnPos] MC sampler failed: {}", t.getMessage());
        }
        LOGGER.debug("[findApproxSpawnPos] MC sampler findSpawnPosition() → {}", pos == null ? "null" : "(" + pos.getX() + "," + pos.getZ() + ")");
        if (pos != null && (pos.getX() != 0 || pos.getZ() != 0)) {
            cx = pos.getX();
            cz = pos.getZ();
        } else {
            // Both finders failed — vanilla itself would fall back to (8,8)
            // here. Before doing the same, try our own spiral over spawn-
            // suitable land using the (now seed-correct) terrain sampler.
            int[] spiral = spiralLandSearch(ctx);
            if (spiral != null) {
                cx = spiral[0];
                cz = spiral[1];
                if (SPAWN_LOG_COUNTER.get() < 30) {
                    SPAWN_LOG_COUNTER.incrementAndGet();
                    LOGGER.debug("[findApproxSpawnPos] spiral fallback -> ({},{})", cx, cz);
                }
            } else {
                int n = SPAWN_FALLBACK_WARN.getAndIncrement();
                String msg = "[findApproxSpawnPos] no spawn found for seed {} — vanilla also spawns at (8,8) here";
                if (n < 5) LOGGER.warn(msg, ctx.seed);
                else LOGGER.debug(msg, ctx.seed);
                cx = 8;
                cz = 8;
            }
        }

        if (SPAWN_LOG_COUNTER.get() < 30) {
            SPAWN_LOG_COUNTER.incrementAndGet();
            LOGGER.debug("[findApproxSpawnPos] findSpawnPosition()={} → ({},{})",
                    pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                    cx, cz);
        }

        return adjustForTerrain(ctx, cx, cz, true);
    }

    private static int[] adjustForTerrain(WorldContext ctx, int cx, int cz, boolean deep) {
        int baseHeight = ctx.computeHeight(cx, cz);
        int seaLevel = ctx.noiseSettings().seaLevel();
        if (baseHeight > seaLevel) {
            return new int[]{cx, cz};
        }
        // Bulk phase: skip the offset scan (up to 12 extra density-column scans);
        // DeepVerifier re-checks finalists with deep=true semantics anyway.
        if (!deep) {
            return new int[]{cx, cz};
        }

        int[][] offsets = {{8,0},{-8,0},{0,8},{0,-8},{16,0},{-16,0},{0,16},{0,-16},{24,0},{-24,0},{0,24},{0,-24}};
        int adjustedX = cx, adjustedZ = cz, bestHeight = baseHeight;
        for (int[] off : offsets) {
            int nx = cx + off[0], nz = cz + off[1];
            int h = ctx.computeHeight(nx, nz);
            if (h > bestHeight && h > seaLevel) {
                bestHeight = h;
                adjustedX = nx;
                adjustedZ = nz;
            }
        }
        return new int[]{adjustedX, adjustedZ};
    }

    /** Entry for callers that already hold a per-seed context (avoids re-create). */
    public Outcome validate(WorldContext ctx, ParsedQuery query, int cx, int cz) {
        return validate(ctx, ctx.seed, query, cx, cz);
    }

    public Outcome validate(WorldContextFactory factory, long seed, ParsedQuery query, boolean accurateRings) {
        WorldContext ctx = factory.create(seed, accurateRings);
        return validate(ctx, seed, query, 0, 0);
    }

    public Outcome validate(WorldContextFactory factory, long seed, ParsedQuery query,
                            boolean accurateRings, int cx, int cz) {
        WorldContext ctx = factory.create(seed, accurateRings);
        return validate(ctx, seed, query, cx, cz);
    }

    private Outcome validate(WorldContext ctx, long seed, ParsedQuery query, int cx, int cz) {
        StructureChecker.Result spawnStruct = StructureChecker.Result.notFound();

        List<ParsedQuery.Term> terms = cachedTermOrder.isEmpty() ? reorder(query.terms()) : cachedTermOrder;
        Map<Integer, BiomeField> fields = sampleBiomeFields(ctx, cx, cz, terms);

        List<String> matchedStructures = null;
        List<String> structModifiers = null;
        List<String> matchedBiomes = null;
        List<String> biomeModifiers = null;
        Map<String, int[]> structurePositions = null;
        Map<String, Integer> biomeDistances = null;
        // Report distance with the SAME radius the term was validated against,
        // so far/between biomes don't print misleading -1.
        Map<String, Integer> biomeReportRadius = null;

        for (ParsedQuery.Term term : terms) {
            switch (term.category) {
                case STRUCTURE -> {
                    StructureChecker.Result r = evalStructureTerm(ctx, cx, cz, term);
                    if (r == null) return Outcome.rejected(Reason.NO_STRUCTURE, spawnStruct);
                    if (r.found) {
                        if (matchedStructures == null) { matchedStructures = new ArrayList<>(4); structModifiers = new ArrayList<>(4); }
                        matchedStructures.add(term.canonical);
                        structModifiers.add(term.modifier.name());
                        if (term.modifier == Modifier.SOME || term.modifier == Modifier.ONLY
                                || term.modifier == Modifier.BETWEEN) {
                            if (structurePositions == null) structurePositions = new HashMap<>(4);
                            int scanR = structureScanRadiusChunks(term);
                            List<int[]> all = structureChecker.positions(ctx, cx, cz, scanR, term.canonical);
                            int idx = 0;
                            for (int[] p : all) {
                                int d = dist(p, cx, cz);
                                if (term.modifier == Modifier.SOME && d > effectiveSomeBlocks(term.someCount)) continue;
                                if (term.modifier == Modifier.BETWEEN
                                        && (d < term.betweenMin || d > term.betweenMax)) continue;
                                if (term.modifier == Modifier.ONLY && d > ONLY_BLOCKS) continue;
                                structurePositions.put(term.canonical + (idx > 0 ? "#" + (idx + 1) : ""),
                                        new int[]{p[0], p[1]});
                                idx++;
                            }
                        } else {
                            if (structurePositions == null) structurePositions = new HashMap<>(4);
                            structurePositions.put(term.canonical,
                                    new int[]{r.structureX, r.structureZ});
                        }
                        if (!spawnStruct.found) spawnStruct = r;
                    }
                }
                case BIOME -> {
                    if (!evalBiomeTerm(ctx, cx, cz, term, fields)) {
                        return Outcome.rejected(Reason.BIOME_MISMATCH, spawnStruct);
                    }
                    if (matchedBiomes == null) { matchedBiomes = new ArrayList<>(2); biomeModifiers = new ArrayList<>(2);
                            biomeReportRadius = new HashMap<>(2); }
                    matchedBiomes.add(term.canonical);
                    biomeModifiers.add(term.modifier.name());
                    biomeReportRadius.merge(term.canonical, biomeTermRadiusChunks(term), Math::max);
                }
                case SPAWN -> {
                    if (!evalSpawnTerm(ctx, cx, cz, term, seed)) {
                        return Outcome.rejected(Reason.SPAWN_MISMATCH, spawnStruct);
                    }
                }
                case OBJECT -> {
                }
            }
        }

        if (matchedBiomes != null) {
            biomeDistances = new HashMap<>(matchedBiomes.size());
            for (String biome : matchedBiomes) {
                int radius = biomeReportRadius.getOrDefault(biome, biomeRadiusChunks);
                int d = biomeChecker.nearestDistanceBlocks(ctx, cx, cz, radius, biome);
                biomeDistances.put(biome, d >= 0 ? d : -1);
            }
        }

        String desc = spawnStruct.found ? spawnStruct.structureId
                : (!query.biomes().isEmpty() ? query.biomes().get(0) : "origin");
        int sx = spawnStruct.found ? spawnStruct.structureX : cx;
        int sz = spawnStruct.found ? spawnStruct.structureZ : cz;
        SearchResult result = new SearchResult(seed, cx, cz, sx, sz, desc,
                matchedStructures != null ? matchedStructures : List.of(),
                matchedBiomes != null ? matchedBiomes : List.of(), "",
                structurePositions != null ? structurePositions : Map.of(),
                biomeDistances != null ? biomeDistances : Map.of());

        AuditLogger.logSeed(seed, cx, cz,
                query.raw() != null ? query.raw() : "",
                matchedStructures, structModifiers, structurePositions,
                matchedBiomes, biomeModifiers, biomeDistances, true);

        return Outcome.accepted(result, spawnStruct,
                matchedStructures != null ? matchedStructures : List.of(),
                matchedBiomes != null ? matchedBiomes : List.of());
    }

    private StructureChecker.Result evalStructureTerm(WorldContext ctx, int cx, int cz, ParsedQuery.Term term) {
        String canonical = term.canonical;
        Modifier mod = term.modifier;

        if (mod == Modifier.NEVER) {
            int[] p = structureChecker.firstPosition(ctx, cx, cz, searchRadiusChunks, canonical);
            return p == null ? StructureChecker.Result.notFound() : null;
        }

        if (mod == Modifier.FAR) {
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, chunks(FAR_MAX_BLOCKS), canonical);
            if (pos.isEmpty()) return null;
            int[] nearest = null;
            int nearestDist = Integer.MAX_VALUE;
            for (int[] p : pos) {
                int d = dist(p, cx, cz);
                if (d < nearestDist) { nearestDist = d; nearest = p; }
            }
            if (nearestDist < FAR_MIN_BLOCKS || nearest == null) return null;
            return StructureChecker.Result.found(nearest[0], nearest[1], canonical);
        }

        if (mod == Modifier.SOME) {
            int effectiveBlocks = effectiveSomeBlocks(term.someCount);
            int someChunks = chunks(effectiveBlocks);
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, someChunks, canonical);
            int count = 0;
            int[] best = null; int bestD = Integer.MAX_VALUE;
            for (int[] p : pos) {
                int d = dist(p, cx, cz);
                if (d <= effectiveBlocks) count++;
                if (d < bestD) { bestD = d; best = p; }
            }
            if (count < term.someCount || best == null) return null;
            return StructureChecker.Result.found(best[0], best[1], canonical);
        }

        if (mod == Modifier.ONLY) {
            int onlyChunks = chunks(ONLY_BLOCKS);
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, onlyChunks, canonical);
            int count = 0;
            int[] best = null; int bestD = Integer.MAX_VALUE;
            for (int[] p : pos) {
                int d = dist(p, cx, cz);
                if (d <= ONLY_BLOCKS) count++;
                if (d < bestD) { bestD = d; best = p; }
            }
            if (count != 1 || best == null) return null;
            return StructureChecker.Result.found(best[0], best[1], canonical);
        }

        if (mod == Modifier.BETWEEN) {
            int maxChunks = chunks(term.betweenMax);
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, maxChunks, canonical);
            int[] best = null; int bestD = Integer.MAX_VALUE;
            for (int[] p : pos) {
                int d = dist(p, cx, cz);
                if (d >= term.betweenMin && d <= term.betweenMax && d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best == null) return null;
            return StructureChecker.Result.found(best[0], best[1], canonical);
        }

        int scanChunks = switch (mod) {
            case NEAR   -> chunks(NEAR_BLOCKS);
            case IN     -> UNDERGROUND.contains(canonical) ? searchRadiusChunks : chunks(IN_ON_BLOCKS);
            default     -> searchRadiusChunks;
        };

        int[] found = cachedFirstPosition(ctx, cx, cz, scanChunks, canonical);
        if (found == null) return null;

        int distance = dist(found, cx, cz);
        if (mod == Modifier.IN && distance > IN_ON_BLOCKS) return null;
        if (mod == Modifier.NEAR && distance > NEAR_BLOCKS) return null;

        LOGGER.info("[SeedValidator] seed {} structure FOUND: {} @({},{}) center=({},{}) dist={} scanChunks={}",
                ctx.seed, canonical, found[0], found[1], cx, cz, distance, scanChunks);
        return StructureChecker.Result.found(found[0], found[1], canonical);
    }

    private int[] cachedFirstPosition(WorldContext ctx, int cx, int cz,
                                       int radiusChunks, String canonical) {
        evictStructureCacheIfNeeded();
        long base = ctx.seed;
        ConcurrentHashMap<Long, StructureChecker.Result> byStruct =
                structureCache.computeIfAbsent(base, k -> new ConcurrentHashMap<>());
        // Composite key: pack canonical.hashCode (32 bits) + radiusChunks (16 bits) + cx (32 bits) + cz (32 bits)
        long key = ((long) canonical.hashCode() & 0xFFFFFFFFL) << 32
                | ((long) radiusChunks & 0xFFFFL);
        key ^= ((long) cx) * 0x9E3779B97F4A7C15L;
        key ^= ((long) cz) * 0x517CC1B727220A95L;
        StructureChecker.Result cached = byStruct.get(key);
        if (cached != null) {
            return cached.found ? new int[]{cached.structureX, cached.structureZ} : null;
        }
        int[] found = structureChecker.firstPosition(ctx, cx, cz, radiusChunks, canonical);
        byStruct.put(key, found != null
                ? StructureChecker.Result.found(found[0], found[1], canonical)
                : StructureChecker.Result.notFound());
        return found;
    }

    private boolean evalSpawnTerm(WorldContext ctx, int cx, int cz, ParsedQuery.Term term, long seed) {
        String requested = term.canonical;
        if ("any_solid".equals(requested)) return true;

        SpawnBlockPredictor predictor = ctx.spawnPredictor;
        if (predictor == null) return true;
        if (!predictor.isPossibleSurfaceBlock(requested)) return true;

        String block = predictor.predict(ctx, cx, cz);
        if (block == null) return true;
        return requested.equals(block);
    }

    private boolean evalBiomeTerm(WorldContext ctx, int cx, int cz, ParsedQuery.Term term,
                                   Map<Integer, BiomeField> fields) {
        if (fields.isEmpty()) return evalBiomeTermDirect(ctx, cx, cz, term);

        String canonical = term.canonical;
        Modifier mod = term.modifier;

        if (biomeChecker instanceof VanillaBiomeChecker vbc && vbc.isUnderground(canonical)) {
            BiomeField f = fields.get(vbc.quartYFor(canonical));
            return f != null && vbc.evalUnderground(f, canonical, mod, NEAR_BLOCKS, FAR_MIN_BLOCKS, FAR_MAX_BLOCKS, ctx, cx, cz);
        }

        if (!(biomeChecker instanceof VanillaBiomeChecker vbc)) return false;
        ResourceKey<Biome> key = vbc.keyOf(canonical);
        if (key == null) return false;

        BiomeField f = fields.get(VanillaBiomeChecker.SURFACE_Y >> 2);
        if (f == null) return false;

        return switch (mod) {
            case IN -> f.exists(key, IN_ON_BLOCKS);
            case UNDER -> biomeChecker.matchesAt(ctx, cx, cz, canonical);
            case NEAR -> f.exists(key, NEAR_BLOCKS);
            case FAR -> {
                int d = f.nearestDistanceBlocks(key, FAR_MIN_BLOCKS, FAR_MAX_BLOCKS);
                yield d >= 0;
            }
            case NEVER -> !f.exists(key, biomeRadiusChunks * 16);
            case ONLY -> f.count(key, biomeRadiusChunks * 16) == 1;
            case BETWEEN -> {
                int d = f.nearestDistanceBlocks(key, term.betweenMin, term.betweenMax);
                yield d >= term.betweenMin && d <= term.betweenMax;
            }
            default -> f.exists(key, biomeRadiusChunks * 16);
        };
    }

    /**
     * Single-biome-term path: sample directly with early-exit so a near match is
     * found without scanning the whole grid (e.g. {@code near} uses {@code exists},
     * which returns on the first hit instead of computing distances for every point).
     */
    private boolean evalBiomeTermDirect(WorldContext ctx, int cx, int cz, ParsedQuery.Term term) {
        String canonical = term.canonical;
        Modifier mod = term.modifier;

        if (biomeChecker instanceof VanillaBiomeChecker vbc && vbc.isUnderground(canonical)) {
            int qy = vbc.quartYFor(canonical);
            int rChunks = biomeTermRadiusChunks(term);
            int step = vbc.effectiveStep(qy, rChunks);
            BiomeField f = biomeChecker.sampleField(ctx, cx, cz, qy, rChunks, step);
            return vbc.evalUnderground(f, canonical, mod, NEAR_BLOCKS, FAR_MIN_BLOCKS, FAR_MAX_BLOCKS, ctx, cx, cz);
        }

        int rChunks = biomeTermRadiusChunks(term);
        int surfaceStep = (biomeChecker instanceof VanillaBiomeChecker vbc2)
                ? vbc2.effectiveStep(VanillaBiomeChecker.SURFACE_Y >> 2, rChunks) : 4;

        return switch (mod) {
            case IN -> biomeChecker.exists(ctx, cx, cz, chunks(IN_ON_BLOCKS), canonical);
            case UNDER -> biomeChecker.matchesAt(ctx, cx, cz, canonical);
            case NEAR -> biomeChecker.exists(ctx, cx, cz, chunks(NEAR_BLOCKS), canonical);
            case FAR -> {
                int d = biomeChecker.nearestDistanceBlocks(ctx, cx, cz, chunks(FAR_MAX_BLOCKS), canonical);
                yield d >= FAR_MIN_BLOCKS && d <= FAR_MAX_BLOCKS;
            }
            case NEVER -> !biomeChecker.exists(ctx, cx, cz, biomeRadiusChunks, canonical);
            case ONLY -> {
                BiomeField f2 = biomeChecker.sampleField(ctx, cx, cz,
                        VanillaBiomeChecker.SURFACE_Y >> 2, biomeRadiusChunks, surfaceStep);
                ResourceKey<Biome> k2 = (biomeChecker instanceof VanillaBiomeChecker v) ? v.keyOf(canonical) : null;
                yield k2 != null && f2.count(k2, biomeRadiusChunks * 16) == 1;
            }
            case BETWEEN -> {
                int bRadius = biomeTermRadiusChunks(term);
                BiomeField f2 = biomeChecker.sampleField(ctx, cx, cz,
                        VanillaBiomeChecker.SURFACE_Y >> 2, bRadius, surfaceStep);
                ResourceKey<Biome> k2 = (biomeChecker instanceof VanillaBiomeChecker v) ? v.keyOf(canonical) : null;
                int d = k2 != null ? f2.nearestDistanceBlocks(k2, term.betweenMin, term.betweenMax) : -1;
                yield d >= term.betweenMin && d <= term.betweenMax;
            }
            default -> biomeChecker.exists(ctx, cx, cz, biomeRadiusChunks, canonical);
        };
    }

    /**
     * Samples the biome grid once per Y-level needed by the biome terms and reuses
     * it across all of them. Only worth it when there is more than one biome term;
     * a single term is handled by {@link #evalBiomeTermDirect} (which early-exits).
     */
    private Map<Integer, BiomeField> sampleBiomeFields(WorldContext ctx, int cx, int cz, List<ParsedQuery.Term> terms) {
        List<ParsedQuery.Term> biomeTerms = terms.stream()
                .filter(t -> t.category == KeywordDictionary.Category.BIOME)
                .toList();
        if (biomeTerms.size() <= 1) return Map.of();

        VanillaBiomeChecker vbc = (biomeChecker instanceof VanillaBiomeChecker x) ? x : null;
        Map<Integer, Integer> maxRadius = new HashMap<>();
        for (ParsedQuery.Term t : biomeTerms) {
            int qy = (vbc != null && vbc.isUnderground(t.canonical))
                    ? vbc.quartYFor(t.canonical) : (VanillaBiomeChecker.SURFACE_Y >> 2);
            maxRadius.merge(qy, biomeTermRadiusChunks(t), Math::max);
        }

        Map<Integer, BiomeField> fields = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : maxRadius.entrySet()) {
            int step = (vbc != null) ? vbc.effectiveStep(e.getKey(), e.getValue()) : 4;
            fields.put(e.getKey(), biomeChecker.sampleField(ctx, cx, cz, e.getKey(), e.getValue(), step));
        }
        return fields;
    }

    private int biomeTermRadiusChunks(ParsedQuery.Term term) {
        return switch (term.modifier) {
            case IN      -> chunks(IN_ON_BLOCKS);
            case NEAR    -> chunks(NEAR_BLOCKS);
            case FAR     -> chunks(FAR_MAX_BLOCKS);
            case BETWEEN -> chunks(term.betweenMax);
            default      -> biomeRadiusChunks;
        };
    }

    /** Cheap checks first (structure/spawn, then positive biome terms) so a rejecting
     *  term is reached with minimal work; expensive {@code far}/{@code never} biome
     *  scans run last. */
    private static List<ParsedQuery.Term> reorder(List<ParsedQuery.Term> terms) {
        List<ParsedQuery.Term> out = new ArrayList<>(terms);
        out.sort(Comparator.comparingInt(SeedValidator::termRank));
        return out;
    }

    private static int termRank(ParsedQuery.Term t) {
        return switch (t.category) {
            case STRUCTURE -> 0;
            case SPAWN -> 1;
            case BIOME -> (t.modifier == Modifier.FAR || t.modifier == Modifier.NEVER) ? 3 : 2;
            default -> 4;
        };
    }

    private static int chunks(int blocks) {
        return (blocks + 15) / 16;
    }

    private static int dist(int[] p, int cx, int cz) {
        long dx = p[0] - cx;
        long dz = p[1] - cz;
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    /** Squared distance — avoids sqrt for comparisons. */
    static long distSq(int[] p, int cx, int cz) {
        long dx = p[0] - cx;
        long dz = p[1] - cz;
        return dx * dx + dz * dz;
    }
}
