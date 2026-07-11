package com.wywtf.search;

import com.wywtf.core.*;
import java.util.Optional;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Один рабочий поток поиска.
 *
 * Перебирает свою последовательность сидов по схеме «stride + offset»,
 * гарантируя отсутствие пересечений с другими потоками:
 *
 *   поток i → сиды:  i, i+T, i+2T, i+3T, ...   (T = общее число потоков)
 *
 * Пример для 4 потоков:
 *   0: 0, 4, 8, 12, 16, ...
 *   1: 1, 5, 9, 13, 17, ...
 *   2: 2, 6, 10, 14, 18, ...
 *   3: 3, 7, 11, 15, 19, ...
 */
public final class SearchWorker implements Runnable {

    private final int             threadIndex;
    private final int             threadCount;
    private final ParsedQuery     query;
    private final SearchConfig    config;
    private final WorldContextFactory contextFactory;
    private final SeedValidator   validator;
    private final SearchProgress  progress;
    private final AtomicBoolean   running;
    private final AtomicLong      globalSeedCursor;     // глобальный курсор (для лимита)
    private final Consumer<SearchResult> onFound;

    public SearchWorker(int threadIndex, int threadCount,
                        ParsedQuery query, SearchConfig config,
                        WorldContextFactory contextFactory,
                        SeedValidator validator,
                        SearchProgress progress,
                        AtomicBoolean running,
                        AtomicLong globalSeedCursor,
                        Consumer<SearchResult> onFound) {
        this.threadIndex       = threadIndex;
        this.threadCount       = threadCount;
        this.query             = query;
        this.config            = config;
        this.contextFactory    = contextFactory;
        this.validator         = validator;
        this.progress          = progress;
        this.running           = running;
        this.globalSeedCursor  = globalSeedCursor;
        this.onFound           = onFound;
    }

    @Override
    public void run() {
        long stride = threadCount;
        long start  = threadIndex;
        long maxSeeds = config.maxSeeds();

        for (long offset = 0; ; offset++) {
            if (!running.get()) return;

            long globalChecked = globalSeedCursor.get();
            if (globalChecked >= maxSeeds) return;

            long seed = start + offset * stride;
            if (seed < 0) {
                // переполнение long — выходим
                return;
            }

            try {
                WorldContext ctx = contextFactory.create(seed);
                Optional<SearchResult> result = validator.validate(ctx, query);

                long checked = globalSeedCursor.incrementAndGet();
                progress.onSeedChecked();

                if (result.isPresent()) {
                    if (progress.offerCandidate(result.get())) {
                        onFound.accept(result.get());
                    }
                    return; // найден — поток завершается, SeedSearcher остановит остальных
                }

                if (checked % 10_000L == 0 && checked >= maxSeeds) {
                    return;
                }

            } catch (Throwable t) {
                // Не падать — просто пропускаем этот сид
                progress.onSeedDiscarded();
            }
        }
    }
}
