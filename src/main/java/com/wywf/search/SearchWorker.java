package com.wywf.search;

import com.wywf.core.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
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
    }

    private static final long SIZE48 = 1L << 48;
    private static final long SIZE16 = 1L << 16;

    private final long startOffset;

    @Override
    public void run() {
        boolean accurateRings = query.structures().contains("minecraft:stronghold");

        if (usesSplit()) {
            LOGGER.info("[thread {}] start: threads={}, mode=origin-relative 48/16 split",
                    threadIndex, threadCount);
            runSplit(startOffset, accurateRings);
        } else {
            LOGGER.info("[thread {}] start: threads={}, mode=linear (biome-only)",
                    threadIndex, threadCount);
            runLinear(accurateRings);
        }
    }

    private boolean usesSplit() {
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
            Modifier mod = term.modifier;
            if (mod == Modifier.NEVER || mod == Modifier.FAR) continue;
            return true;
        }
        return false;
    }

    private void runSplit(long offset, boolean accurateRings) {
        long step = Math.max(1, threadCount);
        long localChecked = 0;

        for (long i = 0; i < SIZE48; i += step) {
            long a = (i + offset) % SIZE48;
            if (!running.get()) {
                LOGGER.info("[thread {}] stopped (checked {} seeds locally)", threadIndex, localChecked);
                return;
            }

            WorldContext pfCtx = contextFactory.create(a, accurateRings);
            if (!prefilterStructures(pfCtx)) {
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
                    SeedValidator.Outcome outcome =
                            validator.validate(contextFactory, seed, query, accurateRings);

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

    private void runLinear(boolean accurateRings) {
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
                SeedValidator.Outcome outcome =
                        validator.validate(contextFactory, seed, query, accurateRings);

                globalSeedCursor.incrementAndGet();
                localChecked++;
                progress.onSeedChecked();
                logOutcome(seed, outcome);

                if (outcome.accepted) {
                    reportFound(outcome.result);
                    if (enoughCandidates()) return;
                }
            } catch (Throwable t) {
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

    private boolean prefilterStructures(WorldContext ctx) {
        for (ParsedQuery.Term term : query.terms()) {
            if (term.category != KeywordDictionary.Category.STRUCTURE) continue;
            Modifier mod = term.modifier;
            if (mod == Modifier.NEVER || mod == Modifier.FAR) continue;
            int radius = validator.structureScanRadiusChunks(term);
            if (!structureChecker.hasAnyPlacementWithin(ctx, 0, 0, radius, term.canonical)) {
                return false;
            }
        }
        return true;
    }

    private boolean enoughCandidates() {
        int target = config.effectiveCandidateTarget(progress.snapshot().elapsedMs());
        return candidates.size() >= target;
    }

    private void reportFound(SearchResult r) {
        LOGGER.info("[thread {}] candidate seed {} → center ({}, {}), match: {}",
                threadIndex, r.seed, r.centerX, r.centerZ, r.primaryDescription);
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
