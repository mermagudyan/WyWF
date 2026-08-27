package com.wywf.search;

import com.wywf.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SeedSearcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");

    private final WorldContextFactory contextFactory;
    private final StructureChecker    structureChecker;
    private final BiomeChecker        biomeChecker;
    private final KeywordDictionary   dictionary;

    private volatile ExecutorService        pool;
    private volatile List<Future<?>>        futures = List.of();
    private volatile AtomicBoolean          running = new AtomicBoolean(false);
    private volatile boolean               cancelledByUser = false;
    private final AtomicLong                globalSeedCursor = new AtomicLong(0);

    private final SearchProgress progress = new SearchProgress();
    private volatile SearchConfig activeConfig;

    private final List<SearchResult> candidates = Collections.synchronizedList(new ArrayList<>());

    private volatile String stopReason;

    public SeedSearcher(WorldContextFactory contextFactory, KeywordDictionary dictionary) {
        this.contextFactory   = contextFactory;
        this.dictionary       = dictionary;
        this.structureChecker = new VanillaStructureChecker(dictionary);
        this.biomeChecker     = new VanillaBiomeChecker();
    }

    public SearchProgress progress() { return progress; }

    public List<SearchResult> candidates() { return candidates; }

    public boolean isRunning() { return running.get(); }

    public synchronized void start(ParsedQuery rawQuery, SearchConfig config, Consumer<SearchResult> onFound) {
        if (running.get()) throw new IllegalStateException("Search already running");

        ParsedQuery query = filterToAvailable(rawQuery);

        if (query.isEmpty()) {
            LOGGER.info("No searchable terms in query \"{}\" — nothing to search, aborting", rawQuery.raw());
            String reason = rawQuery.ignoredWords().isEmpty()
                    ? "query is empty — no recognized keywords"
                    : "all keywords not available in this game version: " + String.join(", ", rawQuery.ignoredWords());
            SearchResult emptyResult = new SearchResult(
                    0, 0, 0, 0, 0, null, List.of(), List.of(), reason, Map.of(), Map.of());
            onFound.accept(emptyResult);
            return;
        }

        candidates.clear();

        // Fresh flag per search: a stale worker from a cancelled search holds the
        // OLD instance (false) and dies, instead of resurrecting against this
        // search's cursor/progress when start() flips a shared flag to true.
        running = new AtomicBoolean(false);

        final SearchConfig searchCfg = config.copy();
        int threadCount = searchCfg.resolveThreadCount();
        activeConfig = searchCfg;
        cancelledByUser = false;
        stopReason = null;
        globalSeedCursor.set(0);
        running.set(true);
        progress.start(threadCount);

        LOGGER.info("===== Starting seed search =====");
        LOGGER.info("Native mode: {}", searchCfg.nativeMode());
        CubiomesBridge.setMode(searchCfg.nativeMode());
        // Fail FAST and clearly when NATIVE was demanded but the accelerator
        // is missing, instead of poisoning every seed with deep exceptions.
        if (!CubiomesBridge.isAvailable()) {
            String reason;
            boolean abort = false;
            if (searchCfg.nativeMode() == SearchConfig.NativeMode.NATIVE) {
                reason = "NATIVE mode is on but the cubiomes DLL failed to load - "
                        + "switch Native mode to AUTO or CLASSIC in settings";
                abort = true;
            } else if (searchCfg.nativeMode() == SearchConfig.NativeMode.AUTO) {
                reason = "accelerator DLL not loaded - running in slow Java mode "
                        + "(see 'Accelerator' row during the search)";
            } else {
                reason = null;
            }
            if (reason != null) LOGGER.warn("[SeedSearcher] {}", reason);
            if (abort) {
                // NATIVE demanded but unavailable: fail fast instead of
                // discarding every seed with deep exceptions.
                SearchResult failResult = new SearchResult(0, 0, 0, 0, 0,
                        null, List.of(), List.of(), reason, Map.of(), Map.of());
                running.set(false);
                progress.finish();
                onFound.accept(failResult);
                return;
            }
        }
        LOGGER.info("Query: \"{}\"", query.raw());
        LOGGER.info("Terms: {}", query.terms().isEmpty() ? "(none)" : query.terms());
        LOGGER.info("Looking for structures: {}", query.structures().isEmpty() ? "(none)" : query.structures());
        LOGGER.info("Looking for biomes: {}",     query.biomes().isEmpty()     ? "(none)" : query.biomes());
        LOGGER.info("Looking for objects: {}",   query.objects().isEmpty()    ? "(none)" : query.objects());
        if (!query.ignoredWords().isEmpty()) {
            LOGGER.info("Ignored words (not recognized as biome/structure/block): {}", query.ignoredWords());
        }
        LOGGER.info("Threads: {}, structure radius: {} chunks, biome radius: {} chunks (step {}), limit: {}",
                threadCount, searchCfg.searchRadiusChunks(), searchCfg.biomeCheckRadiusChunks(),
                searchCfg.biomeSampleStepChunks(),
                searchCfg.infiniteSeeds() ? "unlimited" : searchCfg.maxSeedsToCheck() + " seeds");

        if (biomeChecker instanceof VanillaBiomeChecker vbc) {
            vbc.stepChunks(searchCfg.biomeSampleStepChunks());
        }

        SeedValidator validator = new SeedValidator(
                structureChecker, biomeChecker,
                searchCfg.searchRadiusChunks(), searchCfg.biomeCheckRadiusChunks()
        );

        var threadNum = new java.util.concurrent.atomic.AtomicInteger(0);
        pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "WYWF-Search-" + threadNum.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        List<Future<?>> fs = new ArrayList<>(threadCount);
        long startOffset = config.randomizeStart()
                ? java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL
                : 0L;
        for (int i = 0; i < threadCount; i++) {
            SearchWorker worker = new SearchWorker(
                    i, threadCount, query,
                    contextFactory, validator, progress,
                    running, globalSeedCursor, startOffset, candidates,
                    config
            );
            fs.add(pool.submit(() -> {
                try {
                    worker.run();
                } finally {
                    CubiomesBridge.destroyCurrentGenerator();
                }
            }));
        }
        futures = fs;

        Thread monitor = new Thread(() -> {
            long lastChecked = 0;
            long lastDiscarded = 0;
            long lastTime = System.currentTimeMillis();
            int lastTarget = searchCfg.candidatesToCollect();
            long searchStartTime = lastTime;
            long timeLimitMs = searchCfg.timeLimitMs();
            long maxSeeds = searchCfg.maxSeedsToCheck();
            while (running.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!running.get()) return;
                SearchProgress.Snapshot s = progress.snapshot();
                long now = System.currentTimeMillis();
                long dChecked = s.checkedSeeds() - lastChecked;
                long dDiscarded = s.discardedSeeds() - lastDiscarded;
                double checkedPerSec = dChecked * 1000.0 / Math.max(1, now - lastTime);
                double discardedPerSec = dDiscarded * 1000.0 / Math.max(1, now - lastTime);
                LOGGER.info("[progress] checked {} (~{}/sec), discarded {} (~{}/sec), candidates {}, elapsed {} ms",
                        s.checkedSeeds(), Math.round(checkedPerSec),
                        s.discardedSeeds(), Math.round(discardedPerSec),
                        candidates.size(), s.elapsedMs());
                lastDiscarded = s.discardedSeeds();
                int target = searchCfg.effectiveCandidateTarget(s.elapsedMs());
                if (target < lastTarget) {
                    LOGGER.info("[progress] query is slow — collected {} of {} candidates, ramping target down to {}",
                            candidates.size(), searchCfg.candidatesToCollect(), target);
                    lastTarget = target;
                }
                if (!candidates.isEmpty() && candidates.size() >= target) {
                    stopReason = "collected " + candidates.size() + " candidate(s) (target " + target + ")";
                    LOGGER.warn("[SeedSearcher] Stopping: {}", stopReason);
                    running.set(false);
                }
                if (timeLimitMs > 0 && (now - searchStartTime) >= timeLimitMs) {
                    stopReason = "time limit reached (" + searchCfg.timeLimitMinutes() + " min)";
                    LOGGER.warn("[SeedSearcher] Stopping: {}", stopReason);
                    running.set(false);
                }
                if (s.checkedSeeds() >= maxSeeds) {
                    stopReason = "seed limit reached (" + formatNumber(maxSeeds) + " seeds)";
                    LOGGER.warn("[SeedSearcher] Stopping: {}", stopReason);
                    running.set(false);
                }
                lastChecked = s.checkedSeeds();
                lastTime = now;
            }
        }, "WYWF-Search-Monitor");
        monitor.setDaemon(true);
        monitor.start();

        // Run completion off the worker pool to avoid starvation (pool size N workers + 1 task)
        Thread completionThread = new Thread(() -> {
            try {
                for (Future<?> f : futures) {
                    try { f.get(); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        LOGGER.error("[SeedSearcher] Worker future threw exception", e);
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("[SeedSearcher] Unexpected error while waiting for workers", t);
            }
            shutdownPool();
            running.set(false);
            progress.finish();

            SearchProgress.Snapshot snap;
            synchronized (candidates) {
                snap = progress.snapshot();
            }
            int poolSize;
            synchronized (candidates) {
                poolSize = candidates.size();
            }

            // Deep verify: exhaustive re-check of every candidate before showing.
            // Falls back to legacy selection when nothing survives (should not happen).
            List<SearchResult> snapshot;
            synchronized (candidates) {
                snapshot = new ArrayList<>(candidates);
            }
            SearchResult verified = DeepVerifier.pickBest(contextFactory, structureChecker,
                    query, activeConfig, snapshot, searchCfg.biomeCheckRadiusChunks(),
                    activeConfig != null && activeConfig.sortCandidatesByDistance());

            SearchResult chosen;
            synchronized (candidates) {
                if (verified != null) {
                    chosen = verified;
                } else if (!candidates.isEmpty()) {
                    if (activeConfig != null && activeConfig.sortCandidatesByDistance()) {
                        chosen = candidates.stream()
                                .min(Comparator.comparingDouble(SearchResult::distanceToStructure))
                                .orElse(null);
                    } else {
                        chosen = candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(poolSize));
                    }
                } else {
                    chosen = null;
                }
            }
            if (chosen != null) {
                String reason = stopReason != null ? stopReason
                        : (snap.finished() ? "search complete" : "candidate found");
                SearchResult finalResult = new SearchResult(
                        chosen.seed, chosen.centerX, chosen.centerZ,
                        chosen.structureX, chosen.structureZ,
                        chosen.primaryDescription, chosen.matchedStructures,
                        chosen.matchedBiomes, reason, chosen.structurePositions,
                        chosen.biomeDistances);
                StringBuilder distInfo = new StringBuilder();
                for (var entry : chosen.structurePositions.entrySet()) {
                    int[] pos = entry.getValue();
                    double dx = pos[0] - chosen.centerX;
                    double dz = pos[1] - chosen.centerZ;
                    int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                    if (!distInfo.isEmpty()) distInfo.append(", ");
                    distInfo.append(entry.getKey()).append(" ~").append(dist).append(" blocks");
                }
                for (var entry : chosen.biomeDistances.entrySet()) {
                    if (!distInfo.isEmpty()) distInfo.append(", ");
                    distInfo.append(entry.getKey()).append(" ~").append(entry.getValue()).append(" blocks");
                }
                LOGGER.info("===== Search finished: seed {} chosen from {} candidate(s) after {} checked seeds ({} ms, ~{} seeds/sec) native={} =====",
                        chosen.seed, poolSize, snap.checkedSeeds(), snap.elapsedMs(),
                        snap.elapsedMs() > 0 ? Math.round(snap.checkedSeeds() * 1000.0 / snap.elapsedMs()) : 0,
                        activeConfig != null ? activeConfig.nativeMode() : "unknown");
                LOGGER.info("  origin ({}, {}), distances: {}", chosen.centerX, chosen.centerZ, distInfo);
                if (!cancelledByUser) onFound.accept(finalResult);
            } else {
                LOGGER.warn("===== Search finished: no matching seed found. Checked {} seeds ({} ms, ~{} seeds/sec) native={} =====",
                        snap.checkedSeeds(), snap.elapsedMs(),
                        snap.elapsedMs() > 0 ? Math.round(snap.checkedSeeds() * 1000.0 / snap.elapsedMs()) : 0,
                        activeConfig != null ? activeConfig.nativeMode() : "unknown");
                if (!cancelledByUser) onFound.accept(null);
            }
            CubiomesBridge.destroyCurrentGenerator();
        }, "WYWF-Search-Completion");
        completionThread.setDaemon(true);
        completionThread.start();
    }

    private ParsedQuery filterToAvailable(ParsedQuery q) {
        List<ParsedQuery.Term> kept = new ArrayList<>(q.terms().size());
        boolean changed = false;

        for (ParsedQuery.Term term : q.terms()) {
            switch (term.category) {
                case STRUCTURE -> {
                    boolean any = isStructureUsable(term.canonical);
                    if (!any) {
                        for (String realId : dictionary.getVariants(term.canonical)) {
                            if (isStructureUsable(realId)) { any = true; break; }
                        }
                    }
                    if (any) {
                        kept.add(term);
                    } else {
                        changed = true;
                        LOGGER.info("Structure '{}' is not present in this game version — ignoring keyword", term.canonical);
                    }
                }
                case BIOME -> {
                    if (contextFactory.isBiomeAvailable(term.canonical)) {
                        kept.add(term);
                    } else {
                        changed = true;
                        LOGGER.info("Biome '{}' is not present in this game version — ignoring keyword", term.canonical);
                    }
                }
                case OBJECT -> {
                    changed = true;
                    LOGGER.info("Object '{}' cannot be searched offline yet — ignoring keyword", term.canonical);
                }
                default -> kept.add(term);
            }
        }

        if (!changed) {
            return q;
        }
        return new ParsedQuery(q.raw(), kept);
    }

    /** Checks if a structure ID exists in either the Structure registry or the StructureSet registry. */
    private boolean isStructureUsable(String id) {
        return contextFactory.isStructureAvailable(id) || contextFactory.isStructureSetAvailable(id);
    }

    public synchronized void cancel() {
        cancelledByUser = true;
        running.set(false);
        progress.finish();
        LOGGER.warn("[SeedSearcher] Search cancelled by user");
        shutdownPool();
    }

    private void shutdownPool() {
        ExecutorService p = pool;
        pool = null;
        if (p == null) return;
        p.shutdownNow();
        // Await termination off the calling thread: cancel() runs on the render
        // thread and must not stall the UI while workers unwind long scans.
        Thread reaper = new Thread(() -> {
            try {
                if (!p.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("[SeedSearcher] search workers did not terminate in time");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            CubiomesBridge.destroyCurrentGenerator();
        }, "WYWF-Pool-Reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    private static String formatNumber(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%,d", n).replace(',', ' ');
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fB", n / 1_000_000_000.0);
    }
}
