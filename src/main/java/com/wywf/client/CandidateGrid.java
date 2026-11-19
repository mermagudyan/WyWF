package com.wywf.client;

record CandidateGrid(int columns, int rows, int cardWidth, int cardHeight) {
    static final int TOP = 60;
    static final int GAP = 8;

    static CandidateGrid forWindow(int width, int height) {
        int available = Math.max(1, height - TOP - 88);
        int columns = Math.max(1, Math.min(3, (width - 16) / 288));
        int rows = Math.max(1, (available + GAP) / (100 + GAP));
        return new CandidateGrid(columns, rows, (width - 24 - (columns - 1) * GAP) / columns,
                Math.min(100, (available - (rows - 1) * GAP) / rows));
    }

    int capacity() {
        return columns * rows;
    }
    int x(int slot) {
        return 12 + slot % columns * (cardWidth + GAP);
    }
    int y(int slot) {
        return TOP + slot / columns * (cardHeight + GAP);
    }
    int pages(int count) {
        return Math.max(1, (count + capacity() - 1) / capacity());
    }
}
