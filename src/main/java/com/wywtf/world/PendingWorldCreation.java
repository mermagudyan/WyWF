package com.wywtf.world;

/**
 * Хранит сид и координаты спавна, которые нужно применить при создании мира.
 *
 * Это «почтовый ящик» между GUI поиска и миксином CreateWorldScreen:
 *   1. WorldCreator.set(seed, spawn) → сохраняет значения здесь.
 *   2. Mixin WorldGenSettingsComponent при парсинге сида читает {@link #seed()}
 *      и подменяет результат.
 *   3. Mixin ServerLevel после загрузки мира читает {@link #spawnX()}/{@link #spawnZ()}
 *      и сдвигает спавн.
 *
 * Потокобезопасно через volatile. Никакого «consume» — значения могут читаться
 * многократно разными mixin-ами.
 */
public final class PendingWorldCreation {

    private static volatile long seed = 0L;
    private static volatile int  spawnX = 0;
    private static volatile int  spawnZ = 0;
    private static volatile boolean has = false;

    private PendingWorldCreation() {}

    public static void set(long s, int x, int z) {
        seed = s; spawnX = x; spawnZ = z; has = true;
    }

    public static void clear() {
        has = false;
    }

    public static boolean has()    { return has; }
    public static long   seed()    { return seed; }
    public static int    spawnX()  { return spawnX; }
    public static int    spawnZ()  { return spawnZ; }
}
