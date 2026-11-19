package com.wywf.core;

public final class SearchSession {
    public enum Phase { NEW, RUNNING, REVIEW, CANCELLED }
    private volatile Phase phase = Phase.NEW;

    public boolean start() {
        if (phase != Phase.NEW) return false;
        phase = Phase.RUNNING;
        return true;
    }

    public boolean finish() {
        if (phase != Phase.RUNNING) return false;
        phase = Phase.REVIEW;
        return true;
    }

    public boolean cancel() {
        if (phase != Phase.RUNNING) return false;
        phase = Phase.CANCELLED;
        return true;
    }

    public Phase phase() { return phase; }
}
