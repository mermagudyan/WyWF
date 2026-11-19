package com.wywf.verify;

import java.util.function.IntBinaryOperator;

final class ReliefSurfaceHeights {
    private static final int CHUNK = 16, RADIUS = 24, WIDTH = CHUNK + RADIUS * 2;
    private final int[] heights = new int[WIDTH * WIDTH];
    private final int[] lowest = new int[CHUNK * CHUNK];

    ReliefSurfaceHeights(int x, int z, IntBinaryOperator top, IntBinaryOperator surroundingTop) {
        for (int row = 0; row < WIDTH; row++) for (int col = 0; col < WIDTH; col++) {
            int dx = col - RADIUS, dz = row - RADIUS;
            var source = dx >= 0 && dx < CHUNK && dz >= 0 && dz < CHUNK ? top : surroundingTop;
            heights[row * WIDTH + col] = source.applyAsInt(x + dx, z + dz);
        }
        int[] horizontal = new int[WIDTH * CHUNK], queue = new int[WIDTH];
        for (int row = 0; row < WIDTH; row++)
            minima(heights, row * WIDTH, 1, horizontal, row * CHUNK, 1, queue);
        for (int col = 0; col < CHUNK; col++)
            minima(horizontal, col, CHUNK, lowest, col, CHUNK, queue);
    }

    int top(int dx, int dz) {
        return heights[(dz + RADIUS) * WIDTH + dx + RADIUS];
    }

    int lowerTerrain(int dx, int dz) {
        int i = (dz + RADIUS) * WIDTH + dx + RADIUS;
        int adjacent = Math.min(Math.min(heights[i - 1], heights[i + 1]),
                Math.min(heights[i - WIDTH], heights[i + WIDTH]));
        return Math.min(adjacent, lowest[dz * CHUNK + dx]);
    }

    private static void minima(int[] input, int start, int step, int[] output, int first, int stride, int[] queue) {
        int head = 0, tail = 0;
        for (int i = 0; i < WIDTH; i++) {
            if (head < tail && queue[head] < i - RADIUS * 2) head++;
            int height = input[start + i * step];
            // Missing distant chunks do not lower the sampling floor
            if (height != Integer.MIN_VALUE) {
                while (head < tail && input[start + queue[tail - 1] * step] >= height) tail--;
                queue[tail++] = i;
            }
            if (i >= RADIUS * 2)
                output[first + (i - RADIUS * 2) * stride] = head == tail ? Integer.MAX_VALUE
                        : input[start + queue[head] * step];
        }
    }
}
