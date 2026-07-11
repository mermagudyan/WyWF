package com.wywtf.core;

import java.util.concurrent.atomic.*;

/**
 * Потокобезопасный прогресс поиска.
 *
 * Всё хранится в атомиках — рабочие потоки обновляют счётчики без блокировок.
 * GUI читает снапшот каждые ~50ms.
 */
public final class SearchProgress {

    private final AtomicLong checkedSeeds   = new AtomicLong(0);
    private final AtomicLong discardedSeeds = new AtomicLong(0);
    private final AtomicLong startTimeMs    = new AtomicLong(0);
    private final AtomicLong endTimeMs      = new AtomicLong(0);
    private final AtomicInteger threads      = new AtomicInteger(0);

    // Лучший кандидат на данный момент (по «близости» к идеалу)
    private volatile SearchResult currentBest;
    private final Object bestLock = new Object();

    // ---- API для рабочих потоков ------------------------------------------

    public void start(int threadCount) {
        threads.set(threadCount);
        checkedSeeds.set(0);
        discardedSeeds.set(0);
        startTimeMs.set(System.currentTimeMillis());
        endTimeMs.set(0);
        currentBest = null;
    }

    public void onSeedChecked() {
        checkedSeeds.incrementAndGet();
    }

    public void onSeedDiscarded() {
        discardedSeeds.incrementAndGet();
    }

    /** Возвращает true, если этот результат стал новым «лучшим». */
    public boolean offerCandidate(SearchResult candidate) {
        synchronized (bestLock) {
            if (currentBest == null) {
                currentBest = candidate;
                return true;
            }
            // Здесь можно ввести «score» — пока первый найденный = лучший.
            return false;
        }
    }

    public void finish() {
        endTimeMs.set(System.currentTimeMillis());
    }

    // ---- API для GUI ------------------------------------------------------

    public Snapshot snapshot() {
        long checked = checkedSeeds.get();
        long start   = startTimeMs.get();
        long end     = endTimeMs.get();
        long now     = System.currentTimeMillis();
        long elapsed = (end != 0 ? end : now) - start;
        elapsed = Math.max(1L, elapsed);

        return new Snapshot(
                checked,
                discardedSeeds.get(),
                threads.get(),
                elapsed,
                checked * 1000L / elapsed,        // seeds/sec
                currentBest,
                end != 0
        );
    }

    public SearchResult currentBest() {
        return currentBest;
    }

    /** Immutable снимок прогресса для GUI. */
    public record Snapshot(
            long checkedSeeds,
            long discardedSeeds,
            int  threads,
            long elapsedMs,
            long seedsPerSecond,
            SearchResult currentBest,
            boolean finished
    ) {}
}
