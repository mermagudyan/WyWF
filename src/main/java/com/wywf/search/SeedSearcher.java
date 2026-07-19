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
            LOGGER.warn("No searchable terms in query \"{}\" — nothing to search, aborting", rawQuery.raw());
            return;
        }

        candidates.clear();

        int threadCount = config.resolveThreadCount();
        activeConfig = config;
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
            LOGGER.warn("Ignored words (not recognized as biome/structure/block): {}", query.ignoredWords());
        }
        LOGGER.info("Threads: {}, structure radius: {} chunks, biome radius: {} chunks (step {}), limit: {} seeds",
                threadCount, config.searchRadiusChunks(), config.biomeCheckRadiusChunks(),
                config.biomeSampleStepChunks(), config.unbounded() ? "\u221e" : config.maxSeeds());

        if (biomeChecker instanceof VanillaBiomeChecker vbc) {
            vbc.stepChunks(config.biomeSampleStepChunks());
        }

        SeedValidator validator = new SeedValidator(
                structureChecker, biomeChecker,
                config.searchRadiusChunks(), config.biomeCheckRadiusChunks()
        );

        pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "WYWF-Search-" + threadCount);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        List<Future<?>> fs = new ArrayList<>(threadCount);
        long startOffset = config.randomizeStart()
                ? new java.util.Random().nextLong() & 0xFFFFFFFFFFFFL
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
            long lastTime = System.currentTimeMillis();
            int lastTarget = config.candidatesToCollect();
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
                double perSec = dChecked * 1000.0 / Math.max(1, now - lastTime);
                LOGGER.info("[progress] checked {} seeds, ~{}/sec, discarded {}, elapsed {} ms",
                        s.checkedSeeds(), Math.round(perSec), s.discardedSeeds(), s.elapsedMs());
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
                chosen = candidates.get(new java.util.Random().nextInt(poolSize));
            }
            if (chosen != null) {
                String reason = stopReason != null ? stopReason
                        : (snap.finished() ? "search complete" : "candidate found");
                SearchResult finalResult = new SearchResult(
                        chosen.seed, chosen.centerX, chosen.centerZ,
                        chosen.primaryDescription, chosen.matchedStructures,
                        chosen.matchedBiomes, reason);
                LOGGER.info("===== Search finished: seed {} chosen from {} candidate(s) after {} checked seeds ({} ms) =====",
                        chosen.seed, poolSize, snap.checkedSeeds(), snap.elapsedMs());
                onFound.accept(finalResult);
            } else {
                LOGGER.info("===== Search finished: no matching seed found. Checked {} seeds ({} ms) =====",
                        snap.checkedSeeds(), snap.elapsedMs());
            }
        });
    }

    private ParsedQuery filterToAvailable(ParsedQuery q) {
        List<ParsedQuery.Term> kept = new ArrayList<>(q.terms().size());
        boolean changed = false;

        for (ParsedQuery.Term term : q.terms()) {
            switch (term.category) {
                case STRUCTURE -> {
                    boolean any = false;
                    for (String realId : dictionary.getVariants(term.canonical)) {
                        if (contextFactory.isStructureAvailable(realId)) { any = true; break; }
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
                    LOGGER.warn("Object '{}' cannot be searched offline yet — ignoring keyword", term.canonical);
                }
                default -> kept.add(term);
            }
        }

        if (!changed) {
            return q;
        }
        return new ParsedQuery(q.raw(), kept);
    }

    public synchronized void cancel() {
        running.set(false);
        shutdownPool();
    }

    private void shutdownPool() {
        if (pool != null) {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {

                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            pool = null;
        }
    }
}
