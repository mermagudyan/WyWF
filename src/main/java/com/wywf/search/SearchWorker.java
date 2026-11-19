package com.wywf.search;

import com.wywf.core.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SearchWorker implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");

    private static final AtomicBoolean FIRST_ERROR_LOGGED = new AtomicBoolean(false);

    private final int             threadIndex;
    private final int             threadCount;
    private final ParsedQuery     query;
    private final WorldContextFactory contextFactory;
    private final SeedValidator   validator;
    private final StructureChecker structureChecker;
    private final SearchProgress  progress;
    private final AtomicBoolean   running;
    private final AtomicLong      globalSeedCursor;
    private final List<SearchResult> candidates;
    private final SearchConfig     config;
    private final int              searchRadiusChunks;
    private Set<Long> excluded = Set.of();
    private CandidateStream stream;
    public void stream(CandidateStream value){stream=value;}

    public void exclude(Set<Long> seeds) { excluded = Set.copyOf(seeds); }

    public SearchWorker(int threadIndex, int threadCount,
                        ParsedQuery query,
                        WorldContextFactory contextFactory,
                        SeedValidator validator,
                        SearchProgress progress,
                        AtomicBoolean running,
                        AtomicLong globalSeedCursor,
                        long startOffset,
                        List<SearchResult> candidates,
                        SearchConfig config) {
        this.threadIndex       = threadIndex;
        this.threadCount       = threadCount;
        this.query             = query;
        this.contextFactory    = contextFactory;
        this.validator         = validator;
        this.structureChecker  = validator.structureChecker();
        this.progress          = progress;
        this.running           = running;
        this.globalSeedCursor  = globalSeedCursor;
        this.startOffset       = startOffset;
        this.candidates        = candidates;
        this.config            = config;
        this.searchRadiusChunks = config.searchRadiusChunks();
    }

    private static final long SIZE48 = 1L << 48;
    private static final long SIZE16 = 1L << 16;
    private static final int PRIORITY_ORIGIN_BASES = 8;
    private static final int PRIORITY_SPAWN_BASES = 24;
    private static final int PRIORITY_SPAWN_AREA = 256;
    private static final String ONLINE_BENCH = System.getenv().getOrDefault("WYWF_ONLINE_BENCH", "current");
    private static final boolean WIDE_PRIORITY = switch (ONLINE_BENCH) {
        case "baseline", "biome", "rank", "both" -> false;
        default -> true;
    };
    private static final int PRIORITY_BIOME_RADIUS = WIDE_PRIORITY ? 256 : 128;
    private static final boolean KEEP_PROBING_INSIDE = "current".equals(ONLINE_BENCH) || "inside-priority".equals(ONLINE_BENCH);
    private static final long INSIDE_PRIORITY_NANOS = 30_000_000_000L;
    private static final int INSIDE_PRIORITY_CANDIDATES = 4;
    private static final int PRIORITY_VARIANTS = 2048;
    private static final long PRIORITY_NANOS = 12_000_000_000L;
    private static final int SPAWN_VARIANT_STRIDE = 40503;
    private Set<Long> priorityCandidates = Set.of();
    private static final java.util.concurrent.atomic.AtomicInteger SPAWN_DEBUG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger REJECTION_LOG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    // Diagnostics: per-seed rejects by the placement gate
    private static final AtomicLong GATE_PLACEMENT_REJECT = new AtomicLong();

    // Shared null-spawn fallback, validateCtx only reads it
    private static final int[] ORIGIN_FALLBACK = {0, 0};

    public static String gateStats() {
        return "gate[placementReject=" + GATE_PLACEMENT_REJECT.get() + "]";
    }

    public static void resetGateStats() {
        GATE_PLACEMENT_REJECT.set(0);
    }

    // Per-base placement cache: one scan serves all 65536 variants, per-seed gating is pure math
    private Map<String, List<int[]>> placementGateCache = null;
    private Map<String, Integer> placementGateLimit = null;

    private final long startOffset;

    @Override
    public void run() {
        // Pin native mode for this worker thread so mid-search config changes don't flip the branch
        CubiomesBridge.setThreadMode(config.nativeMode());
        try {
        boolean accurateRings = query.structures().contains("minecraft:stronghold");
        SearchConfig.SearchCenter center = config.searchCenter();

        if (config.linearBiomeSearch() && center == SearchConfig.SearchCenter.ORIGIN && isBiomeOnlyQuery()) {
            if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] start: threads={}, center={}, linear (biome-only, opt-in)",
                    threadIndex, threadCount, center);
            runLinear(accurateRings, center);
            return;
        }

        if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] start: threads={}, center={}, 48/16 split",
                threadIndex, threadCount, center);
        if (stream != null) {
            try {
                prioritizeCloseMatches(center);
            } catch (RuntimeException error) {
                if (WyWFDebug.ENABLED) LOGGER.debug("Close-match priority pass ended early", error);
            }
        }
        runSplit(startOffset, accurateRings, center);
        } finally {
            CubiomesBridge.clearThreadMode();
            CubiomesBridge.destroyCurrentGenerator();
        }
    }

    // True when no structure term needs placement gating
    private boolean isBiomeOnlyQuery() {
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE || DimensionSpace.external(term)) continue;
            Modifier mod = term.modifier;
            if (mod == Modifier.NEVER || mod == Modifier.FAR) continue;
            return false;
        }
        return true;
    }

    private record ClosePlacement(long base, List<int[]> positions, List<int[]> nearOrigin) {}

    private void prioritizeCloseMatches(SearchConfig.SearchCenter center) {
        if (!query.estimated() || query.hasAlternatives()) return;
        ParsedQuery.Term structure = null;
        ParsedQuery.Term nearbyBiome = null;
        for (var term : query.terms()) {
            if (DimensionSpace.external(term) || term.loot != null || !term.tags.empty()) return;
            if (term.category == KeywordDictionary.Category.STRUCTURE) {
                if (structure != null || !(term.modifier == Modifier.SOME
                        || term.modifier == Modifier.BETWEEN && term.betweenMin == 0 && term.betweenMax <= 128)) return;
                structure = term;
            } else {
                if (term.category != KeywordDictionary.Category.BIOME
                        || term.modifier != Modifier.BETWEEN || term.betweenMax > PRIORITY_BIOME_RADIUS) return;
                if (nearbyBiome == null || term.betweenMax < nearbyBiome.betweenMax) nearbyBiome = term;
            }
        }
        if (structure == null) return;

        int radius = TargetDistance.radius(structure, searchRadiusChunks * 16);
        boolean keepProbingInside = radius == 0 && KEEP_PROBING_INSIDE && nearbyBiome != null
                && center == SearchConfig.SearchCenter.SPAWN && config.candidatesToCollect() > 1;
        var priorityTerms = query.terms();
        // Nearby starts can have a footprint that contains the exact requested point
        if (radius == 0 && WIDE_PRIORITY) {
            radius = 64;
            var required = structure;
            var nearby = new ParsedQuery.Term(structure.canonical, structure.category, Modifier.BETWEEN,
                    structure.someCount, 0, radius);
            priorityTerms = priorityTerms.stream().map(term -> term == required ? nearby : term).toList();
        }
        var priorityQuery = new ParsedQuery(query.raw(), priorityTerms);
        var priorityValidator = validator.withQuery(priorityQuery);
        int needed = structure.modifier == Modifier.SOME ? structure.someCount : 1;
        long budget = nearbyBiome == null ? 500_000_000L : PRIORITY_NANOS;
        long end = System.nanoTime() + (keepProbingInside ? INSIDE_PRIORITY_NANOS : budget);
        boolean spawn = center == SearchConfig.SearchCenter.SPAWN;
        int cachedRadius = (radius + 15) / 16 + (spawn ? SeedValidator.SPAWN_DRIFT_BLOCKS / 16 : 0);
        var biomeProbe = spawn && nearbyBiome != null ? new VanillaBiomeChecker() : null;
        int probeRadius = nearbyBiome == null ? 0 : (radius + nearbyBiome.betweenMax + 15) / 16;
        int baseCount = biomeProbe == null ? PRIORITY_ORIGIN_BASES : PRIORITY_SPAWN_BASES;
        if (biomeProbe != null && threadCount >= 4 && threadIndex == threadCount - 1) return;
        List<ClosePlacement> bases = new ArrayList<>(baseCount);
        for (long i = threadIndex; i < SIZE48 && bases.size() < baseCount; i += threadCount) {
            if (!priorityActive(end)) return;
            long base = (i + startOffset) % SIZE48;
            var context = contextFactory.create(base, false);
            if (structureChecker.hasConcentricRings(context, structure.canonical)) return;
            var placements = structureChecker.positionsPlacementOnly(context, 0, 0, cachedRadius, structure.canonical);
            if (spawn || closeEnough(placements, ORIGIN_FALLBACK, radius, needed)) {
                var nearOrigin = biomeProbe == null ? List.<int[]>of() : placements.stream()
                        .filter(p -> Math.abs(p[0]) <= PRIORITY_SPAWN_AREA && Math.abs(p[1]) <= PRIORITY_SPAWN_AREA)
                        .toList();
                if (biomeProbe == null || !nearOrigin.isEmpty()) bases.add(new ClosePlacement(base, placements, nearOrigin));
            }
        }

        // Rotate across promising low-48 families so a single family cannot consume the whole pass
        for (int h = 0; h < PRIORITY_VARIANTS; h++) {
            for (var base : bases) {
                if (!priorityActive(end)) return;
                int variant = spawn ? h * SPAWN_VARIANT_STRIDE & 0xffff : h;
                long seed = base.base() | ((long) variant << 48);
                if (excluded.contains(seed)) continue;
                if (CubiomesBridge.isActive()) CubiomesBridge.applySeed(seed);
                var context = contextFactory.create(seed, false);
                if (biomeProbe != null && !biomeNearPlacement(biomeProbe, context, base.nearOrigin(),
                        probeRadius, nearbyBiome.canonical)) continue;
                int[] point = spawn ? SeedValidator.findApproxSpawnPos(context, false) : ORIGIN_FALLBACK;
                if (point == null) continue;
                if (spawn && Math.abs((long) point[0]) <= SeedValidator.SPAWN_DRIFT_BLOCKS
                        && Math.abs((long) point[1]) <= SeedValidator.SPAWN_DRIFT_BLOCKS
                        && !closeEnough(base.positions(), point, radius, needed)) continue;
                var outcome = priorityValidator.validate(context, priorityQuery, point[0], point[1]);
                progress.onSeedChecked();
                if (outcome.accepted) {
                    reportFound(outcome.result, true);
                    if (priorityCandidates.isEmpty()) priorityCandidates = new HashSet<>();
                    priorityCandidates.add(seed);
                    if (!keepProbingInside || priorityCandidates.size() >= INSIDE_PRIORITY_CANDIDATES) return;
                }
            }
        }
    }

    private static boolean biomeNearPlacement(VanillaBiomeChecker probe, WorldContext context,
                                              List<int[]> positions, int radius, String biome) {
        for (int[] p : positions) {
            if (probe.exists(context, p[0], p[1], radius, biome)) return true;
        }
        return false;
    }

    private boolean priorityActive(long end) {
        return running.get() && System.nanoTime() < end && stream.awaitWorker(threadIndex, () -> !running.get())
                && System.nanoTime() < end;
    }

    private static boolean closeEnough(List<int[]> placements, int[] point, int radius, int needed) {
        long maxDistance = (long) radius * radius + radius;
        for (int[] placement : placements) {
            long dx = (long) placement[0] - point[0], dz = (long) placement[1] - point[1];
            if (dx * dx + dz * dz <= maxDistance && --needed == 0) return true;
        }
        return false;
    }

    private void runSplit(long offset, boolean accurateRings, SearchConfig.SearchCenter center) {
        long step = Math.max(1, threadCount);
        long localChecked = 0;

        for (long i = threadIndex; i < SIZE48; i += step) {
            long a = (i + offset) % SIZE48;
            if (!running.get()||stream!=null&&!stream.awaitWorker(threadIndex,()->!running.get())) {
                if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                return;
            }

            WorldContext pfCtx = contextFactory.create(a, accurateRings);

            // No applySeed(a): prefilters are placement-only Java, seeding would waste one JNA call per block
            if (center == SearchConfig.SearchCenter.ORIGIN) {
                if (!prefilterStructures(pfCtx, center, null)) {
                    globalSeedCursor.addAndGet(SIZE16);
                    progress.onSeedsDiscarded(SIZE16);
                    continue;
                }
            } else if (!prefilterStructuresExpanded(pfCtx, center)) {
                globalSeedCursor.addAndGet(SIZE16);
                progress.onSeedsDiscarded(SIZE16);
                continue;
            }

            if (center == SearchConfig.SearchCenter.SPAWN) {
                buildPlacementGateCache(pfCtx);
            } else {
                placementGateCache = null;
                placementGateLimit = null;
            }

            for (int h = 0; h < SIZE16; h++) {
                if (!running.get()||stream!=null&&!stream.awaitWorker(threadIndex,()->!running.get())) {
                    if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                    return;
                }

                long seed = (a & 0xFFFFFFFFFFFFL) | ((long) h << 48);

                if (CubiomesBridge.isActive()) {
                    CubiomesBridge.applySeed(seed);
                }

                try {
                    WorldContext seedCtx = contextFactory.create(seed, accurateRings);
                    int[] spawn = null;
                    if (center == SearchConfig.SearchCenter.SPAWN) {
                        spawn = SeedValidator.findApproxSpawnPos(seedCtx, false);
                        if (spawn == null) {
                            globalSeedCursor.incrementAndGet();
                            localChecked++;
                            progress.onSeedDiscarded();
                            continue;
                        }
                        if (WyWFDebug.ENABLED && SPAWN_DEBUG_COUNTER.getAndIncrement() < 5) {
                            LOGGER.debug("[runSplit] seed={} -> spawn=({},{})", seed, spawn[0], spawn[1]);
                        }
                        if (!placementGatePass(spawn)) {
                            GATE_PLACEMENT_REJECT.incrementAndGet();
                            globalSeedCursor.incrementAndGet();
                            localChecked++;
                            progress.onSeedDiscarded();
                            continue;
                        }
                    }

                    SeedValidator.Outcome outcome = validateCtx(seedCtx, center, spawn);

                    globalSeedCursor.incrementAndGet();
                    localChecked++;
                    progress.onSeedChecked();
                    logOutcome(seed, outcome);

                    if (outcome.accepted) {
                        reportFound(outcome.result, false);
                        if (enoughCandidates()) return;
                    }
                } catch (Throwable t) {
                    globalSeedCursor.incrementAndGet();
                    progress.onSeedDiscarded();
                    if (FIRST_ERROR_LOGGED.compareAndSet(false, true)) {
                        LOGGER.error("[thread {}] FIRST seed {} discarded due to error:", threadIndex, seed, t);
                    }
                    LOGGER.debug("[thread {}] seed {} discarded due to error: {}", threadIndex, seed, t.toString());
                }
            }
        }

        if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] 48-bit range exhausted (checked {} locally), exiting", threadIndex, localChecked);
    }

    private void runLinear(boolean accurateRings, SearchConfig.SearchCenter center) {
        boolean positive = (threadIndex % 2) == 0;
        int evenCount = (threadCount + 1) / 2;
        int oddCount  = threadCount / 2;
        long step = positive ? evenCount : oddCount;
        long rank = threadIndex / 2;
        long localChecked = 0;

        if (step <= 0) {
            if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] no {} direction available, exiting", threadIndex, positive ? "positive" : "negative");
            return;
        }

        long inner = rank;
        for (;;) {
            if (!running.get()||stream!=null&&!stream.awaitWorker(threadIndex,()->!running.get())) {
                if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                return;
            }

            long seed = positive ? inner : (-1L - inner);

            try {
                if (CubiomesBridge.isActive()) {
                    CubiomesBridge.applySeed(seed);
                }

                int[] spawn = null;
                WorldContext seedCtx = contextFactory.create(seed, accurateRings);
                if (center == SearchConfig.SearchCenter.SPAWN) {
                    spawn = SeedValidator.findApproxSpawnPos(seedCtx, false);
                    if (spawn == null) {
                        globalSeedCursor.incrementAndGet();
                        localChecked++;
                        progress.onSeedDiscarded();
                        long next0 = inner + step;
                        if (next0 < inner) {
                            if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] {} seed range exhausted (checked {} locally), exiting",
                                    threadIndex, positive ? "positive" : "negative", localChecked);
                            return;
                        }
                        inner = next0;
                        continue;
                    }
                    if (WyWFDebug.ENABLED && SPAWN_DEBUG_COUNTER.getAndIncrement() < 5) {
                        LOGGER.debug("[runLinear] seed={} -> spawn=({},{})", seed, spawn[0], spawn[1]);
                    }
                }

                SeedValidator.Outcome outcome = validateCtx(seedCtx, center,
                        spawn != null ? spawn : ORIGIN_FALLBACK);

                globalSeedCursor.incrementAndGet();
                localChecked++;
                progress.onSeedChecked();
                logOutcome(seed, outcome);

                if (outcome.accepted) {
                    reportFound(outcome.result, false);
                    if (enoughCandidates()) return;
                }
            } catch (Throwable t) {
                globalSeedCursor.incrementAndGet();
                progress.onSeedDiscarded();
                if (FIRST_ERROR_LOGGED.compareAndSet(false, true)) {
                    LOGGER.error("[thread {}] FIRST seed {} discarded due to error:", threadIndex, seed, t);
                }
                LOGGER.debug("[thread {}] seed {} discarded due to error: {}", threadIndex, seed, t.toString());
            }

            long next = inner + step;
            if (next < inner) {
                if (WyWFDebug.ENABLED) LOGGER.info("[thread {}] {} seed range exhausted (checked {} locally), exiting",
                        threadIndex, positive ? "positive" : "negative", localChecked);
                return;
            }
            inner = next;
        }
    }

    // One context reused for spawn lookup and every term evaluation
    private SeedValidator.Outcome validateCtx(WorldContext ctx,
                                              SearchConfig.SearchCenter center, int[] spawn) {
        return switch (center) {
            case ORIGIN -> validator.validate(ctx, query, 0, 0);
            case SPAWN  -> validator.validate(ctx, query, spawn[0], spawn[1]);
        };
    }

    private static final int FAR_MAX_CHUNKS = (1000 + 15) / 16;
    private static final int FAR_MIN_BLOCKS = 500;

    private boolean prefilterStructuresExpanded(WorldContext ctx, SearchConfig.SearchCenter center) {
        if (query.hasAlternatives()) return true;
        int expandBlocks = SeedValidator.SPAWN_DRIFT_BLOCKS;
        if (!dimensionPlacements(ctx, 0, 0, expandBlocks)) return false;
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE || DimensionSpace.external(term)) continue;
            Modifier mod = term.modifier;
            if (mod == Modifier.NEVER || mod == Modifier.FAR || mod == Modifier.BETWEEN || mod.counted()) continue;
            // Rings are full-seed dependent — cannot be gated at block level.
            if (structureChecker.hasConcentricRings(ctx, term.canonical)) continue;

            if (mod.counted()) continue;
            if (mod == Modifier.SOME && term.someCount > 1) {
                int effectiveBlocks = SeedValidator.effectiveSomeBlocks(term.someCount)+(query.estimated() ? TargetDistance.PLACEMENT_MARGIN : 0);
                int scanChunks = (effectiveBlocks + expandBlocks + 15) / 16;
                int count = countPlacements(ctx, 0, 0, scanChunks, effectiveBlocks + expandBlocks, term.canonical);
                if (count < term.someCount) return false;
                continue;
            }

            int baseRadius = validator.structureScanRadiusChunks(term);
            int expandedChunks = baseRadius + (expandBlocks + 15) / 16;
            if (structureChecker.firstPositionPlacementOnly(ctx, 0, 0, expandedChunks, term.canonical) == null) {
                return false;
            }
        }
        return true;
    }

    // Per-base gate for DEFAULT/NEAR/IN terms (no rings); a miss means validate() can't hit either
    private void buildPlacementGateCache(WorldContext pfCtx) {
        if (query.hasAlternatives()) { placementGateCache = null; placementGateLimit = null; return; }
        placementGateCache = new HashMap<>();
        placementGateLimit = new HashMap<>();
        int driftChunks = (SeedValidator.SPAWN_DRIFT_BLOCKS + 15) / 16;
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE || DimensionSpace.external(term)) continue;
            Modifier mod = term.modifier;
            if (mod != Modifier.DEFAULT && mod != Modifier.NEAR && mod != Modifier.IN) continue;
            if (structureChecker.hasConcentricRings(pfCtx, term.canonical)) continue;
            int scanR = validator.structureScanRadiusChunks(term);
            List<int[]> pts = structureChecker.positionsPlacementOnly(
                    pfCtx, 0, 0, scanR + driftChunks, term.canonical);
            placementGateCache.put(term.canonical, pts);
            placementGateLimit.put(term.canonical, validator.structureGateLimitBlocks(term));
        }
    }

    // True when every gateable term has a cached placement within eval limit of spawn. Pure integer math
    private boolean placementGatePass(int[] spawn) {
        if (placementGateCache == null || placementGateCache.isEmpty()) return true;
        for (Map.Entry<String, List<int[]>> e : placementGateCache.entrySet()) {
            int limit = placementGateLimit.get(e.getKey());
            long lim2 = (long) limit * limit;
            boolean ok = false;
            for (int[] p : e.getValue()) {
                long dx = p[0] - spawn[0];
                long dz = p[1] - spawn[1];
                if (dx * dx + dz * dz <= lim2) { ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    private boolean prefilterStructures(WorldContext ctx, SearchConfig.SearchCenter center, int[] spawn) {
        if(query.estimated()){
            if(query.hasAlternatives())return true;
            for(var term:query.terms()){
                if(term.category!=KeywordDictionary.Category.STRUCTURE||TargetDistance.deferred(term)
                        ||structureChecker.hasConcentricRings(ctx,term.canonical))continue;
                var dimension=DimensionSpace.of(term.canonical);
                int x=spawn==null ? 0 : spawn[0],z=spawn==null ? 0 : spawn[1];
                if(structureChecker.firstPositionPlacementOnly(ctx,dimension.coordinate(x),dimension.coordinate(z),validator.structureScanRadiusChunks(term),term.canonical)==null){return false;}
            }
            return true;
        }
        if (query.hasAlternatives()) return true;
        if (center == SearchConfig.SearchCenter.ORIGIN && !dimensionPlacements(ctx, 0, 0, 0)) return false;
        boolean checkOrigin = center == SearchConfig.SearchCenter.ORIGIN;
        boolean checkSpawn = center == SearchConfig.SearchCenter.SPAWN && spawn != null;

        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE || DimensionSpace.external(term)) continue;
            Modifier mod = term.modifier;

            // Ring layouts (stronghold) depend on the FULL seed — block-level
            // gating would judge 65536 variants by one variant's rings.
            if (structureChecker.hasConcentricRings(ctx, term.canonical)) continue;

            if (mod == Modifier.NEVER) {
                continue;
            }

            if (mod == Modifier.FAR) {
                boolean farFailsOrigin = checkOrigin && farWouldFail(ctx, 0, 0, term.canonical);
                boolean farFailsSpawn = checkSpawn && farWouldFail(ctx, spawn[0], spawn[1], term.canonical);
                if (checkOrigin && !checkSpawn && farFailsOrigin) return false;
                if (checkSpawn && !checkOrigin && farFailsSpawn) return false;
                continue;
            }

            if (mod.counted()) continue;
            if (mod == Modifier.SOME && term.someCount > 1) {
                int effectiveBlocks = SeedValidator.effectiveSomeBlocks(term.someCount)+(query.estimated() ? TargetDistance.PLACEMENT_MARGIN : 0);
                int scanChunks = Math.max(searchRadiusChunks, (effectiveBlocks + 15) / 16);
                boolean enoughOrigin = checkOrigin && countPlacements(ctx, 0, 0, scanChunks, effectiveBlocks, term.canonical) >= term.someCount;
                boolean enoughSpawn = checkSpawn && countPlacements(ctx, spawn[0], spawn[1], scanChunks, effectiveBlocks, term.canonical) >= term.someCount;
                if (checkOrigin && !checkSpawn && !enoughOrigin) return false;
                if (checkSpawn && !checkOrigin && !enoughSpawn) return false;
                continue;
            }

            int radius = validator.structureScanRadiusChunks(term);
            boolean found = false;
            if (checkOrigin) {
                // Placement-only: structure placement depends only on low-48 bits,
                // but biome viability is per-full-seed. Checking biome here would
                // reject whole 65536-seed blocks based on the base seed's biomes.
                found = structureChecker.firstPositionPlacementOnly(ctx, 0, 0, radius, term.canonical) != null;
            }
            if (!found && checkSpawn) {
                found = structureChecker.firstPositionPlacementOnly(ctx, spawn[0], spawn[1], radius, term.canonical) != null;
            }
            if (!found){return false;}
        }
        return true;
    }

    private boolean dimensionPlacements(WorldContext ctx, int cx, int cz, int drift) {
        for (var term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE || !DimensionSpace.external(term)
                    || term.modifier == Modifier.NEVER || term.modifier == Modifier.FAR || term.modifier.counted()) continue;
            var dimension = DimensionSpace.of(term.canonical);
            int radius = validator.structureScanRadiusChunks(term) * 16;
            int margin = dimension == DimensionSpace.NETHER ? (drift + 7) / 8 : drift;
            if (structureChecker.firstPositionPlacementOnly(ctx, dimension.coordinate(cx), dimension.coordinate(cz),
                    (radius + margin + 31) / 16, term.canonical) == null) return false;
        }
        return true;
    }
    private boolean farWouldFail(WorldContext ctx, int cx, int cz, String canonical) {
        // Placement-only superset is exact for this verdict: if even the nearest
        // candidate placement is < FAR_MIN, no viable instance can be farther.
        List<int[]> pos = structureChecker.positionsPlacementOnly(ctx, cx, cz, FAR_MAX_CHUNKS, canonical);
        if (pos.isEmpty()) return true;
        long minDistSq = Long.MAX_VALUE;
        long farMinBlocksSq = (long) FAR_MIN_BLOCKS * FAR_MIN_BLOCKS;
        for (int[] p : pos) {
            long dx = p[0] - cx;
            long dz = p[1] - cz;
            long d2 = dx * dx + dz * dz;
            if (d2 < minDistSq) minDistSq = d2;
        }
        return minDistSq < farMinBlocksSq;
    }

    private int countPlacements(WorldContext ctx, int cx, int cz, int radiusChunks, int effectiveBlocks, String canonical) {
        List<int[]> pos = structureChecker.positionsPlacementOnly(ctx, cx, cz, radiusChunks, canonical);
        int count = 0;
        long effectiveBlocksSq = (long) effectiveBlocks * effectiveBlocks;
        for (int[] p : pos) {
            long dx = p[0] - cx;
            long dz = p[1] - cz;
            long d2 = dx * dx + dz * dz;
            if (d2 <= effectiveBlocksSq) count++;
        }
        return count;
    }

    private boolean enoughCandidates() {
        if(stream!=null)return stream.closed()||Thread.currentThread().isInterrupted();
        int target = config.effectiveCandidateTarget(progress.snapshot().elapsedMs());
        return candidates.size() >= target;
    }

    private void reportFound(SearchResult r, boolean preferred) {
        if (excluded.contains(r.seed) || priorityCandidates.contains(r.seed)) return;
        if (WyWFDebug.ENABLED) {
            StringBuilder sb = new StringBuilder();
            sb.append("[thread ").append(threadIndex).append("] candidate seed ").append(r.seed);
            if (config.searchCenter() == SearchConfig.SearchCenter.SPAWN) {
                sb.append(" → spawn (").append(r.centerX).append(", ").append(r.centerZ).append(")");
            } else {
                sb.append(" → origin (").append(r.centerX).append(", ").append(r.centerZ).append(")");
            }
            for (var entry : r.structurePositions.entrySet()) {
                int[] pos = entry.getValue();
                long dx = pos[0] - r.centerX;
                long dz = pos[1] - r.centerZ;
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                sb.append(", ").append(entry.getKey()).append(" ~").append(dist).append(" blocks");
                sb.append(" @(").append(pos[0]).append(", ").append(pos[1]).append(")");
            }
            for (var entry : r.biomeDistances.entrySet()) {
                sb.append(", ").append(entry.getKey()).append(" ~").append(entry.getValue()).append(" blocks");
            }
            LOGGER.info("{}", sb);
        }
        if (stream != null) {
            stream.put(r, () -> !running.get(), preferred);
            return;
        }
        synchronized (candidates) {
            if (candidates.size() < config.candidatesToCollect()) {
                candidates.add(r);
            }
        }
        if (enoughCandidates()) {
            running.set(false);
        }
    }

    private void logOutcome(long seed, SeedValidator.Outcome outcome) {
        if (outcome.accepted) return;
        progress.onSeedDiscarded();

        if (WyWFDebug.ENABLED && REJECTION_LOG_COUNTER.getAndIncrement() < 30) {
            if (outcome.structuresMatched) {
                LOGGER.debug("seed {} rejected: structure {} present @({}, {}), but {}",
                        seed, outcome.structureId, outcome.structureX, outcome.structureZ,
                        outcome.reason.description);
            } else {
                LOGGER.debug("seed {} rejected: {}", seed, outcome.reason.description);
            }
        }
    }
}
