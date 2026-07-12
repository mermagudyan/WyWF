package com.wywf.world;

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
