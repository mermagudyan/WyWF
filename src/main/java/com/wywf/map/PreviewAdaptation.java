package com.wywf.map;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchDraft;
import com.wywf.search.MinecraftWorldContextFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

public final class PreviewAdaptation implements AutoCloseable {
    public record Goal(String biome, int x, int z) {}
    public record Result(long seed, boolean matched, int attempts, long elapsedMillis) {}
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), r -> {
                Thread thread = new Thread(r, "WyWF-ExampleFit");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile long revision;
    private volatile boolean paused;
    private volatile Result result;
    private Future<?> task;

    public static List<Goal> goals(List<SearchDraft.Placement> placements) {
        return placements.stream().filter(p -> p.target().category() == KeywordDictionary.Category.BIOME).map(p -> {
            double angle = Math.atan2(p.y(), p.x());
            return new Goal(p.target().canonical(), (int) Math.round(Math.cos(angle) * p.radius()),
                    (int) Math.round(Math.sin(angle) * p.radius()));
        }).toList();
    }

    private int fitSeconds = 5;
    public void fitSeconds(int seconds) { fitSeconds = Math.max(1,Math.min(10,seconds)); }
    public synchronized void request(long seed, List<Goal> goals) {
        cancel();
        result = null;
        long version = revision, start = System.nanoTime(), deadline = start + fitSeconds * 1_000_000_000L;
        List<Goal> requested = List.copyOf(goals);
        if (requested.isEmpty()) { result = new Result(seed, true, 0, 0); return; }
        task = WORKER.submit(() -> {
            int attempts = 0;
            try {
                MinecraftWorldContextFactory factory = new MinecraftWorldContextFactory();
                Random random = new Random(seed ^ requested.hashCode());
                while (version == revision && System.nanoTime() < deadline) {
                    if (paused) { LockSupport.parkNanos(25_000_000); continue; }
                    long candidate = attempts == 0 ? seed : random.nextLong();
                    attempts++;
                    var context = factory.create(candidate, false);
                    boolean fits = true;
                    var dimensions = new EnumMap<com.wywf.core.DimensionSpace, com.wywf.search.WorldContext>(com.wywf.core.DimensionSpace.class);
                    dimensions.put(com.wywf.core.DimensionSpace.OVERWORLD, context);
                    for (Goal goal : requested) {
                        var dimension = com.wywf.core.DimensionSpace.of(goal.biome());
                        var source = dimensions.computeIfAbsent(dimension, d -> factory.createDimension(candidate, d));
                        int y = BiomeMapColors.cave(goal.biome) ? -50 : 64;
                        var biome = source.blockBiomeAt(goal.x, y, goal.z);
                        if (biome == null || !biome.unwrapKey().orElseThrow().identifier().toString().equals(goal.biome)) {
                            fits = false; break;
                        }
                    }
                    if (fits && System.nanoTime() <= deadline) {
                        publish(version, new Result(candidate, true, attempts, (System.nanoTime() - start) / 1_000_000));
                        return;
                    }
                    LockSupport.parkNanos(Math.max(0, Math.min(8_000_000, deadline - System.nanoTime())));
                }
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(PreviewAdaptation.class).warn("Example fitting failed", error);
            }
            publish(version, new Result(seed, false, attempts, (System.nanoTime() - start) / 1_000_000));
        });
    }
    private synchronized void publish(long version, Result next) { if (version == revision) result = next; }
    public Result result() { return result; }
    public synchronized boolean searching() { return task != null && result == null && !task.isDone(); }
    public void paused(boolean value) { paused = value; }
    private void cancel() { revision++; if (task != null) task.cancel(false); task = null; }
    @Override public synchronized void close() { cancel(); result = null; }
}
