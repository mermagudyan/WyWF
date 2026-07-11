package com.wywtf.search;

import com.wywtf.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Координатор поиска.
 *
 * Запускает N рабочих потоков через {@link ExecutorService}.
 * Каждый поток работает по схеме stride+offset, поэтому сиды не пересекаются.
 *
 * Жизненный цикл:
 *   1. start(query, config, onFound) — запускает пул.
 *   2. Рабочие потоки обновляют {@link SearchProgress}.
 *   3. При нахождении — onFound callback (в рабочем потоке!).
 *      Caller должен переключиться на main/render thread сам.
 *   4. cancel() — останавливает все потоки.
 *
 * Гарантии:
 *   - Главный поток Minecraft не блокируется.
 *   - Все счётчики в атомиках — GUI читает безопасно.
 *   - Никаких лишних аллокаций в горячем цикле (контекст мира — единственная аллокация на сид).
 */
public final class SeedSearcher {

    private final WorldContextFactory contextFactory;
    private final StructureChecker    structureChecker;
    private final BiomeChecker        biomeChecker;
    private final SpawnFinder         spawnFinder;

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
        this.spawnFinder      = new SpawnFinder();
    }

    public SearchProgress progress() { return progress; }

    public boolean isRunning() { return running.get(); }

    /** Запускает поиск. Блокирующий только в части подготовки пула, потом сразу возвращается. */
    public synchronized void start(ParsedQuery query, SearchConfig config, Consumer<SearchResult> onFound) {
        if (running.get()) throw new IllegalStateException("Search already running");

        int threadCount = config.resolveThreadCount();
        activeConfig = config;
        globalSeedCursor.set(0);
        running.set(true);
        progress.start(threadCount);

        SeedValidator validator = new SeedValidator(
                structureChecker, biomeChecker, spawnFinder,
                config.searchRadiusChunks(), config.biomeCheckRadiusChunks()
        );

        pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "WYYTF-Search-" + threadCount);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);  // не вытеснять render thread
            return t;
        });

        List<Future<?>> fs = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            SearchWorker worker = new SearchWorker(
                    i, threadCount, query, config,
                    contextFactory, validator, progress,
                    running, globalSeedCursor, onFound
            );
            fs.add(pool.submit(worker));
        }
        futures = fs;

        // Когда все потоки завершились — закрываем пул и выставляем флаг.
        pool.execute(() -> {
            for (Future<?> f : futures) {
                try { f.get(); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {}
            }
            shutdownPool();
            running.set(false);
            progress.finish();
        });
    }

    /** Останавливает поиск. Потоки завершаются на следующей итерации. */
    public synchronized void cancel() {
        running.set(false);
        shutdownPool();
    }

    private void shutdownPool() {
        if (pool != null) {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    // жёстко
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            pool = null;
        }
    }
}
