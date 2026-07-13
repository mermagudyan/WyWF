package com.wywf.world;

public final class PendingWorldCreation {

    private static volatile long seed = 0L;
    private static volatile boolean has = false;

    private PendingWorldCreation() {}

    public static void set(long s) {
        seed = s; has = true;
    }

    public static void clear() {
        has = false;
    }

    public static boolean has()    { return has; }
    public static long   seed()    { return seed; }
}
