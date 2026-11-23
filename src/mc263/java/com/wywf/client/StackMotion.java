package com.wywf.client;

import dev.diylib.animation.RetargetableValue;

final class StackMotion {
    private final RetargetableValue motion;

    StackMotion(double initial) { motion = new RetargetableValue(initial, 190_000_000L); }

    void target(double value, long now) { motion.target(value, now); }

    double value(long now) { return motion.value(now); }
}
