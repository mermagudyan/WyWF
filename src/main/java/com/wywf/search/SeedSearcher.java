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
    private final AtomicBoolean             running = new AtomicBoolean(false);
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

        int threadCount = config.resolveThreadCount();
        activeConfig = config;
        cancelledByUser = false;
        stopReason = null;
        globalSeedCursor.set(0);
        running.set(true);
        progress.start(threadCount);

        LOGGER.info("===== Starting seed search =====");
        LOGGER.info("Query: \"{}\"", query.raw());
        LOGGER.info("Terms: {}", query.terms().isEmpty() ? "(none)" : query.terms());
        LOGGER.info("Looking for structures: {}", query.structures().isEmpty() ? "(none)" : query.structures());
        LOGGER.info("Looking for biomes: {}",     query.biomes().isEmpty()     ? "(none)" : query.biomes());
        LOGGER.info("Looking for objects: {}",   query.objects().isEmpty()    ? "(none)" : query.objects());
        if (!query.ignoredWords().isEmpty()) {
            LOGGER.info("Ignored words (not recognized as biome/structure/block): {}", query.ignoredWords());
        }
        LOGGER.info("Threads: {}, structure radius: {} chunks, biome radius: {} chunks (step {}), limit: {}",
                threadCount, config.searchRadiusChunks(), config.biomeCheckRadiusChunks(),
                config.biomeSampleStepChunks(),
                config.infiniteSeeds() ? "unlimited" : config.maxSeedsToCheck() + " seeds");

        if (biomeChecker instanceof VanillaBiomeChecker vbc) {
            vbc.stepChunks(config.biomeSampleStepChunks());
        }

        SeedValidator validator = new SeedValidator(
                structureChecker, biomeChecker,
                config.searchRadiusChunks(), config.biomeCheckRadiusChunks()
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
            fs.add(pool.submit(worker));
        }
        futures = fs;

        Thread monitor = new Thread(() -> {
            long lastChecked = 0;
            long lastDiscarded = 0;
            long lastTime = System.currentTimeMillis();
            int lastTarget = config.candidatesToCollect();
            long searchStartTime = lastTime;
            long timeLimitMs = config.timeLimitMs();
            long maxSeeds = config.maxSeedsToCheck();
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
                int target = config.effectiveCandidateTarget(s.elapsedMs());
                if (target < lastTarget) {
                    LOGGER.info("[progress] query is slow — collected {} of {} candidates, ramping target down to {}",
                            candidates.size(), config.candidatesToCollect(), target);
                    lastTarget = target;
                }
                if (!candidates.isEmpty() && candidates.size() >= target) {
                    stopReason = "collected " + candidates.size() + " candidate(s) (target " + target + ")";
                    running.set(false);
                }
                if (timeLimitMs > 0 && (now - searchStartTime) >= timeLimitMs) {
                    stopReason = "time limit reached (" + config.timeLimitMinutes() + " min)";
                    running.set(false);
                }
                if (s.checkedSeeds() >= maxSeeds) {
                    stopReason = "seed limit reached (" + formatNumber(maxSeeds) + " seeds)";
                    running.set(false);
                }
                lastChecked = s.checkedSeeds();
                lastTime = now;
            }
        }, "WYWF-Search-Monitor");
        monitor.setDaemon(true);
        monitor.start();

        pool.execute(() -> {
            for (Future<?> f : futures) {
                try { f.get(); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {}
            }
            shutdownPool();
            running.set(false);
            progress.finish();

            SearchProgress.Snapshot snap = progress.snapshot();
            int poolSize = candidates.size();
            SearchResult chosen = null;
            if (!candidates.isEmpty()) {
                if (activeConfig != null && activeConfig.sortCandidatesByDistance()) {
                    chosen = candidates.stream()
                            .min(Comparator.comparingDouble(SearchResult::distanceToStructure))
                            .orElse(null);
                } else {
                    chosen = candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(poolSize));
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
                LOGGER.info("===== Search finished: seed {} chosen from {} candidate(s) after {} checked seeds ({} ms, ~{} seeds/sec) =====",
                        chosen.seed, poolSize, snap.checkedSeeds(), snap.elapsedMs(),
                        snap.elapsedMs() > 0 ? Math.round(snap.checkedSeeds() * 1000.0 / snap.elapsedMs()) : 0);
                LOGGER.info("  origin ({}, {}), distances: {}", chosen.centerX, chosen.centerZ, distInfo);
                if (!cancelledByUser) onFound.accept(finalResult);
            } else {
                LOGGER.info("===== Search finished: no matching seed found. Checked {} seeds ({} ms, ~{} seeds/sec) =====",
                        snap.checkedSeeds(), snap.elapsedMs(),
                        snap.elapsedMs() > 0 ? Math.round(snap.checkedSeeds() * 1000.0 / snap.elapsedMs()) : 0);
                if (!cancelledByUser) onFound.accept(null);
            }
        });
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
        shutdownPool();
    }

    private void shutdownPool() {
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
            try {
                if (!p.awaitTermination(2, TimeUnit.SECONDS)) {

                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String formatNumber(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%,d", n).replace(',', ' ');
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fB", n / 1_000_000_000.0);
    }
}
