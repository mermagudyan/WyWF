package com.wywf.search;

// This is a test file. These files have no effect on the main gameplay.

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.ParsedQuery;
import com.wywf.core.QueryParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SearchRefactorParityTest {

    private static MinecraftWorldContextFactory factory;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        factory = new MinecraftWorldContextFactory();
    }

    private static int roundDist(long d2) {
        return (int) Math.round(Math.sqrt((double) d2));
    }

    @Test
    void distanceTransformsMatchSqrt() {
        int[] radii = {0, 1, 63, 64, 200, 320, 500, 1000};
        for (int r : radii) {
            int bound = r + 2;
            for (int dx = -bound; dx <= bound; dx++) {
                for (int dz = -bound; dz <= bound; dz++) {
                    long d2 = (long) dx * dx + (long) dz * dz;
                    int d = roundDist(d2);
                    assertEquals(d <= r, d2 <= (long) r * r + r, "le r=" + r + " d2=" + d2);
                    if (r > 0) {
                        assertEquals(d < r, d2 <= (long) r * r - r, "lt r=" + r + " d2=" + d2);
                    }
                }
            }
        }
        int[][] bands = {{0, 800}, {1, 800}, {100, 600}, {300, 600}, {500, 800}};
        for (int[] b : bands) {
            int m = b[0], mx = b[1];
            long loSq = m <= 0 ? 0 : (long) m * m - m + 1;
            long hiSq = (long) mx * mx + mx;
            int bound = mx + 2;
            for (int dx = -bound; dx <= bound; dx += 3) {
                for (int dz = -bound; dz <= bound; dz += 3) {
                    long d2 = (long) dx * dx + (long) dz * dz;
                    int d = roundDist(d2);
                    assertEquals(d >= m && d <= mx, d2 >= loSq && d2 <= hiSq,
                            "band " + m + ".." + mx + " d2=" + d2);
                }
            }
        }
    }

    @Test
    void viableManyCountOverload() {
        assumeTrue(CubiomesBridge.isActive());
        Random rnd = new Random(7);
        for (int n : new int[]{1, 7, 40}) {
            int[] xs = new int[40], zs = new int[40];
            for (int i = 0; i < 40; i++) {
                xs[i] = rnd.nextInt(4096) - 2048;
                zs[i] = rnd.nextInt(4096) - 2048;
            }
            CubiomesBridge.applySeed(123456789L);
            boolean[] full = CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, 123456789L, xs, zs);
            // 7-arg count overload (variantFlags 0 = all variants)
            boolean[] part = CubiomesBridge.viableMany(5, CubiomesBridge.DIM_OVERWORLD, 123456789L, xs, zs, n, 0);
            assertNotNull(full);
            assertNotNull(part);
            assertEquals(n, part.length);
            for (int i = 0; i < n; i++) {
                assertEquals(full[i], part[i], "mismatch at " + i + " n=" + n);
            }
        }
    }

    @Test
    void spiralMatchesBruteForce() throws Exception {
        assumeTrue(CubiomesBridge.isActive());
        Method m = SeedValidator.class.getDeclaredMethod("spiralLandSearch", WorldContext.class);
        m.setAccessible(true);
        int r = 20, step = 2, n = 2 * r / step + 1;
        for (long seed = 1; seed <= 15; seed++) {
            WorldContext ctx = factory.create(seed, false);
            CubiomesBridge.applySeed(seed);
            int[] actual = (int[]) m.invoke(null, ctx);
            int[] expected = bruteSpiral(ctx, r, step, n);
            assertArrayEquals(expected, actual, "spiral mismatch seed=" + seed);
        }
    }

    private static int[] bruteSpiral(WorldContext ctx, int r, int step, int n) {
        int[] grid = new int[n * n];
        int got = CubiomesBridge.sampleBiomeGrid(64, 0, 0, r, step, grid);
        if (got <= 0) return null;
        int sea = ctx.noiseSettings().seaLevel();
        List<int[]> matches = new ArrayList<>();
        for (int i = 0; i < got; i++) {
            int id = grid[i];
            if (id == 1 || id == 4 || id == 129 || id == 177 || id == 5) {
                int gx = (i / n) * step - r, gz = (i % n) * step - r;
                matches.add(new int[]{gx * 16, gz * 16, gx * gx + gz * gz});
            }
        }
        matches.sort(Comparator.comparingInt(a -> a[2]));
        if (!matches.isEmpty()) return new int[]{matches.get(0)[0], matches.get(0)[1]};
        List<int[]> land = new ArrayList<>();
        for (int i = 0; i < got; i++) {
            int id = grid[i];
            if (!isWaterId(id)) {
                int gx = (i / n) * step - r, gz = (i % n) * step - r;
                land.add(new int[]{gx * 16, gz * 16, gx * gx + gz * gz});
            }
        }
        land.sort(Comparator.comparingInt(a -> a[2]));
        for (int i = 0; i < Math.min(3, land.size()); i++) {
            int[] c = land.get(i);
            if (ctx.computeHeight(c[0], c[1]) > sea) return new int[]{c[0], c[1]};
        }
        return null;
    }

    private static boolean isWaterId(int id) {
        switch (id) {
            case 0: case 7: case 10: case 11: case 24:
            case 44: case 45: case 46: case 47: case 48: case 49: case 50:
                return true;
            default:
                return false;
        }
    }

    @Test
    void countRecountConsistent() {
        assumeTrue(CubiomesBridge.isActive());
        KeywordDictionary dict = new KeywordDictionary();
        QueryParser parser = new QueryParser(dict);
        SeedValidator validator = new SeedValidator(
                new VanillaStructureChecker(dict), new VanillaBiomeChecker(), 40, 16);
        checkQuery(validator, parser, "some 2 village", 1, 40);
        checkQuery(validator, parser, "village between 100 to 600", 1, 40);
    }

    private static void checkQuery(SeedValidator validator, QueryParser parser, String q, long from, long to) {
        ParsedQuery query = parser.parse(q);
        assertFalse(query.isEmpty(), "query did not parse: " + q);
        int accepted = 0;
        for (long seed = from; seed <= to; seed++) {
            WorldContext ctx = factory.create(seed, false);
            SeedValidator.Outcome o;
            try {
                o = validator.validate(ctx, query, 0, 0);
            } catch (Throwable t) {
                fail("validate threw for seed " + seed + ": " + t);
                return;
            }
            if (!o.accepted) continue;
            accepted++;
            assertNotNull(o.result);
            assertFalse(o.result.structurePositions.isEmpty(), "accepted with no positions seed=" + seed);
            for (var e : o.result.structurePositions.entrySet()) {
                int[] p = e.getValue();
                long d2 = (long) p[0] * p[0] + (long) p[1] * p[1];
                int d = (int) Math.round(Math.sqrt((double) d2));
                for (ParsedQuery.Term t : query.terms()) {
                    switch (t.modifier) {
                        case SOME:
                            assertTrue(d <= SeedValidator.effectiveSomeBlocks(t.someCount),
                                    "SOME position out of range seed=" + seed + " d=" + d);
                            break;
                        case BETWEEN:
                            assertTrue(d >= t.betweenMin && d <= t.betweenMax,
                                    "BETWEEN position out of band seed=" + seed + " d=" + d);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        System.out.println("[parity] " + q + " accepted " + accepted + "/" + (to - from + 1));
    }
}
