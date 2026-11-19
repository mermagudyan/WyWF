package com.wywf.verify;

import com.wywf.WYWFClient;
import com.wywf.core.WyWFDebug;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

public final class ReliefDiagnostics {
    private record Target(int x, int z, int radius) {
        boolean contains(int wx, int wz) {
            return Math.abs((long)wx - x) <= radius && Math.abs((long)wz - z) <= radius;
        }
    }

    private static final Target TARGET = WyWFDebug.ENABLED ? readTarget() : null;

    private ReliefDiagnostics() {}

    private static Target readTarget() {
        String property = System.getProperty("wywf.relief.trace", "");
        if (property.isBlank()) return null;
        try {
            String[] values = property.split(",");
            if (values.length < 2 || values.length > 3) throw new IllegalArgumentException();
            int radius = values.length == 3 ? Math.clamp(Integer.parseInt(values[2].trim()), 0, 4) : 0;
            return new Target(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim()), radius);
        } catch (IllegalArgumentException error) {
            WYWFClient.LOGGER.warn("[Relief] Expected -Dwywf.relief.trace=x,z[,radius]");
            return null;
        }
    }

    public static String[][] traceTile(int x, int z) {
        if (TARGET == null || (long)x > (long)TARGET.x + TARGET.radius || (long)x + 15 < (long)TARGET.x - TARGET.radius
                || (long)z > (long)TARGET.z + TARGET.radius || (long)z + 15 < (long)TARGET.z - TARGET.radius) return null;
        return new String[256][];
    }

    static boolean traces(int x, int z) { return TARGET != null && TARGET.contains(x, z); }

    static void sample(String[] column, int offset, int x, int y, int z, BlockState state, String reason) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (column != null) column[offset] = id;
        if (reason != null && !state.isAir())
            WYWFClient.LOGGER.info("[Relief sample] xyz=({},{},{}) block={} reason={}", x, y, z, id, reason);
    }

    public static void face(ReliefTile tile, int column, int y, String face, String reason,
                            ReliefTile neighbor, int adjacentColumn) {
        if (tile.trace() == null || tile.trace()[column] == null) return;
        int x = tile.x() + (column & 15), z = tile.z() + (column >> 4);
        String block = tile.tracedBlock(column, y);
        String adjacent = neighbor == null ? "unavailable" : neighbor.tracedBlock(adjacentColumn, y);
        WYWFClient.LOGGER.info("[Relief mesh] xyz=({},{},{}) block={} face={} reason={} neighbor={}",
                x, y, z, block, face, reason, adjacent);
    }
}
