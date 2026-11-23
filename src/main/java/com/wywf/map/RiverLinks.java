package com.wywf.map;

import java.util.List;
import java.util.function.IntBinaryOperator;

final class RiverLinks {
    static final int SIDE = 4;
    static final int RIVER = BiomePalette.color("minecraft:river");
    static final int FROZEN_RIVER = BiomePalette.color("minecraft:frozen_river");
    private static final int VERTICAL = 4;

    static boolean supports(int step) {
        return step >= 32 && step <= 128;
    }

    static long find(List<BiomeMapSnapshot.Cell> samples, List<Integer> detail, int worldX, int worldZ,
                     int step, IntBinaryOperator riverAt) {
        int rivers = 0;
        for (int i = 0; i < samples.size(); i++) {
            int type = switch (samples.get(i).id()) {
                case "minecraft:river" -> 1;
                case "minecraft:frozen_river" -> 2;
                default -> 0;
            };
            rivers |= type << (i * 2);
        }
        if (rivers == 0) return 0;

        int span = step / (SIDE * 4);
        long links = 0;
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
            int i = z * SIDE + x;
            if (WaterMap.wet(detail.get(i))) continue;
            int qx = (worldX >> 2) + x * span + span / 2;
            int qz = (worldZ >> 2) + z * span + span / 2;
            int horizontal = anchors(rivers, i, x, 1, qx, qz, span, false, riverAt);
            int vertical = anchors(rivers, i, z, SIDE, qx, qz, span, true, riverAt);
            int link = 0;
            if (horizontal != 0 && connected(qx, qz, span, false, horizontal, riverAt)) {
                link = horizontal;
            } else if (vertical != 0 && connected(qx, qz, span, true, vertical, riverAt)) {
                link = vertical | VERTICAL;
            }
            links |= (long) link << (i * 4);
        }
        return links;
    }

    private static int anchors(int rivers, int i, int position, int stride, int qx, int qz, int span,
                               boolean vertical, IntBinaryOperator riverAt) {
        int before = position == 0 ? 0 : (rivers >>> ((i - stride) * 2)) & 3;
        int after = position == SIDE - 1 ? 0 : (rivers >>> ((i + stride) * 2)) & 3;
        if (before == 0 && after == 0) return 0;
        if (position == 0) before = type(riverAt.applyAsInt(qx - (vertical ? 0 : span), qz - (vertical ? span : 0)));
        if (position == SIDE - 1) after = type(riverAt.applyAsInt(qx + (vertical ? 0 : span), qz + (vertical ? span : 0)));
        return before == after ? before : 0;
    }

    // trace matching quart biomes between both anchors, in a strip as wide as the missing pixel
    private static boolean connected(int qx, int qz, int span, boolean vertical, int type, IntBinaryOperator riverAt) {
        int length = 2 * span + 1;
        int start = span / 2 * length;
        int finish = start + length - 1;
        int color = color(type);
        if (riverAt.applyAsInt(qx - (vertical ? 0 : span), qz - (vertical ? span : 0)) != color) return false;
        int[] queue = new int[length * span];
        boolean[] seen = new boolean[queue.length];
        queue[0] = start;
        seen[start] = true;
        int tail = 1;
        for (int head = 0; head < tail; head++) {
            int i = queue[head], along = i % length, across = i / length;
            for (int direction = 0; direction < 4; direction++) {
                int nextAlong = along + switch (direction) { case 0 -> -1; case 1 -> 1; default -> 0; };
                int nextAcross = across + switch (direction) { case 2 -> -1; case 3 -> 1; default -> 0; };
                if (nextAlong < 0 || nextAlong >= length || nextAcross < 0 || nextAcross >= span) continue;
                int index = nextAcross * length + nextAlong;
                if (seen[index]) continue;
                seen[index] = true;
                int dx = nextAlong - span, dz = nextAcross - span / 2;
                if (riverAt.applyAsInt(qx + (vertical ? dz : dx), qz + (vertical ? dx : dz)) != color) continue;
                if (index == finish) return true;
                queue[tail++] = index;
            }
        }
        return false;
    }

    static void paint(BiomeMapSnapshot map, int scale, int[] pixels) {
        if (scale != SIDE || map.y() < 64 || !supports(map.step())) return;
        int width = map.width() * SIDE, height = map.height() * SIDE;
        int[] original = pixels;
        for (int z = 0; z < map.height(); z++) for (int x = 0; x < map.width(); x++) {
            var cell = map.cells()[z * map.width() + x];
            if (cell == null || cell.overview() == null) continue;
            long links = cell.overview().riverLinks();
            while (links != 0) {
                int i = Long.numberOfTrailingZeros(links) / 4;
                int link = (int) (links >>> (i * 4)) & 7;
                links &= ~(15L << (i * 4));
                int px = x * SIDE + i % SIDE, pz = z * SIDE + i / SIDE;
                boolean vertical = (link & VERTICAL) != 0;
                int dx = vertical ? 0 : 1, dz = vertical ? 1 : 0;
                if (px < dx || pz < dz || px + dx >= width || pz + dz >= height) continue;
                int color = color(link & 3);
                int index = pz * width + px;
                int offset = vertical ? width : 1;
                if (original[index - offset] != color || original[index + offset] != color) continue;
                if (original == pixels) original = pixels.clone();
                pixels[index] = color;
            }
        }
    }

    private static int type(int color) {
        if (color == RIVER) return 1;
        return color == FROZEN_RIVER ? 2 : 0;
    }

    private static int color(int type) {
        return type == 1 ? RIVER : FROZEN_RIVER;
    }

    private RiverLinks() {}
}
