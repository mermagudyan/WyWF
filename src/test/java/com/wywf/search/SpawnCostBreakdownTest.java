package com.wywf.search;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpawnCostBreakdownTest {

    private static MinecraftWorldContextFactory factory;
    private static final int N = 100;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        factory = new MinecraftWorldContextFactory();
    }

    @Test
    void printBreakdown() {
        System.out.println("[spawnbench] native active=" + CubiomesBridge.isActive());
        for (long s = 1; s <= 10; s++) { factory.create(s, false); }

        long t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) factory.create(s, false);
        long create = System.nanoTime() - t0;

        t0 = System.nanoTime();
        long estWorst = 0;
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            long a = System.nanoTime();
            CubiomesBridge.estimateSpawn();
            long dt = System.nanoTime() - a;
            if (dt > estWorst) estWorst = dt;
        }
        long estSpawn = System.nanoTime() - t0;
        System.out.printf("[spawnbench] estSpawn worst=%.1f ms%n", estWorst / 1e6);

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            CubiomesBridge.getSpawn();
        }
        long nativeSpawn = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            WorldContext ctx = factory.create(s, false);
            ctx.computeHeight(0, 0);
        }
        long height = System.nanoTime() - t0;

        t0 = System.nanoTime();
        SeedValidator.resetSpawnStats();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            WorldContext ctx = factory.create(s, false);
            SeedValidator.findApproxSpawnPos(ctx, false);
        }
        long full = System.nanoTime() - t0;
        System.out.println("[spawnbench] paths: " + SeedValidator.spawnStats());

        double per = (double) N;
        System.out.printf("[spawnbench] seeds=%d create=%.1f height1=%.1f estSpawn=%.1f nativeSpawn=%.1f fullSpawn=%.1f ms/seed%n",
                N, create / per / 1e6, (height - create) / per / 1e6, estSpawn / per / 1e6,
                nativeSpawn / per / 1e6, full / per / 1e6);
    }
}
