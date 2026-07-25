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

    private final long startOffset;

    @Override
    public void run() {
        boolean accurateRings = query.structures().contains("minecraft:stronghold");
        SearchConfig.SearchCenter center = config.searchCenter();

        if (usesSplit()) {
            LOGGER.info("[thread {}] start: threads={}, center={}, 48/16 split",
                    threadIndex, threadCount, center);
            runSplit(startOffset, accurateRings, center);
        } else {
            LOGGER.info("[thread {}] start: threads={}, center={}, linear (biome-only)",
                    threadIndex, threadCount, center);
            runLinear(accurateRings, center);
        }
    }

    private boolean usesSplit() {
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
            return true;
        }
        return false;
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

            int[] spawn = null;
            if (center == SearchConfig.SearchCenter.SPAWN || center == SearchConfig.SearchCenter.BOTH) {
                Set<String> waterBiomes = pfCtx.spawnPredictor != null
                        ? pfCtx.spawnPredictor.waterBiomes() : Set.of();
                spawn = SeedValidator.findApproxSpawnPos(pfCtx.biomeSource, pfCtx.sampler(), waterBiomes);
            }

            if (!prefilterStructures(pfCtx, center, spawn)) {
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

                try {
                    int[] fullSpawn = spawn;
                    if (spawn != null) {
                        WorldContext fullCtx = contextFactory.create(seed, accurateRings);
                        Set<String> wb = fullCtx.spawnPredictor != null
                                ? fullCtx.spawnPredictor.waterBiomes() : Set.of();
                        fullSpawn = SeedValidator.findApproxSpawnPos(fullCtx.biomeSource, fullCtx.sampler(), wb);
                    }

                    SeedValidator.Outcome outcome = validateSeed(seed, accurateRings, center, fullSpawn);

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
                int[] spawn = null;
                if (center == SearchConfig.SearchCenter.SPAWN || center == SearchConfig.SearchCenter.BOTH) {
                    WorldContext seedCtx = contextFactory.create(seed, accurateRings);
                    Set<String> waterBiomes = seedCtx.spawnPredictor != null
                            ? seedCtx.spawnPredictor.waterBiomes() : Set.of();
                    spawn = SeedValidator.findApproxSpawnPos(seedCtx.biomeSource, seedCtx.sampler(), waterBiomes);
                }

                SeedValidator.Outcome outcome = validateSeed(seed, accurateRings, center, spawn);

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

    private SeedValidator.Outcome validateSeed(long seed, boolean accurateRings,
                                               SearchConfig.SearchCenter center, int[] spawn) {
        return switch (center) {
            case ORIGIN -> {
                WorldContext ctx = contextFactory.create(seed, accurateRings);
                Set<String> wb = ctx.spawnPredictor != null
                        ? ctx.spawnPredictor.waterBiomes() : Set.of();
                int[] predictedSpawn = SeedValidator.findApproxSpawnPos(ctx.biomeSource, ctx.sampler(), wb);
                SeedValidator.Outcome outcome = validator.validate(contextFactory, seed, query, accurateRings);
                if (outcome.accepted && SPAWN_DEBUG_COUNTER.getAndIncrement() < 20) {
                    LOGGER.warn("[ORIGIN] seed={} center=(0,0) predictedSpawn=({},{}) struct=({},{}) dist={}",
                            seed, predictedSpawn[0], predictedSpawn[1],
                            outcome.structureX, outcome.structureZ,
                            (int) Math.round(Math.sqrt(
                                    Math.pow(outcome.structureX, 2) + Math.pow(outcome.structureZ, 2))));
                }
                yield outcome;
            }
            case SPAWN -> validator.validate(contextFactory, seed, query, accurateRings, spawn[0], spawn[1]);
            case BOTH -> {
                SeedValidator.Outcome spawnOutcome = validator.validate(contextFactory, seed, query, accurateRings, spawn[0], spawn[1]);
                if (spawnOutcome.accepted) {
                    if (SPAWN_DEBUG_COUNTER.getAndIncrement() < 20) {
                        LOGGER.warn("[BOTH-SPAWN] seed={} spawn=({},{}) struct=({},{})",
                                seed, spawn[0], spawn[1], spawnOutcome.structureX, spawnOutcome.structureZ);
                    }
                    yield spawnOutcome;
                }
                WorldContext ctx = contextFactory.create(seed, accurateRings);
                Set<String> wb = ctx.spawnPredictor != null
                        ? ctx.spawnPredictor.waterBiomes() : Set.of();
                int[] predictedSpawn = SeedValidator.findApproxSpawnPos(ctx.biomeSource, ctx.sampler(), wb);
                SeedValidator.Outcome originOutcome = validator.validate(contextFactory, seed, query, accurateRings);
                if (originOutcome.accepted && SPAWN_DEBUG_COUNTER.getAndIncrement() < 20) {
                    LOGGER.warn("[BOTH-ORIGIN] seed={} center=(0,0) predictedSpawn=({},{}) struct=({},{})",
                            seed, predictedSpawn[0], predictedSpawn[1],
                            originOutcome.structureX, originOutcome.structureZ);
                }
                yield originOutcome;
            }
        };
    }

    private static final int FAR_MAX_CHUNKS = (1000 + 15) / 16;
    private static final int FAR_MIN_BLOCKS = 500;

    private boolean prefilterStructures(WorldContext ctx, SearchConfig.SearchCenter center, int[] spawn) {
        boolean checkOrigin = center == SearchConfig.SearchCenter.ORIGIN
                || center == SearchConfig.SearchCenter.BOTH;
        boolean checkSpawn = (center == SearchConfig.SearchCenter.SPAWN
                || center == SearchConfig.SearchCenter.BOTH) && spawn != null;

        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
            Modifier mod = term.modifier;

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
                found = structureChecker.hasAnyPlacementWithin(ctx, 0, 0, radius, term.canonical);
            }
            if (!found && checkSpawn) {
                found = structureChecker.hasAnyPlacementWithin(ctx, spawn[0], spawn[1], radius, term.canonical);
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean farWouldFail(WorldContext ctx, int cx, int cz, String canonical) {
        List<int[]> pos = structureChecker.positionsPlacementOnly(ctx, cx, cz, FAR_MAX_CHUNKS, canonical);
        if (pos.isEmpty()) return true;
        int nearestDist = Integer.MAX_VALUE;
        for (int[] p : pos) {
            double dx = p[0] - cx;
            double dz = p[1] - cz;
            int d = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            if (d < nearestDist) nearestDist = d;
        }
        return nearestDist < FAR_MIN_BLOCKS;
    }

    private int countPlacements(WorldContext ctx, int cx, int cz, int radiusChunks, int effectiveBlocks, String canonical) {
        List<int[]> pos = structureChecker.positionsPlacementOnly(ctx, cx, cz, radiusChunks, canonical);
        int count = 0;
        for (int[] p : pos) {
            long dx = p[0] - cx;
            long dz = p[1] - cz;
            long d2 = dx * dx + dz * dz;
            int d = (int) Math.round(Math.sqrt(d2));
            if (d <= effectiveBlocks) count++;
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
        } else {
            sb.append(" → center (").append(r.centerX).append(", ").append(r.centerZ).append(")");
        }
        for (var entry : r.structurePositions.entrySet()) {
            int[] pos = entry.getValue();
            double dx = pos[0] - r.centerX;
            double dz = pos[1] - r.centerZ;
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

        switch (outcome.reason) {
            case NO_STRUCTURE ->
                    LOGGER.trace("seed {} rejected: {}", seed, outcome.reason.description);
            case BIOME_MISMATCH, OBJECT_MISMATCH -> {
                if (outcome.structuresMatched) {
                    LOGGER.debug("seed {} rejected: structure {} present @({}, {}), but {}",
                            seed, outcome.structureId, outcome.structureX, outcome.structureZ,
                            outcome.reason.description);
                } else {
                    LOGGER.trace("seed {} rejected: {}", seed, outcome.reason.description);
                }
            }
            default -> LOGGER.trace("seed {} rejected: {}", seed, outcome.reason.description);
        }
    }
}
