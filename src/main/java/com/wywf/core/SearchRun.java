package com.wywf.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

public final class SearchRun {
    private final LongSupplier clock;
    private final long started;
    private final Set<Long> excluded = new HashSet<>();
    private final java.util.concurrent.atomic.AtomicLong placeCursor=new java.util.concurrent.atomic.AtomicLong();
    public long nextPlace(){return placeCursor.getAndIncrement();}
    private long checked,discarded;
    private boolean retry;

    public SearchRun() { this(() -> System.nanoTime() / 1_000_000L); }
    SearchRun(LongSupplier clock) { this.clock = clock; started = clock.getAsLong(); }

    public Set<Long> excluded() { return Set.copyOf(excluded); }

    public void batch(Collection<Long> seeds, long count, String reason) { batch(seeds,count,0,reason); }

    public void batch(Collection<Long> seeds,long count,long rejected,String reason) {
        discarded=SearchStatistics.add(discarded,rejected);
        int previous = excluded.size();
        excluded.addAll(seeds);
        checked = count > Long.MAX_VALUE - checked ? Long.MAX_VALUE : checked + Math.max(0, count);
        retry = (excluded.size()>previous||placeCursor.get()>0&&count>0) && reason != null && reason.startsWith("collected ");
    }

    public long completedCheckedSeeds(){return checked;}
    public long completedDiscardedSeeds(){return discarded;}

    public long elapsedMs() { return Math.max(0, clock.getAsLong() - started); }

    public long remainingTimeMs(SearchConfig config) {
        return Math.max(0, config.timeLimitMs() - elapsedMs());
    }

    public long remainingSeeds(SearchConfig config) {
        return config.infiniteSeeds() ? Long.MAX_VALUE : Math.max(0, config.maxSeedsToCheck() - checked);
    }

    public boolean canContinue(SearchConfig config) {
        return retry && remainingTimeMs(config) > 0 && remainingSeeds(config) > 0;
    }
}
