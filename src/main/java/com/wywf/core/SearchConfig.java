package com.wywf.core;

public final class SearchConfig {

    public enum Mode {
        AUTO,
        ECONOMY,
        HIGH,
        MAX
    }

    public enum SearchCenter {
        ORIGIN,
        SPAWN,
        BOTH
    }

    public static final long UNBOUNDED = Long.MAX_VALUE;

    public static final int MIN_TIME_LIMIT_MINUTES = 5;
    public static final int DEFAULT_TIME_LIMIT_MINUTES = 30;
    public static final long MIN_MAX_SEEDS = 1_000_000;
    public static final long DEFAULT_MAX_SEEDS = 10_000_000;
    public static final long ABSOLUTE_MAX_SEEDS = 100_000_000_000L;

    private Mode mode = Mode.MAX;
    private int manualThreads = 0;
    private int searchRadiusChunks = 40;
    private int biomeCheckRadiusChunks = 16;
    private int biomeSampleStepChunks = 4;
    private int timeLimitMinutes = DEFAULT_TIME_LIMIT_MINUTES;
    private long maxSeedsToCheck = DEFAULT_MAX_SEEDS;
    private boolean infiniteSeeds = false;
    private int candidatesToCollect = 8;
    private int minCandidates = 3;
    private int candidateRampDownSeconds = 10;
    private boolean randomizeStart = true;
    private boolean stopAtFirstCandidate = false;
    private boolean sortCandidatesByDistance = false;
    private KeywordDictionary.Lang queryLanguage = KeywordDictionary.Lang.EN;
    private SearchCenter searchCenter = SearchCenter.SPAWN;

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

    /** Time limit in minutes. Minimum 5, default 30. */
    public int timeLimitMinutes()                     { return timeLimitMinutes; }
    public SearchConfig timeLimitMinutes(int v)       { this.timeLimitMinutes = Math.max(MIN_TIME_LIMIT_MINUTES, v); return this; }

    /** Time limit converted to milliseconds. */
    public long timeLimitMs()                         { return (long) timeLimitMinutes * 60_000L; }

    /** Max seeds to check. Minimum 1M, default 10M. */
    public long maxSeedsToCheck()                     { return infiniteSeeds ? UNBOUNDED : maxSeedsToCheck; }
    public SearchConfig maxSeedsToCheck(long v)       { this.maxSeedsToCheck = Math.max(MIN_MAX_SEEDS, Math.min(ABSOLUTE_MAX_SEEDS, v)); return this; }

    /** Raw max seeds value (ignoring infinite flag). */
    public long rawMaxSeedsToCheck()                  { return maxSeedsToCheck; }

    /** When true, seed limit is ignored — search runs until time limit or candidates found. */
    public boolean infiniteSeeds()                     { return infiniteSeeds; }
    public SearchConfig infiniteSeeds(boolean v)       { this.infiniteSeeds = v; return this; }

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

    /** When true the final candidate is chosen by distance to the nearest structure
     *  (closest first), rather than randomly. Defaults to false. */
    public boolean sortCandidatesByDistance()                  { return sortCandidatesByDistance; }
    public SearchConfig sortCandidatesByDistance(boolean v)    { this.sortCandidatesByDistance = v; return this; }

    /** Which synonym language the query parser uses. AUTO merges EN + RU. */
    public KeywordDictionary.Lang queryLanguage()                 { return queryLanguage; }
    public SearchConfig queryLanguage(KeywordDictionary.Lang v)   { this.queryLanguage = (v == null) ? KeywordDictionary.Lang.AUTO : v; return this; }

    /** Where to center structure/biome checks: origin (0,0), approximate spawn, or both. */
    public SearchCenter searchCenter()                 { return searchCenter; }
    public SearchConfig searchCenter(SearchCenter v)   { this.searchCenter = (v == null) ? SearchCenter.ORIGIN : v; return this; }
}
