package com.wywf.verify;

public record ReliefTile(int x, int z, int[] colors, int[] heights, int[] voxels, int fog, int[] ground, int caveDepth, String[][] trace, char[] blockIds, ReliefColumns columns, long[] sampledAir, int[] solidUpTo) {
    public ReliefTile(int x, int z, int[] colors, int[] heights, int[] voxels, int fog, int[] ground) {
        this(x, z, colors, heights, voxels, fog, ground, 0);
    }

    public ReliefTile(int x, int z, int[] colors, int[] heights, int[] voxels, int fog, int[] ground, int caveDepth) {
        this(x, z, colors, heights, voxels, fog, ground, caveDepth, null, null, null, null);
    }

    public ReliefTile(int x, int z, int[] colors, int[] heights, int[] voxels, int fog, int[] ground,
                      int caveDepth, String[][] trace, char[] blockIds) {
        this(x, z, colors, heights, voxels, fog, ground, caveDepth, trace, blockIds, null, null);
    }

    public ReliefTile(int x, int z, int[] colors, int[] heights, int[] voxels, int fog, int[] ground,
                      int caveDepth, String[][] trace, char[] blockIds, ReliefColumns columns, long[] sampledAir) {
        this(x, z, colors, heights, voxels, fog, ground, caveDepth, trace, blockIds, columns, sampledAir, null);
    }

    private int stride() { return Math.max(DEPTH, caveDepth); }

    public int depth(int column) {
        if (columns == null) return caveDepth > 0 ? caveDepth : DEPTH;
        return voxels == null ? columns.depth(column) : DEPTH + columns.depth(column);
    }

    public boolean belowSample(int column, int y) {
        return voxels != null && colors[column] != 0 && y <= heights[column] - depth(column);
    }

    public String blockAt(int column, int y) {
        int offset = heights[column] - y;
        if (offset < 0 || offset >= depth(column)) return null;
        int key;
        if (voxels == null) key = columns.id(column, offset);
        else if (offset >= stride()) key = columns.id(column, offset - DEPTH);
        else key = blockIds == null ? 0 : blockIds[column * stride() + offset];
        int id = key - 1;
        var blocks = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var block = id < 0 ? null : blocks.byId(id);
        return block == null ? null : blocks.getKey(block).toString();
    }

    String tracedBlock(int column, int y) {
        int offset = heights[column] - y;
        return trace == null || trace[column] == null || offset < 0 || offset >= trace[column].length
                || trace[column][offset] == null ? "not sampled" : trace[column][offset];
    }

    public static final int DEPTH = 40;
    static final int SAMPLED_AIR = 1;
    public static final int CAVE_SHADOW = 0xF8181821;
    public static final int CAVE_FLOOR = 0xFA000000, COVERED_WATER = 0xF9000000;
    public static final int WATER_FACE = 0xFE000000, ENTRANCE_FACE = 0xFD000000;
    public static final int CLIPPED_FACE = 0xFC000000, PLANT_FACE = 0xFB000000, FACE_TYPE = 0xFF000000;
    public static final int BERRY_FACE = 0xF4000000, SHAPED_BLOCK = 0xF5000000;

    public static boolean plant(int material) {
        return material == PLANT_FACE || material == BERRY_FACE;
    }

    public static boolean shaped(int material) {
        return material == SHAPED_BLOCK || plant(material);
    }


    public int voxel(int column, int y) {
        int offset = heights[column] - y;
        if (colors[column] == 0 || offset < 0 || offset >= depth(column)) return 0;
        if (voxels == null) return columns.color(column, offset);
        if (offset < stride()) return voxels[column * stride() + offset];
        int color = columns.color(column, offset - DEPTH);
        return color == SAMPLED_AIR ? 0 : color;
    }

    public boolean sampledAir(int column, int offset) {
        if (offset < 0 || sampledAir == null) return false;
        if (offset < DEPTH) return (sampledAir[column] & (1L << offset)) != 0;
        return columns != null && columns.color(column, offset - DEPTH) == SAMPLED_AIR;
    }
}
