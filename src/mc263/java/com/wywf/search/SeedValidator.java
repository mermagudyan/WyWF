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

    // Term order, cached once per query and reused for every seed
    private final List<ParsedQuery.Term> cachedTermOrder;

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

    // Copy with term order cached for one query
    public SeedValidator withQuery(ParsedQuery query) {
        return new SeedValidator(structureChecker, biomeChecker,
                searchRadiusChunks, biomeRadiusChunks, reorder(query.terms()));
    }

    public StructureChecker structureChecker() {
        return structureChecker;
    }

    // Scan radius validate() uses; placement is low-48-only so prefilter rejects whole bases
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

    private static final java.util.concurrent.atomic.AtomicInteger SPAWN_LOG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger SPAWN_FALLBACK_WARN =
            new java.util.concurrent.atomic.AtomicInteger();

    // Max spawn drift from origin assumed by the expanded prefilters
    public static final int SPAWN_DRIFT_BLOCKS = 2048;

    // Diagnostics: where each bulk spawn estimate came from
    public static final java.util.concurrent.atomic.AtomicLong SPAWN_NATIVE_HIT =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SPAWN_WET_HIT =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SPAWN_MC_HIT =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SPAWN_SPIRAL_HIT =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SPAWN_HARD_FALLBACK =
            new java.util.concurrent.atomic.AtomicLong();

    public static String spawnStats() {
        return "spawn[nativeDry=" + SPAWN_NATIVE_HIT.get()
                + " nativeWet=" + SPAWN_WET_HIT.get()
                + " mc=" + SPAWN_MC_HIT.get()
                + " spiral=" + SPAWN_SPIRAL_HIT.get()
                + " fallback88=" + SPAWN_HARD_FALLBACK.get() + "]";
    }

    public static void resetSpawnStats() {
        SPAWN_NATIVE_HIT.set(0);
        SPAWN_WET_HIT.set(0);
        SPAWN_MC_HIT.set(0);
        SPAWN_SPIRAL_HIT.set(0);
        SPAWN_HARD_FALLBACK.set(0);
    }

    // Distance limit evalStructureTerm enforces for DEFAULT/NEAR/IN (keep in sync); used by the placement gate
    public int structureGateLimitBlocks(ParsedQuery.Term term) {
        return switch (term.modifier) {
            case IN -> IN_ON_BLOCKS;
            case NEAR -> NEAR_BLOCKS;
            default -> structureScanRadiusChunks(term) * 16;
        };
    }

    private static final Set<ResourceKey<Biome>> SPAWN_BIOMES = Set.of(
            net.minecraft.world.level.biome.Biomes.PLAINS,
            net.minecraft.world.level.biome.Biomes.FOREST,
            net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS,
            net.minecraft.world.level.biome.Biomes.MEADOW,
            net.minecraft.world.level.biome.Biomes.TAIGA
    );

    // Last-resort land spiral when native + MC finders fail (spawn biomes first, then any land)
    // Cubiomes ids for the Java SPAWN_BIOMES set, index lookup avoids boxing
    private static final boolean[] SPAWN_BIOME = buildIdTable(1, 4, 129, 177, 5);
    // Cubiomes water ids, excluded in the any-land second pass
    private static final boolean[] WATER_BIOME = buildIdTable(0, 7, 10, 11, 24, 44, 45, 46, 47, 48, 49, 50);

    private static boolean[] buildIdTable(int... ids) {
        boolean[] t = new boolean[256];
        for (int id : ids) t[id] = true;
        return t;
    }

    private static boolean isSpawnBiomeId(int id) {
        return id >= 0 && id < SPAWN_BIOME.length && SPAWN_BIOME[id];
    }

    private static boolean isWaterBiomeId(int id) {
        return id >= 0 && id < WATER_BIOME.length && WATER_BIOME[id];
    }

    private static int[] spiralLandSearch(WorldContext ctx) {
        if (ctx.biomeSource == null || ctx.sampler() == null) return null;
        // One ~1ms bulk grid sample replaces up to 512 MC lookups
        if (CubiomesBridge.isActive()) {
            int r = 20, step = 2, y = VanillaBiomeChecker.SURFACE_Y;
            int n = (2 * r / step + 1);
            int[] grid = new int[n * n];
            int got = CubiomesBridge.sampleBiomeGrid(y, 0, 0, r, step, grid);
            if (got > 0) {
                int sea = ctx.noiseSettings().seaLevel();
                // Pass 0: nearest spawn biome wins directly, land by definition
                int bestX = 0, bestZ = 0, bestD = Integer.MAX_VALUE;
                for (int i = 0; i < got; i++) {
                    if (!isSpawnBiomeId(grid[i])) continue;
                    int gx = (i / n) * step - r, gz = (i % n) * step - r;
                    int d = gx * gx + gz * gz;
                    if (d < bestD) { bestD = d; bestX = gx * 16; bestZ = gz * 16; }
                }
                if (bestD != Integer.MAX_VALUE) return new int[]{bestX, bestZ};
                // Pass 1: three nearest non-water cells, height-confirm in order
                int d1 = Integer.MAX_VALUE, d2 = Integer.MAX_VALUE, d3 = Integer.MAX_VALUE;
                int x1 = 0, z1 = 0, x2 = 0, z2 = 0, x3 = 0, z3 = 0;
                for (int i = 0; i < got; i++) {
                    if (isWaterBiomeId(grid[i])) continue;
                    int gx = (i / n) * step - r, gz = (i % n) * step - r;
                    int d = gx * gx + gz * gz;
                    if (d < d1) {
                        d3 = d2; x3 = x2; z3 = z2;
                        d2 = d1; x2 = x1; z2 = z1;
                        d1 = d; x1 = gx * 16; z1 = gz * 16;
                    } else if (d < d2) {
                        d3 = d2; x3 = x2; z3 = z2;
                        d2 = d; x2 = gx * 16; z2 = gz * 16;
                    } else if (d < d3) {
                        d3 = d; x3 = gx * 16; z3 = gz * 16;
                    }
                }
                if (d1 != Integer.MAX_VALUE && ctx.computeHeight(x1, z1) > sea) return new int[]{x1, z1};
                if (d2 != Integer.MAX_VALUE && ctx.computeHeight(x2, z2) > sea) return new int[]{x2, z2};
                if (d3 != Integer.MAX_VALUE && ctx.computeHeight(x3, z3) > sea) return new int[]{x3, z3};
                return null;
            }
        }
        int sea = ctx.noiseSettings().seaLevel();
        Set<String> water = ctx.spawnPredictor != null
                ? ctx.spawnPredictor.waterBiomes() : Set.of();
        int x = 0, z = 0, dx = 0, dz = -1, steps = 1, turn = 0;
        for (int i = 0; i < 512; i++) {
            int bx = x * 16, bz = z * 16;
            Holder<Biome> h = ctx.noiseBiomeAt(bx >> 2, VanillaBiomeChecker.SURFACE_Y >> 2, bz >> 2);
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

    // Vanilla-parity spawn estimate; deep=true also probes offsets below sea level, bulk passes false
    public static int[] findApproxSpawnPos(WorldContext ctx, boolean deep) {
        int cx = 0, cz = 0;

        // Native spawn estimate, usable even over ocean
        if (CubiomesBridge.isActive()) {
            try {
                int[] nativeSpawn = CubiomesBridge.estimateSpawn();
                if (nativeSpawn != null && (nativeSpawn[0] != 0 || nativeSpawn[1] != 0)) {
                    cx = nativeSpawn[0];
                    cz = nativeSpawn[1];
                    // Bulk skips the height column, counts the hit as dry
                    boolean dry = true;
                    if (deep) {
                        dry = ctx.computeHeight(cx, cz) > ctx.noiseSettings().seaLevel();
                    }
                    if (dry) {
                        SPAWN_NATIVE_HIT.incrementAndGet();
                    } else {
                        SPAWN_WET_HIT.incrementAndGet();
                    }
                    if (SPAWN_LOG_COUNTER.get() < 30) {
                        SPAWN_LOG_COUNTER.incrementAndGet();
                        LOGGER.debug("[findApproxSpawnPos] native estimateSpawn -> ({},{}) dry={}", cx, cz, dry);
                    }
                    // Bulk takes the raw point (see below); deep re-checks terrain
                    if (!deep) {
                        return new int[]{cx, cz};
                    }
                    return adjustForTerrain(ctx, cx, cz, deep);
                }
                if (SPAWN_LOG_COUNTER.get() < 30) {
                    SPAWN_LOG_COUNTER.incrementAndGet();
                        LOGGER.debug("[findApproxSpawnPos] CubiomesBridge.estimateSpawn() returned ({},{}), trying fallbacks",
                            nativeSpawn == null ? "null" : nativeSpawn[0],
                            nativeSpawn == null ? "" : nativeSpawn[1]);
                }
            } catch (Throwable t) {
                if (WyWFDebug.ENABLED) LOGGER.warn("[findApproxSpawnPos] CubiomesBridge.estimateSpawn() failed: {}", t.getMessage());
                if (SPAWN_LOG_COUNTER.get() < 30) {
                    SPAWN_LOG_COUNTER.incrementAndGet();
                }
            }
        }

        // Grid spiral, then vanilla (8,8): the MC sampler is gone in 26.3
        int[] spiral = spiralLandSearch(ctx);
        if (spiral != null) {
            cx = spiral[0];
            cz = spiral[1];
            SPAWN_SPIRAL_HIT.incrementAndGet();
            if (SPAWN_LOG_COUNTER.get() < 30) {
                SPAWN_LOG_COUNTER.incrementAndGet();
                LOGGER.debug("[findApproxSpawnPos] spiral fallback -> ({},{})", cx, cz);
            }
        } else {
            // All finders failed — vanilla itself falls back to (8,8)
            int n = SPAWN_FALLBACK_WARN.getAndIncrement();
            String msg = "[findApproxSpawnPos] no spawn found for seed {} — vanilla also spawns at (8,8) here";
            if (WyWFDebug.ENABLED) LOGGER.warn(msg, ctx.seed);
            SPAWN_HARD_FALLBACK.incrementAndGet();
            cx = 8;
            cz = 8;
        }

        // Bulk uses the raw point, height result would be discarded
        if (!deep) {
            return new int[]{cx, cz};
        }
        return adjustForTerrain(ctx, cx, cz, deep);
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

    // Entry for callers holding a per-seed context (no re-create)
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
                            // Reuse the list evalStructureTerm already scanned, same radius
                            List<int[]> all = r.candidates != null ? r.candidates
                                    : structureChecker.positions(ctx, cx, cz, structureScanRadiusChunks(term), term.canonical);
                            long loSq = 0, hiSq = Long.MAX_VALUE;
                            if (term.modifier == Modifier.SOME) {
                                int eff = effectiveSomeBlocks(term.someCount);
                                hiSq = (long) eff * eff + eff;
                            } else if (term.modifier == Modifier.BETWEEN) {
                                if (term.betweenMin > 0) {
                                    loSq = (long) term.betweenMin * term.betweenMin - term.betweenMin + 1;
                                }
                                hiSq = (long) term.betweenMax * term.betweenMax + term.betweenMax;
                            } else {
                                hiSq = (long) ONLY_BLOCKS * ONLY_BLOCKS + ONLY_BLOCKS;
                            }
                            int idx = 0;
                            for (int[] p : all) {
                                long d2 = distSq(p, cx, cz);
                                if (d2 < loSq || d2 > hiSq) continue;
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
            long nearestD2 = Long.MAX_VALUE;
            for (int[] p : pos) {
                long d2 = distSq(p, cx, cz);
                if (d2 < nearestD2) { nearestD2 = d2; nearest = p; }
            }
            // round(sqrt) < R ⟺ d2 <= R*R-R, exact on integers
            if (nearest == null || nearestD2 <= (long) FAR_MIN_BLOCKS * FAR_MIN_BLOCKS - FAR_MIN_BLOCKS) return null;
            return StructureChecker.Result.found(nearest[0], nearest[1], canonical);
        }

        if (mod == Modifier.SOME) {
            int effectiveBlocks = effectiveSomeBlocks(term.someCount);
            int someChunks = chunks(effectiveBlocks);
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, someChunks, canonical);
            // round(sqrt) <= R ⟺ d2 <= R*R+R, exact on integers
            long limitSq = (long) effectiveBlocks * effectiveBlocks + effectiveBlocks;
            int count = 0;
            int[] best = null; long bestD2 = Long.MAX_VALUE;
            for (int[] p : pos) {
                long d2 = distSq(p, cx, cz);
                if (d2 <= limitSq) count++;
                if (d2 < bestD2) { bestD2 = d2; best = p; }
            }
            if (count < term.someCount || best == null) return null;
            return StructureChecker.Result.foundWith(best[0], best[1], canonical, pos);
        }

        if (mod == Modifier.ONLY) {
            int onlyChunks = chunks(ONLY_BLOCKS);
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, onlyChunks, canonical);
            long limitSq = (long) ONLY_BLOCKS * ONLY_BLOCKS + ONLY_BLOCKS;
            int count = 0;
            int[] best = null; long bestD2 = Long.MAX_VALUE;
            for (int[] p : pos) {
                long d2 = distSq(p, cx, cz);
                if (d2 <= limitSq) count++;
                if (d2 < bestD2) { bestD2 = d2; best = p; }
            }
            if (count != 1 || best == null) return null;
            return StructureChecker.Result.foundWith(best[0], best[1], canonical, pos);
        }

        if (mod == Modifier.BETWEEN) {
            int maxChunks = chunks(term.betweenMax);
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, maxChunks, canonical);
            // round(sqrt) >= m ⟺ d2 >= m*m-m+1 (m > 0); m <= 0 matches all
            long loSq = term.betweenMin <= 0 ? 0
                    : (long) term.betweenMin * term.betweenMin - term.betweenMin + 1;
            long hiSq = (long) term.betweenMax * term.betweenMax + term.betweenMax;
            int[] best = null; long bestD2 = Long.MAX_VALUE;
            for (int[] p : pos) {
                long d2 = distSq(p, cx, cz);
                if (d2 >= loSq && d2 <= hiSq && d2 < bestD2) {
                    bestD2 = d2;
                    best = p;
                }
            }
            if (best == null) return null;
            return StructureChecker.Result.foundWith(best[0], best[1], canonical, pos);
        }

        int scanChunks = switch (mod) {
            case NEAR   -> chunks(NEAR_BLOCKS);
            case IN     -> UNDERGROUND.contains(canonical) ? searchRadiusChunks : chunks(IN_ON_BLOCKS);
            default     -> searchRadiusChunks;
        };

        int[] found = structureChecker.firstPosition(ctx, cx, cz, scanChunks, canonical);
        if (found == null) return null;

        int distance = dist(found, cx, cz);
        if (mod == Modifier.IN && distance > IN_ON_BLOCKS) return null;
        if (mod == Modifier.NEAR && distance > NEAR_BLOCKS) return null;

        LOGGER.debug("[SeedValidator] seed {} structure FOUND: {} @({},{}) center=({},{}) dist={} scanChunks={}",
                ctx.seed, canonical, found[0], found[1], cx, cz, distance, scanChunks);
        return StructureChecker.Result.found(found[0], found[1], canonical);
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

    // Single biome term: sample directly with early exit, not the whole grid
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

    // Several biome terms: sample each needed Y-level once, share across terms
    private Map<Integer, BiomeField> sampleBiomeFields(WorldContext ctx, int cx, int cz, List<ParsedQuery.Term> terms) {
        // Count first, the list only matters with 2+ biome terms
        int biomeCount = 0;
        for (ParsedQuery.Term t : terms) {
            if (t.category == KeywordDictionary.Category.BIOME) biomeCount++;
        }
        if (biomeCount <= 1) return Map.of();
        List<ParsedQuery.Term> biomeTerms = new ArrayList<>(biomeCount);
        for (ParsedQuery.Term t : terms) {
            if (t.category == KeywordDictionary.Category.BIOME) biomeTerms.add(t);
        }

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

    // Cheap terms first so rejects cost little; far/never biome scans last
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

    // Squared distance, no sqrt for comparisons
    static long distSq(int[] p, int cx, int cz) {
        long dx = p[0] - cx;
        long dz = p[1] - cz;
        return dx * dx + dz * dz;
    }
}
