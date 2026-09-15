package com.wywf.core;

// Master switch for noisy per-search logging, on only with -Dwywf.debug=true
public final class WyWFDebug {

    public static final boolean ENABLED = Boolean.getBoolean("wywf.debug");

    private WyWFDebug() {}
}
