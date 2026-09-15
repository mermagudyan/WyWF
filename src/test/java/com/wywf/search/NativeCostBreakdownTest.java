package com.wywf.search;

import org.junit.jupiter.api.Test;

class NativeCostBreakdownTest {

    private static final int N = 400;
    private static final int WARM = 50;

    @Test
    void printBreakdown() {
        System.out.println("[bench] native active=" + CubiomesBridge.isActive());
        if (!CubiomesBridge.isActive()) return;

        for (long s = 1; s <= WARM; s++) CubiomesBridge.applySeed(s);

        // Fake but realistic candidate spread for viableMany (40 pts).
        int[] xs = new int[40], zs = new int[40];
        for (int i = 0; i < 40; i++) { xs[i] = (i - 20) * 128; zs[i] = (i % 7 - 3) * 128; }

        long t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) CubiomesBridge.applySeed(s);
        long setup = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            CubiomesBridge.biomeExists(1, 64, 0, 0, 16, 4, 0, 0, 16 * 16);
            CubiomesBridge.biomeNearest(2, 64, 0, 0, 16, 4, 0, 0);
        }
        long withBiome = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, s, xs, zs);
        }
        long withStruct = System.nanoTime() - t0;

        t0 = System.nanoTime();
        int agree = 0, total = 0;
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            boolean[] all = CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, s, xs, zs);
            boolean[] one = CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, s, xs, zs, 1);
            for (int i = 0; i < all.length; i++) {
                total++;
                // flags=1 verdicts must be a subset of flags=0 (never new accepts)
                if (!one[i] || all[i]) agree++;
            }
        }
        long withFlags = System.nanoTime() - t0;
        System.out.printf("[bench] village flags: subset-agreement=%d/%d%n", agree, total);

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, s, xs, zs, 1);
        }
        long flagsOnly = System.nanoTime() - t0;
        System.out.printf("[bench] viableMany40 flags0=%.1f flags1=%.1f us/seed%n",
                withStruct / (double) N / 1000.0, flagsOnly / (double) N / 1000.0);

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            CubiomesBridge.viableMany(10, CubiomesBridge.DIM_OVERWORLD, s, xs, zs);
        }
        System.out.printf("[bench] viableMany40 outpost=%.1f us/seed%n",
                (System.nanoTime() - t0) / (double) N / 1000.0);

        t0 = System.nanoTime();
        for (long s = 1; s <= N; s++) {
            CubiomesBridge.applySeed(s);
            CubiomesBridge.biomeExists(1, 64, 0, 0, 16, 4, 0, 0, 16 * 16);
            CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, s, xs, zs);
        }
        long full = System.nanoTime() - t0;

        double per = (double) N;
        System.out.printf("[bench] seeds=%d setup=%.1f us/seed biomeChecks=%.1f structChecks=%.1f full=%.1f us/seed%n",
                N, setup / per / 1000.0, (withBiome - setup) / per / 1000.0,
                (withStruct - setup) / per / 1000.0, full / per / 1000.0);
        System.out.printf("[bench] setup share of full=%.0f%%%n", 100.0 * setup / full);
    }
}
