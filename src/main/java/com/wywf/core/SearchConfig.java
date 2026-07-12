package com.wywf.core;

public final class SearchConfig {

    public enum Mode {
        AUTO,
        ECONOMY,
        HIGH,
        MAX
    }

    public static final long UNBOUNDED = Long.MAX_VALUE;

    private Mode mode = Mode.MAX;
    private int manualThreads = 0;
    private int searchRadiusChunks = 40;
    private int biomeCheckRadiusChunks = 16;
    private int biomeSampleStepChunks = 4;
    private long maxSeeds = UNBOUNDED;
    private long timeLimitMs = 0L;

    public SearchConfig() {}

    public static SearchConfig defaults() { return new SearchConfig(); }

    public int resolveThreadCount() {
        if (manualThreads > 0) return manualThreads;

        int cpu = Runtime.getRuntime().availableProcessors();
        return switch (mode) {
            case ECONOMY -> Math.max(1, cpu / 4);
            case HIGH    -> Math.max(1, (int) Math.round(cpu * 0.75));
            case MAX     -> cpu;
            case AUTO    -> Math.max(1, (int) Math.round(cpu * 0.65));
        };
    }

    public Mode mode()                       { return mode; }
    public SearchConfig mode(Mode m)         { this.mode = m; return this; }

    public int manualThreads()               { return manualThreads; }
    public SearchConfig manualThreads(int v) { this.manualThreads = v; return this; }

    public int searchRadiusChunks()               { return searchRadiusChunks; }
    public SearchConfig searchRadiusChunks(int v) { this.searchRadiusChunks = v; return this; }

    public int biomeCheckRadiusChunks()               { return biomeCheckRadiusChunks; }
    public SearchConfig biomeCheckRadiusChunks(int v) { this.biomeCheckRadiusChunks = v; return this; }

    public int biomeSampleStepChunks()               { return Math.max(1, biomeSampleStepChunks); }
    public SearchConfig biomeSampleStepChunks(int v) { this.biomeSampleStepChunks = v; return this; }

    public long maxSeeds()                  { return maxSeeds; }
    public SearchConfig maxSeeds(long v)    { this.maxSeeds = v; return this; }

    public boolean unbounded()              { return maxSeeds >= UNBOUNDED; }

    public long timeLimitMs()               { return timeLimitMs; }
    public SearchConfig timeLimitMs(long v) { this.timeLimitMs = v; return this; }
}
