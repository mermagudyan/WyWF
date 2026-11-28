package com.wywf.verify;

import com.wywf.map.MapViewport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class EndReturnGateways {
    public static final String ID = "wywf:end_return_gateway";
    private static final int INNER_LIMIT = 256;
    private static final int OUTER_LIMIT = 512;

    public static final int MAX_SCANNED_PAGES = 1536;

    private EndReturnGateways() {}

    public static SurfaceWindow scanWindow(MapViewport view) {
        return SurfaceWindow.around(view, MAX_SCANNED_PAGES * 16);
    }

    public static double pageDistance(MapViewport view, int x, int z) {
        double pageX = x + 32.0, pageZ = z + 32.0;
        double distance = Math.hypot(pageX - view.x(), pageZ - view.z());
        // prioritize the outer islands when the map is centered on the main island
        if (Math.hypot(view.x(), view.z()) < OUTER_LIMIT)
            distance += 2 * Math.max(0, 1024 - Math.hypot(pageX, pageZ));
        return distance;
    }

    public static boolean mayContain(int chunkX, int chunkZ) {
        return !within((chunkX << 4) + 8, (chunkZ << 4) + 8, OUTER_LIMIT - 12);
    }

    public static boolean mayContainPage(int x, int z) {
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++)
            if (mayContain((x >> 4) + dx, (z >> 4) + dz)) return true;
        return false;
    }

    public static List<MapFeature> inChunk(LevelChunk chunk) {
        if (!mayContain(chunk.getPos().x(), chunk.getPos().z())) return List.of();
        var found = new ArrayList<MapFeature>();
        for (var position : chunk.getBlockEntitiesPos()) {
            if (chunk.getBlockEntity(position) instanceof TheEndGatewayBlockEntity gateway) {
                fromGateway(position, gateway.saveWithoutMetadata(chunk.getLevel().registryAccess())).ifPresent(found::add);
            }
        }
        return List.copyOf(found);
    }

    public static Optional<MapFeature> fromGateway(BlockPos position, CompoundTag data) {
        // natural return gateways use an exact destination, dragon-linked ones do not
        if (!data.getBooleanOr("ExactTeleport", false) || within(position.getX(), position.getZ(), OUTER_LIMIT))
            return Optional.empty();
        return data.read("exit_portal", BlockPos.CODEC)
                .filter(exit -> within(exit.getX(), exit.getZ(), INNER_LIMIT))
                .map(exit -> new MapFeature(ID, position.getX(), position.getY(), position.getZ()));
    }

    private static boolean within(int x, int z, int radius) {
        return (long) x * x + (long) z * z <= (long) radius * radius;
    }
}
