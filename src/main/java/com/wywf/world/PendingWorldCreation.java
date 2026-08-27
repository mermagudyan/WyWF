package com.wywf.world;

import java.util.concurrent.atomic.AtomicLong;

public final class PendingWorldCreation {

    private static final long UNSET = Long.MIN_VALUE;
    private static final AtomicLong state = new AtomicLong(UNSET);

    private PendingWorldCreation() {}

    public static void set(long s) {
        state.set(s);
    }

    public static void clear() {
        state.set(UNSET);
    }

    public static boolean has()    { return state.get() != UNSET; }
    public static long   seed()    { return state.get(); }
}
