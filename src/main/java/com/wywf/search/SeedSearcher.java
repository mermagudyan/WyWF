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

    private volatile ExecutorService        pool;
    private volatile List<Future<?>>        futures = List.of();
    private final AtomicBoolean             running = new AtomicBoolean(false);
    private final AtomicLong                globalSeedCursor = new AtomicLong(0);

    private final SearchProgress progress = new SearchProgress();
    private volatile SearchConfig activeConfig;

    public SeedSearcher(WorldContextFactory contextFactory) {
        this.contextFactory   = contextFactory;
        this.structureChecker = new VanillaStructureChecker();
        this.biomeChecker     = new VanillaBiomeChecker();
    }

    public SearchProgress progress() { return progress; }

    public boolean isRunning() { return running.get(); }

    public synchronized void start(ParsedQuery rawQuery, SearchConfig config, Consumer<SearchResult> onFound) {
        if (running.get()) throw new IllegalStateException("Search already running");

        ParsedQuery query = filterToAvailable(rawQuery);

        if (query.isEmpty()) {
            LOGGER.warn("No searchable terms in query \"{}\" — nothing to search, aborting", rawQuery.raw());
            return;
        }

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
        for (int i = 0; i < threadCount; i++) {
            SearchWorker worker = new SearchWorker(
                    i, threadCount, query,
                    contextFactory, validator, progress,
                    running, globalSeedCursor, onFound
            );
            fs.add(pool.submit(worker));
        }
        futures = fs;

        Thread monitor = new Thread(() -> {
            long lastChecked = 0;
            long lastTime = System.currentTimeMillis();
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
            SearchResult best = snap.currentBest();
            if (best != null) {
                LOGGER.info("===== Search finished: seed {} found after {} checked seeds ({} ms) =====",
                        best.seed, snap.checkedSeeds(), snap.elapsedMs());
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
                    for (String realId : VanillaStructureChecker.expand(term.canonical)) {
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
