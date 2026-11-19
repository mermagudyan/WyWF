package com.wywf.client;

import com.wywf.verify.ReliefTile;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class ReliefOpenAir {
    record Exposure(long[] shallow, BitSet deep, int depthStride) {
        void markChanges(Exposure prior, boolean[] dirty, int side) {
            if (depthStride != prior.depthStride) {
                Arrays.fill(dirty, true);
                return;
            }
            int tileSide = side / 16;
            for (int cell = 0; cell < shallow.length; cell++) {
                if (shallow[cell] != prior.shallow[cell]) markColumn(dirty, side, tileSide, cell);
            }
            if (deep.isEmpty() && prior.deep.isEmpty()) return;
            var differences = (BitSet) deep.clone();
            differences.xor(prior.deep);
            int below = depthStride - ReliefTile.DEPTH;
            for (int at = differences.nextSetBit(0); at >= 0;) {
                int cell = at / below;
                markColumn(dirty, side, tileSide, cell);
                at = differences.nextSetBit((cell + 1) * below);
            }
        }

        private static void markColumn(boolean[] dirty, int side, int tileSide, int cell) {
            int x = cell % side, z = cell / side;
            int chunk = (z >> 4) * tileSide + (x >> 4);
            dirty[chunk] = true;
            if ((x & 15) == 0 && x > 0) dirty[chunk - 1] = true;
            if ((x & 15) == 15 && x + 1 < side) dirty[chunk + 1] = true;
            if ((z & 15) == 0 && z > 0) dirty[chunk - tileSide] = true;
            if ((z & 15) == 15 && z + 1 < side) dirty[chunk + tileSide] = true;
        }
    }

    Exposure exposure() {
        return new Exposure(connected, deepConnected, depthStride);
    }

    private final ReliefTile[] tiles;
    private final int side, tileSide, depthStride;
    private final int[] heights;
    private final long[] air;
    private final long[] connected;
    private final BitSet deepConnected = new BitSet();

    ReliefOpenAir(ReliefTile[] tiles, int tileSide, BooleanSupplier cancelled) {
        this.tiles = tiles;
        this.tileSide = tileSide;
        side = tileSide * 16;
        heights = new int[side * side];
        Arrays.fill(heights, Integer.MAX_VALUE);
        air = new long[heights.length];
        connected = new long[heights.length];
        var pending = new IntArrayFIFOQueue();
        int maxDepth = ReliefTile.DEPTH;
        for (int chunk = 0; chunk < tiles.length; chunk++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var tile = tiles[chunk];
            if (tile == null || tile.ground() == null) continue;
            int start = chunk / tileSide * 16 * side + chunk % tileSide * 16;
            for (int col = 0; col < 256; col++) {
                if (tile.colors()[col] == 0) continue;
                int cell = start + (col >> 4) * side + (col & 15);
                heights[cell] = tile.heights()[col];
                if (tile.sampledAir() != null) air[cell] = tile.sampledAir()[col];
                maxDepth = Math.max(maxDepth, tile.depth(col));
            }
        }
        depthStride = maxDepth;
        for (int z = 0; z < side; z++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            for (int x = 0; x < side; x++) {
                int cell = z * side + x;
                if (heights[cell] == Integer.MAX_VALUE) continue;
                int neighbor = Integer.MAX_VALUE;
                if (x > 0) neighbor = heights[cell - 1];
                if (x + 1 < side) neighbor = Math.min(neighbor, heights[cell + 1]);
                if (z > 0) neighbor = Math.min(neighbor, heights[cell - side]);
                if (z + 1 < side) neighbor = Math.min(neighbor, heights[cell + side]);
                if (neighbor >= heights[cell]) continue;
                int drop = heights[cell] - neighbor;
                long exposed = air[cell] & ((1L << Math.min(ReliefTile.DEPTH, drop)) - 1);
                while (exposed != 0) {
                    int offset = Long.numberOfTrailingZeros(exposed);
                    add(pending, cell, offset);
                    exposed &= exposed - 1;
                }
                if (drop > ReliefTile.DEPTH) {
                    var tile = tileAt(x, z);
                    int column = column(x, z);
                    for (int offset = ReliefTile.DEPTH; offset < Math.min(drop, tile.depth(column)); offset++) {
                        if (tile.sampledAir(column, offset)) add(pending, cell, offset);
                    }
                }
            }
        }
        int count = 0;
        while (!pending.isEmpty()) {
            if ((count++ & 4095) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
            int entry = pending.dequeueInt(), cell = entry / depthStride, offset = entry % depthStride;
            int y = heights[cell] - offset, x = cell % side, z = cell / side;
            add(pending, cell, offset - 1);
            add(pending, cell, offset + 1);
            if (x > 0) addAt(pending, cell - 1, y);
            if (x + 1 < side) addAt(pending, cell + 1, y);
            if (z > 0) addAt(pending, cell - side, y);
            if (z + 1 < side) addAt(pending, cell + side, y);
        }
    }

    boolean contains(int x, int y, int z) {
        int cell = z * side + x;
        if (heights[cell] == Integer.MAX_VALUE) return false;
        int offset = heights[cell] - y;
        if (offset < 0 || offset >= depthStride) return false;
        if (offset < ReliefTile.DEPTH) return (connected[cell] & (1L << offset)) != 0;
        return deepConnected.get(deepIndex(cell, offset));
    }

    private void addAt(IntArrayFIFOQueue pending, int cell, int y) {
        if (heights[cell] != Integer.MAX_VALUE) add(pending, cell, heights[cell] - y);
    }

    private void add(IntArrayFIFOQueue pending, int cell, int offset) {
        if (offset < 0 || offset >= depthStride) return;
        if (offset < ReliefTile.DEPTH) {
            long bit = 1L << offset;
            if ((air[cell] & bit) == 0 || (connected[cell] & bit) != 0) return;
            connected[cell] |= bit;
        } else {
            int index = deepIndex(cell, offset);
            if (deepConnected.get(index)) return;
            int x = cell % side, z = cell / side;
            var tile = tileAt(x, z);
            if (tile == null || !tile.sampledAir(column(x, z), offset)) return;
            deepConnected.set(index);
        }
        pending.enqueue(cell * depthStride + offset);
    }

    private int deepIndex(int cell, int offset) {
        return cell * (depthStride - ReliefTile.DEPTH) + offset - ReliefTile.DEPTH;
    }

    private ReliefTile tileAt(int x, int z) {
        return tiles[(z >> 4) * tileSide + (x >> 4)];
    }

    private static int column(int x, int z) {
        return (z & 15) * 16 + (x & 15);
    }
}
