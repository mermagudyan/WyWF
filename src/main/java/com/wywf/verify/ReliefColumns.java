package com.wywf.verify;

import it.unimi.dsi.fastutil.ints.IntArrayList;

record ReliefColumns(int[] starts, int[] ends, int[] colors, int[] ids) {
    int depth(int column) {
        return starts[column] == starts[column + 1] ? 0 : ends[starts[column + 1] - 1];
    }

    int color(int column, int offset) {
        int run = run(column, offset);
        return run < 0 ? 0 : colors[run];
    }

    int id(int column, int offset) {
        int run = ids == null ? -1 : run(column, offset);
        return run < 0 ? 0 : ids[run];
    }

    private int run(int column, int offset) {
        int first = starts[column], end = starts[column + 1];
        while (first < end) {
            int middle = (first + end) >>> 1;
            if (ends[middle] <= offset) first = middle + 1;
            else end = middle;
        }
        return first == starts[column + 1] ? -1 : first;
    }

    static final class Builder {
        private final int[] starts = new int[257];
        private final IntArrayList ends = new IntArrayList();
        private final IntArrayList colors = new IntArrayList();
        private final IntArrayList ids;
        private int start;

        Builder(boolean debug) { ids = debug ? new IntArrayList() : null; }

        void add(int offset, int color, int id) {
            int last = colors.size() - 1;
            if (last >= start && colors.getInt(last) == color && (ids == null || ids.getInt(last) == id)) {
                ends.set(last, offset + 1);
            } else {
                ends.add(offset + 1);
                colors.add(color);
                if (ids != null) ids.add(id);
            }
        }

        void endColumn(int column, boolean trimAir) {
            int last = colors.size() - 1;
            if (trimAir && last >= start && colors.getInt(last) == 0 && (ids == null || ids.getInt(last) == 0)) {
                colors.removeInt(last);
                ends.removeInt(last);
                if (ids != null) ids.removeInt(last);
            }
            start = colors.size();
            starts[column + 1] = start;
        }

        ReliefColumns build() {
            return new ReliefColumns(starts, ends.toIntArray(), colors.toIntArray(), ids == null ? null : ids.toIntArray());
        }
    }
}
