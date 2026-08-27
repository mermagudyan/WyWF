package com.wywf.search;

import com.wywf.core.*;
import java.util.List;
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
    private static final java.util.concurrent.atomic.AtomicInteger SPAWN_DEBUG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger REJECTION_LOG_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private final long startOffset;

    @Override
    public void run() {
        // Pin native mode for this worker thread so mid-search config changes don't flip the branch
        CubiomesBridge.setThreadMode(config.nativeMode());
        try {
        boolean accurateRings = query.structures().contains("minecraft:stronghold");
        SearchConfig.SearchCenter center = config.searchCenter();

        if (config.linearBiomeSearch() && center == SearchConfig.SearchCenter.ORIGIN && isBiomeOnlyQuery()) {
            LOGGER.info("[thread {}] start: threads={}, center={}, linear (biome-only, opt-in)",
                    threadIndex, threadCount, center);
            runLinear(accurateRings, center);
            return;
        }

        LOGGER.info("[thread {}] start: threads={}, center={}, 48/16 split",
                threadIndex, threadCount, center);
        runSplit(startOffset, accurateRings, center);
        } finally {
            CubiomesBridge.clearThreadMode();
            CubiomesBridge.destroyCurrentGenerator();
        }
    }

    /** True when no structure term needs block-level placement gating. */
    private boolean isBiomeOnlyQuery() {
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
            Modifier mod = term.modifier;
            if (mod == Modifier.NEVER || mod == Modifier.FAR) continue;
            return false;
        }
        return true;
    }

    private void runSplit(long offset, boolean accurateRings, SearchConfig.SearchCenter center) {
        long step = Math.max(1, threadCount);
        long localChecked = 0;

        for (long i = threadIndex; i < SIZE48; i += step) {
            long a = (i + offset) % SIZE48;
            if (!running.get()) {
                LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                return;
            }

            WorldContext pfCtx = contextFactory.create(a, accurateRings);

            if (CubiomesBridge.isActive()) {
                CubiomesBridge.applySeed(a);
            }

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

            for (int h = 0; h < SIZE16; h++) {
                if (!running.get()) {
                    LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                    return;
                }

                long seed = (a & 0xFFFFFFFFFFFFL) | ((long) h << 48);

                if (CubiomesBridge.isActive()) {
                    CubiomesBridge.applySeed(seed);
                }

                try {
                    int[] spawn = null;
                    WorldContext seedCtx = contextFactory.create(seed, accurateRings);
                    if (center == SearchConfig.SearchCenter.SPAWN || center == SearchConfig.SearchCenter.BOTH) {
                        spawn = SeedValidator.findApproxSpawnPos(seedCtx, false);
                        if (spawn == null) {
                            globalSeedCursor.incrementAndGet();
                            localChecked++;
                            progress.onSeedDiscarded();
                            continue;
                        }
                        if (SPAWN_DEBUG_COUNTER.getAndIncrement() < 5) {
                            LOGGER.debug("[runSplit] seed={} -> spawn=({},{})", seed, spawn[0], spawn[1]);
                        }
                    }

                    SeedValidator.Outcome outcome = validateCtx(seedCtx, center, spawn);

                    globalSeedCursor.incrementAndGet();
                    localChecked++;
                    progress.onSeedChecked();
                    logOutcome(seed, outcome);

                    if (outcome.accepted) {
                        reportFound(outcome.result);
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

        LOGGER.info("[thread {}] 48-bit range exhausted (checked {} locally), exiting", threadIndex, localChecked);
    }

    private void runLinear(boolean accurateRings, SearchConfig.SearchCenter center) {
        boolean positive = (threadIndex % 2) == 0;
        int evenCount = (threadCount + 1) / 2;
        int oddCount  = threadCount / 2;
        long step = positive ? evenCount : oddCount;
        long rank = threadIndex / 2;
        long localChecked = 0;

        if (step <= 0) {
            LOGGER.info("[thread {}] no {} direction available, exiting", threadIndex, positive ? "positive" : "negative");
            return;
        }

        long inner = rank;
        for (;;) {
            if (!running.get()) {
                LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                return;
            }

            long seed = positive ? inner : (-1L - inner);

            try {
                if (CubiomesBridge.isActive()) {
                    CubiomesBridge.applySeed(seed);
                }

                int[] spawn = null;
                WorldContext seedCtx = contextFactory.create(seed, accurateRings);
                if (center == SearchConfig.SearchCenter.SPAWN || center == SearchConfig.SearchCenter.BOTH) {
                    spawn = SeedValidator.findApproxSpawnPos(seedCtx, false);
                    if (spawn == null) {
                        globalSeedCursor.incrementAndGet();
                        localChecked++;
                        progress.onSeedDiscarded();
                        continue;
                    }
                    if (SPAWN_DEBUG_COUNTER.getAndIncrement() < 5) {
                        LOGGER.debug("[runLinear] seed={} -> spawn=({},{})", seed, spawn[0], spawn[1]);
                    }
                }

                SeedValidator.Outcome outcome = validateCtx(seedCtx, center,
                        spawn != null ? spawn : new int[]{0, 0});

                globalSeedCursor.incrementAndGet();
                localChecked++;
                progress.onSeedChecked();
                logOutcome(seed, outcome);

                if (outcome.accepted) {
                    reportFound(outcome.result);
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
                LOGGER.info("[thread {}] {} seed range exhausted (checked {} locally), exiting",
                        threadIndex, positive ? "positive" : "negative", localChecked);
                return;
            }
            inner = next;
        }
    }

    /** Single-context validation: reuses the per-seed context for spawn lookup
     *  and every term evaluation. */
    private SeedValidator.Outcome validateCtx(WorldContext ctx,
                                              SearchConfig.SearchCenter center, int[] spawn) {
        return switch (center) {
            case ORIGIN -> validator.validate(ctx, query, 0, 0);
            case SPAWN  -> validator.validate(ctx, query, spawn[0], spawn[1]);
            case BOTH   -> {
                SeedValidator.Outcome s = validator.validate(ctx, query, spawn[0], spawn[1]);
                if (s.accepted) yield s;
                yield validator.validate(ctx, query, 0, 0);
            }
        };
    }

    private static final int FAR_MAX_CHUNKS = (1000 + 15) / 16;
    private static final int FAR_MIN_BLOCKS = 500;
    private static final int SPAWN_DRIFT_BLOCKS = 2048;

    private boolean prefilterStructuresExpanded(WorldContext ctx, SearchConfig.SearchCenter center) {
        int expandBlocks = SPAWN_DRIFT_BLOCKS;
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
            Modifier mod = term.modifier;
            if (mod == Modifier.NEVER || mod == Modifier.FAR || mod == Modifier.BETWEEN) continue;
            // Rings are full-seed dependent — cannot be gated at block level.
            if (structureChecker.hasConcentricRings(ctx, term.canonical)) continue;

            if (mod == Modifier.SOME && term.someCount > 1) {
                int effectiveBlocks = SeedValidator.effectiveSomeBlocks(term.someCount);
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

    private boolean prefilterStructures(WorldContext ctx, SearchConfig.SearchCenter center, int[] spawn) {
        boolean checkOrigin = center == SearchConfig.SearchCenter.ORIGIN
                || center == SearchConfig.SearchCenter.BOTH;
        boolean checkSpawn = (center == SearchConfig.SearchCenter.SPAWN
                || center == SearchConfig.SearchCenter.BOTH) && spawn != null;

        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
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
                if (checkOrigin && checkSpawn && farFailsOrigin && farFailsSpawn) return false;
                if (checkOrigin && !checkSpawn && farFailsOrigin) return false;
                if (checkSpawn && !checkOrigin && farFailsSpawn) return false;
                continue;
            }

            if (mod == Modifier.SOME && term.someCount > 1) {
                int effectiveBlocks = SeedValidator.effectiveSomeBlocks(term.someCount);
                int scanChunks = Math.max(searchRadiusChunks, (effectiveBlocks + 15) / 16);
                boolean enoughOrigin = checkOrigin && countPlacements(ctx, 0, 0, scanChunks, effectiveBlocks, term.canonical) >= term.someCount;
                boolean enoughSpawn = checkSpawn && countPlacements(ctx, spawn[0], spawn[1], scanChunks, effectiveBlocks, term.canonical) >= term.someCount;
                if (checkOrigin && checkSpawn && !enoughOrigin && !enoughSpawn) return false;
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
            if (!found) return false;
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
        int target = config.effectiveCandidateTarget(progress.snapshot().elapsedMs());
        return candidates.size() >= target;
    }

    private void reportFound(SearchResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("[thread ").append(threadIndex).append("] candidate seed ").append(r.seed);
        if (config.searchCenter() == SearchConfig.SearchCenter.SPAWN) {
            sb.append(" → spawn (").append(r.centerX).append(", ").append(r.centerZ).append(")");
        } else if (config.searchCenter() == SearchConfig.SearchCenter.BOTH) {
            sb.append(" → BOTH center=(").append(r.centerX).append(", ").append(r.centerZ).append(")");
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

        if (REJECTION_LOG_COUNTER.getAndIncrement() < 30) {
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
