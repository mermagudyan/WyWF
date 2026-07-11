package com.wywtf.core;

/**
 * Результат поиска одного сида.
 *
 * Immutable. Передаётся из рабочих потоков в главный поток без блокировок.
 */
public final class SearchResult {

    public final long seed;
    public final int  spawnX;
    public final int  spawnZ;
    public final String matchedDescription;     // человеко-читаемое описание, что нашли

    public SearchResult(long seed, int spawnX, int spawnZ, String matchedDescription) {
        this.seed = seed;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        this.matchedDescription = matchedDescription;
    }

    @Override public String toString() {
        return "SearchResult{seed=" + seed +
                ", spawn=(" + spawnX + "," + spawnZ + ")" +
                ", desc='" + matchedDescription + "'}";
    }
}
