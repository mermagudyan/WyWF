package com.wywtf.core;

/**
 * Конфигурация поиска: режим распараллеливания.
 *
 * Режимы:
 *   AUTO      — 60-70% доступных потоков CPU
 *   ECONOMY   — 1 поток (или 2 на многоядерных)
 *   HIGH      — 70-80% потоков
 *   MAX       — все потоки
 *
 * Радиус поиска (в блоках):
 *   - вокруг спавна проверяются структуры
 *   - вокруг найденной структуры — биомы
 */
public final class SearchConfig {

    public enum Mode {
        AUTO,
        ECONOMY,
        HIGH,
        MAX
    }

    private Mode mode = Mode.AUTO;
    private int manualThreads = 0;            // 0 = авто
    private int searchRadiusChunks = 96;      // 96 * 16 = 1536 блоков радиус
    private int biomeCheckRadiusChunks = 16;  // радиус проверки биомов вокруг структуры
    private long maxSeeds = 50_000_000L;      // лимит, после которого сдаёмся
    private long timeLimitMs = 5 * 60_000L;   // 5 минут

    public SearchConfig() {}

    public static SearchConfig defaults() { return new SearchConfig(); }

    // ---- Расчёт потоков ----------------------------------------------------

    public int resolveThreadCount() {
        if (manualThreads > 0) return manualThreads;

        int cpu = Runtime.getRuntime().availableProcessors();
        return switch (mode) {
            case ECONOMY -> Math.max(1, cpu / 4);
            case HIGH    -> Math.max(1, (int) Math.round(cpu * 0.75));
            case MAX     -> cpu;
            case AUTO    -> Math.max(1, (int) Math.round(cpu * 0.65));  // 60-70%
        };
    }

    // ---- Геттеры/сеттеры ---------------------------------------------------

    public Mode mode()                       { return mode; }
    public SearchConfig mode(Mode m)         { this.mode = m; return this; }

    public int manualThreads()               { return manualThreads; }
    public SearchConfig manualThreads(int v) { this.manualThreads = v; return this; }

    public int searchRadiusChunks()               { return searchRadiusChunks; }
    public SearchConfig searchRadiusChunks(int v) { this.searchRadiusChunks = v; return this; }

    public int biomeCheckRadiusChunks()               { return biomeCheckRadiusChunks; }
    public SearchConfig biomeCheckRadiusChunks(int v) { this.biomeCheckRadiusChunks = v; return this; }

    public long maxSeeds()                  { return maxSeeds; }
    public SearchConfig maxSeeds(long v)    { this.maxSeeds = v; return this; }

    public long timeLimitMs()               { return timeLimitMs; }
    public SearchConfig timeLimitMs(long v) { this.timeLimitMs = v; return this; }
}
