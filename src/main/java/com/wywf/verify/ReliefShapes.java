package com.wywf.verify;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReliefShapes {
    public record Geometry(int color, List<AABB> boxes) {}
    private record Swatch(BlockState state, int color) {}

    private static final Geometry CUBE = new Geometry(0, List.of());
    private static final Geometry EMPTY = new Geometry(0, List.of());
    private static final ConcurrentHashMap<Swatch, Integer> PACKED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Geometry> GEOMETRY = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private ReliefShapes() {}

    static int pack(BlockState state, int color) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) return 0;
        if (state.isSolidRender()) return color;
        return PACKED.computeIfAbsent(new Swatch(state, color), ReliefShapes::pack);
    }

    private static int pack(Swatch swatch) {
        var geometry = geometry(swatch.state, swatch.color);
        if (geometry == CUBE) return swatch.color;
        if (geometry == EMPTY) return 0;
        int id = NEXT_ID.incrementAndGet();
        GEOMETRY.put(id, geometry);
        boolean eye = swatch.state.is(Blocks.END_PORTAL_FRAME) && swatch.state.getValue(EndPortalFrameBlock.HAS_EYE);
        return (eye ? ReliefTile.END_PORTAL_EYE : ReliefTile.SHAPED_BLOCK) | id;
    }

    public static Geometry get(int value) {
        return GEOMETRY.get(value & 0xFFFFFF);
    }

    static int rgb(int value) {
        int material = value & ReliefTile.FACE_TYPE;
        return material == ReliefTile.SHAPED_BLOCK || material == ReliefTile.END_PORTAL_EYE ? get(value).color : value;
    }

    private static Geometry geometry(BlockState state, int color) {
        var shape = switch (state.getBlock()) {
            case FenceBlock ignored -> fence(state);
            case FenceGateBlock ignored -> gate(state);
            default -> state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        };
        if (shape.isEmpty()) return EMPTY;
        if (Block.isShapeFullBlock(shape)) return CUBE;
        var boxes = shape.toAabbs().stream()
                .filter(box -> box.maxX > 0 && box.maxY > 0 && box.maxZ > 0
                        && box.minX < 1 && box.minY < 1 && box.minZ < 1)
                .map(box -> new AABB(Math.max(0, box.minX), Math.max(0, box.minY), Math.max(0, box.minZ),
                        Math.min(1, box.maxX), Math.min(1, box.maxY), Math.min(1, box.maxZ)))
                .toList();
        return boxes.isEmpty() ? EMPTY : new Geometry(color == 0 ? 0xFF929CA3 : color, boxes);
    }

    // The visual rails leave gaps above and below their collision outline
    private static VoxelShape fence(BlockState state) {
        VoxelShape shape = Block.box(6, 0, 6, 10, 16, 10);
        if (state.getValue(BlockStateProperties.NORTH)) shape = rails(shape, 7, 0, 9, 6);
        if (state.getValue(BlockStateProperties.EAST)) shape = rails(shape, 10, 7, 16, 9);
        if (state.getValue(BlockStateProperties.SOUTH)) shape = rails(shape, 7, 10, 9, 16);
        if (state.getValue(BlockStateProperties.WEST)) shape = rails(shape, 0, 7, 6, 9);
        return shape;
    }

    private static VoxelShape rails(VoxelShape shape, int x0, int z0, int x1, int z1) {
        return Shapes.or(shape, Block.box(x0, 6, z0, x1, 9, z1), Block.box(x0, 12, z0, x1, 15, z1));
    }

    private static VoxelShape gate(BlockState state) {
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        int drop = state.getValue(BlockStateProperties.IN_WALL) ? 3 : 0;
        var posts = Shapes.or(gateBox(facing, drop, 0, 5, 7, 2, 16, 9),
                gateBox(facing, drop, 14, 5, 7, 16, 16, 9));
        if (state.getValue(BlockStateProperties.OPEN)) {
            for (int x : new int[]{0, 14}) {
                posts = Shapes.or(posts, gateBox(facing, drop, x, 6, 13, x + 2, 15, 15),
                        gateBox(facing, drop, x, 6, 9, x + 2, 9, 13),
                        gateBox(facing, drop, x, 12, 9, x + 2, 15, 13));
            }
            return posts;
        }
        return Shapes.or(posts, gateBox(facing, drop, 6, 6, 7, 10, 15, 9),
                gateBox(facing, drop, 2, 6, 7, 14, 9, 9),
                gateBox(facing, drop, 2, 12, 7, 14, 15, 9));
    }

    private static VoxelShape gateBox(Direction facing, int drop, int x0, int y0, int z0, int x1, int y1, int z1) {
        return switch (facing) {
            case NORTH -> Block.box(16 - x1, y0 - drop, 16 - z1, 16 - x0, y1 - drop, 16 - z0);
            case EAST -> Block.box(z0, y0 - drop, 16 - x1, z1, y1 - drop, 16 - x0);
            case WEST -> Block.box(16 - z1, y0 - drop, x0, 16 - z0, y1 - drop, x1);
            default -> Block.box(x0, y0 - drop, z0, x1, y1 - drop, z1);
        };
    }
}
