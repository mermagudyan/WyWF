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
    private int candidatesToCollect = 8;
    private int minCandidates = 3;
    private int candidateRampDownSeconds = 10;
    private boolean randomizeStart = true;
    private boolean stopAtFirstCandidate = false;

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

    public int candidatesToCollect()                 { return candidatesToCollect; }
    public SearchConfig candidatesToCollect(int v)   { this.candidatesToCollect = Math.max(1, v); return this; }

    public int minCandidates()                       { return minCandidates; }
    public SearchConfig minCandidates(int v)         { this.minCandidates = Math.max(1, v); return this; }

    public int candidateRampDownSeconds()                   { return candidateRampDownSeconds; }
    public SearchConfig candidateRampDownSeconds(int v)     { this.candidateRampDownSeconds = Math.max(0, v); return this; }

    /**
     * How many candidates are needed to stop the search, given how long it has run.
     * <ul>
     *   <li>before {@link #candidateRampDownSeconds()} — the full {@link #candidatesToCollect()};</li>
     *   <li>after — ramps down to {@link #minCandidates()} (3), so a rare/complex query still
     *       produces a result soon without waiting to accumulate the full set.</li>
     * </ul>
     */
    public int effectiveCandidateTarget(long elapsedMs) {
        if (stopAtFirstCandidate) return 1;
        long rampMs = (long) candidateRampDownSeconds * 1000L;
        if (elapsedMs < rampMs) return candidatesToCollect;
        return Math.max(1, Math.min(candidatesToCollect, minCandidates));
    }

    public boolean randomizeStart()                 { return randomizeStart; }
    public SearchConfig randomizeStart(boolean v)    { this.randomizeStart = v; return this; }

    /** When true the search stops as soon as the first matching seed is found
     *  (candidate target = 1), giving the fastest possible result at the cost of
     *  variety. Defaults to false (the ramp-down to {@link #minCandidates()} applies). */
    public boolean stopAtFirstCandidate()                  { return stopAtFirstCandidate; }
    public SearchConfig stopAtFirstCandidate(boolean v)    { this.stopAtFirstCandidate = v; return this; }
}
