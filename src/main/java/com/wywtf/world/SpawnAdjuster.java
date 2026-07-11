package com.wywtf.world;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Сдвигает спавн мира к найденной точке.
 *
 * Запланированное смещение забирается хуком в ServerLevel после загрузки мира.
 *
 * В 26.x реальный путь:
 *   - Mixin в ServerLevel#setDefaultSpawnPos
 *   - или ServerLevel#initData через обработчик LevelEventListener
 */
public final class SpawnAdjuster {

    private static final AtomicReference<int[]> pending = new AtomicReference<>(null);

    public static void scheduleAdjustment(int x, int z) {
        pending.set(new int[]{x, z});
    }

    public static boolean consume(java.util.function.IntBiConsumer consumer) {
        int[] p = pending.getAndSet(null);
        if (p == null) return false;
        consumer.apply(p[0], p[1]);
        return true;
    }

    @FunctionalInterface
    public interface IntBiConsumer {
        void apply(int x, int z);
    }
}
