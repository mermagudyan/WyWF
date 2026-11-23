package com.wywf.core;

import dev.diylib.Diy;
import dev.diylib.tasks.SynchronBondage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public final class MapBackground {
    private static final String MOD_ID = "wywf";

    private MapBackground() {}

    public static CompletableFuture<Void> cpu(Runnable action) {
        if (Diy.isInstalled()) return Diy.synchronBondage().cpu(MOD_ID, () -> {
            action.run();
            return null;
        });
        return CompletableFuture.runAsync(action, Fallback.CPU);
    }

    public static <T> CompletableFuture<T> io(Supplier<T> action) {
        if (Diy.isInstalled()) return Diy.synchronBondage().io(MOD_ID, action);
        return CompletableFuture.supplyAsync(action, Fallback.IO);
    }

    public static int cpuAllowance(int requested) {
        return Diy.isInstalled() ? Diy.synchronBondage().cpuAllowance(requested) : requested;
    }

    public static <T> CompletableFuture<T> cpuResult(Supplier<T> action) {
        if (Diy.isInstalled()) return Diy.synchronBondage().cpu(MOD_ID, action);
        return CompletableFuture.supplyAsync(action);
    }

    public static <T> CompletableFuture<T> raster(int workers, IntFunction<T> action) {
        if (Diy.isInstalled()) {
            int requested = workers == 1 ? 1 : workers + 1;
            return Diy.synchronBondage().cpu(MOD_ID, requested, granted -> action.apply(Math.max(1, granted - 1)));
        }
        return CompletableFuture.supplyAsync(() -> action.apply(workers));
    }

    public static final class Revision {
        private final SynchronBondage.Revision delegate = new SynchronBondage.Revision();

        public long advance() { return delegate.advance(); }
        public boolean isCurrent(long expected) { return delegate.isCurrent(expected); }
        public boolean publish(long expected, Runnable action) { return delegate.publish(expected, action); }
    }

    private static final class Fallback {
        private static final ExecutorService CPU = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().priority(Thread.MIN_PRIORITY).name("WyWF-Coverage-", 0).factory());
        private static final ExecutorService IO = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("WyWF-Saved-Surface-", 0).factory());
    }
}
