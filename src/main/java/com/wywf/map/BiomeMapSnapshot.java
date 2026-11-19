package com.wywf.map;

public record BiomeMapSnapshot(int x, int z, int step, int width, int height, Cell[] cells, boolean complete, int y) {
    public record Overview(int color, float temperature, int upperColor, float upperTemperature, java.util.List<Integer> detail, java.util.List<Cell> samples) {
        public Overview(int color, float temperature, int upperColor, float upperTemperature, java.util.List<Integer> detail) {
            this(color, temperature, upperColor, upperTemperature, detail, java.util.List.of());
        }
        public Overview(int color, float temperature, int upperColor, float upperTemperature) {
            this(color, temperature, upperColor, upperTemperature, java.util.List.of());
        }
    }
    public record Cell(String id, float temperature, String surfaceId, float surfaceTemperature, Overview overview, int height, float climate) {
        public Cell(String id, float t, String upper, float upperT, Overview overview, int height) {
            this(id, t, upper, upperT, overview, height, 0);
        }
        public Cell(String id, float temperature, String surfaceId, float surfaceTemperature, Overview overview) {
            this(id, temperature, surfaceId, surfaceTemperature, overview, Integer.MIN_VALUE);
        }
        public Cell(String id, float temperature) { this(id, temperature, id, temperature, null); }
        public Cell(String id, float temperature, String surfaceId, float surfaceTemperature) {
            this(id, temperature, surfaceId, surfaceTemperature, null);
        }
    }
    public Cell at(int worldX, int worldZ) {
        int cx = Math.floorDiv(worldX - x, step), cz = Math.floorDiv(worldZ - z, step);
        return cx < 0 || cz < 0 || cx >= width || cz >= height ? null : cells[cz * width + cx];
    }
}
