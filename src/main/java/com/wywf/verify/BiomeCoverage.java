package com.wywf.verify;

import com.wywf.core.*;
import java.util.function.IntPredicate;

final class BiomeCoverage {
    enum Verdict { PASS, FAIL, PARTIAL }
    static Verdict check(SurfaceSnapshot s, ParsedQuery.Term term, int cx, int cz, int radius) {
        if (s == null || !s.complete() || com.wywf.map.BiomeMapColors.cave(term.canonical)
                || term.modifier == Modifier.SOME || term.modifier == Modifier.ONLY) return Verdict.PARTIAL;
        double nearest = Double.POSITIVE_INFINITY;
        for (int z = 0; z < s.size(); z++) for (int x = 0; x < s.size(); x++) {
            if (term.canonical.equals(s.biomes()[z * s.size() + x]))
                nearest = Math.min(nearest, Math.hypot((double) s.x() + x - cx, (double) s.z() + z - cz));
        }
        boolean covered = s.contains(cx - radius, cz - radius) && s.contains(cx + radius, cz + radius);
        int min = term.modifier == Modifier.FAR ? 500 : term.modifier == Modifier.BETWEEN ? term.betweenMin : 0;
        if (term.modifier == Modifier.NEVER)
            return nearest <= radius ? Verdict.FAIL : covered ? Verdict.PASS : Verdict.PARTIAL;
        if (nearest < min) return Verdict.FAIL;
        boolean innerCovered = min == 0 || s.contains(cx - min, cz - min) && s.contains(cx + min, cz + min);
        if (nearest <= radius && innerCovered) return Verdict.PASS;
        return covered ? Verdict.FAIL : Verdict.PARTIAL;
    }
    static int countRegions(int side, IntPredicate matches, int required, Runnable checkpoint) {
        int area = side * side;
        boolean[] cells = new boolean[area];
        int[] queue = new int[area];
        int regions = 0;
        for (int index = 0; index < area; index++) {
            if (index % side == 0) checkpoint.run();
            if (!visit(index, cells, matches)) continue;
            if (++regions >= required) return regions;
            int tail = 1;
            queue[0] = index;
            for (int head = 0; head < tail; head++) {
                if ((head & 1023) == 0) checkpoint.run();
                int cell = queue[head];
                int column = cell % side;
                if (column > 0 && visit(cell - 1, cells, matches)) queue[tail++] = cell - 1;
                if (column < side - 1 && visit(cell + 1, cells, matches)) queue[tail++] = cell + 1;
                if (cell >= side && visit(cell - side, cells, matches)) queue[tail++] = cell - side;
                if (cell + side < area && visit(cell + side, cells, matches)) queue[tail++] = cell + side;
            }
        }
        return regions;
    }

    private static boolean visit(int cell, boolean[] visited, IntPredicate matches) {
        if (visited[cell]) return false;
        visited[cell] = true;
        return matches.test(cell);
    }

    private BiomeCoverage() {}
}
