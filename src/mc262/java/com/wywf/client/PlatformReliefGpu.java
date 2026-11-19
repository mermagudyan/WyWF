package com.wywf.client;

final class PlatformReliefGpu {
    static void register() {}
    static boolean available() {
        return false;
    }
    static ReliefGpu create() {
        return null;
    }
}
