package com.wywf.verify;

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

    private EndReturnGateways() {}

    public static boolean mayContain(int chunkX, int chunkZ) {
        return !within((chunkX << 4) + 8, (chunkZ << 4) + 8, OUTER_LIMIT - 12);
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
        if (within(position.getX(), position.getZ(), OUTER_LIMIT)) return Optional.empty();
        return data.read("exit_portal", BlockPos.CODEC)
                .filter(exit -> within(exit.getX(), exit.getZ(), INNER_LIMIT))
                .map(exit -> new MapFeature(ID, position.getX(), position.getY(), position.getZ()));
    }

    private static boolean within(int x, int z, int radius) {
        return (long) x * x + (long) z * z <= (long) radius * radius;
    }
}
