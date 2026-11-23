package com.wywf.verify;

import com.wywf.core.DimensionSpace;
import dev.diylib.client.render.DiyBlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;

import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;

public final class ReliefBlocks {
    public static final int SIZE = 64, SLICE_RADIUS = 32;
    private ReliefBlocks() {}

    public static ReliefTile tile(DimensionSpace dimension, int x, int z,
                                       Integer cut, int bottom, int ceiling, BlockGetter level,
                                       Function<BlockPos, BlockState> block, IntBinaryOperator top,
                                       Predicate<BlockPos> river, int fog) {
        return tile(dimension, x, z, cut, bottom, ceiling, level, block, top, top, river, fog);
    }

    public static ReliefTile tile(DimensionSpace dimension, int x, int z, Integer cut, int bottom, int ceiling,
                                  BlockGetter level, Function<BlockPos, BlockState> block, IntBinaryOperator top,
                                  IntBinaryOperator surroundingTop, Predicate<BlockPos> river, int fog) {
        return tile(dimension, x, z, cut, bottom, ceiling, level, block, top, surroundingTop, river,
                pos -> 0xFF1B464E, fog);
    }

    public static ReliefTile tile(DimensionSpace dimension, int x, int z, Integer cut, int bottom, int ceiling,
                                  BlockGetter level, Function<BlockPos, BlockState> block, IntBinaryOperator top,
                                  IntBinaryOperator surroundingTop, Predicate<BlockPos> river,
                                  java.util.function.ToIntFunction<BlockPos> waterTint, int fog) {
        if (dimension == DimensionSpace.END) return endTile(x, z, bottom, level, block, top, fog);
        if (cut != null) {
            return caveTile(x, z, cut, bottom, ceiling, level, block,
                    dimension == DimensionSpace.OVERWORLD ? top : null, SLICE_RADIUS, fog);
        }
        int[] colors = new int[256], heights = new int[256], voxels = new int[256 * ReliefTile.DEPTH];
        var palette = new java.util.IdentityHashMap<BlockState, Integer>();
        var trace = ReliefDiagnostics.traceTile(x, z);
        char[] blockIds = com.wywf.core.WyWFDebug.ENABLED ? new char[voxels.length] : null;
        int[] ground = dimension == DimensionSpace.OVERWORLD ? new int[256] : null;
        long[] sampledAir = ground == null ? null : new long[256];
        int[] solidUpTo = ground == null ? null : new int[256];
        int[] coveredWaterOffsets = null;
        var deeper = ground == null ? null : new ReliefColumns.Builder(blockIds != null);
        var terrainHeights = ground == null ? null : new ReliefSurfaceHeights(x, z, top, surroundingTop);
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int i = dz * 16 + dx, wx = x + dx, wz = z + dz;
            boolean traced = trace != null && ReliefDiagnostics.traces(wx, wz);
            int floor = terrainHeights == null ? top.applyAsInt(wx, wz) : terrainHeights.top(dx, dz);
            if (floor < bottom) {
                if (deeper != null) deeper.endColumn(i, false);
                continue;
            }
            var state = block.apply(pos.set(wx, floor, wz));
            boolean waterSurface = water(state);
            int color = reliefColor(state, level, pos, palette);
            while (!waterSurface && color == 0 && floor > bottom) {
                if (traced) ReliefDiagnostics.sample(null, 0, wx, floor, wz, state, sampleReason(state, color));
                state = block.apply(pos.set(wx, --floor, wz));
                waterSurface = water(state);
                color = reliefColor(state, level, pos, palette);
            }
            if (!waterSurface && color == 0) {
                if (deeper != null) deeper.endColumn(i, false);
                continue;
            }
            int waterColor = waterSurface ? waterColor(wx, floor, wz, bottom, level, block, palette, pos) : 0;
            int surfaceWater = waterSurface ? ReliefTile.WATER_FACE | (waterTint.applyAsInt(pos.set(wx, floor, wz)) & 0xFFFFFF) : 0;
            boolean entrance = ground != null && !waterSurface && color != BlockMapColors.LAVA && floor < ceiling
                    && block.apply(pos.set(wx, floor + 1, wz)).is(Blocks.CAVE_AIR);
            if (waterSurface) color = ReliefTile.WATER_FACE | (waterColor & 0xFFFFFF);
            else if (entrance && (color & ReliefTile.FACE_TYPE) == ReliefTile.FACE_TYPE) color = ReliefTile.ENTRANCE_FACE | (color & 0xFFFFFF);
            colors[i] = color;
            heights[i] = floor;
            int first = i * ReliefTile.DEPTH;
            int lowest = terrainHeights == null ? floor : Math.min(floor, terrainHeights.lowerTerrain(dx, dz));
            int sampleBottom = (int)Math.max(bottom, (long)lowest - ReliefTile.DEPTH + 1);
            int depth = floor - sampleBottom + 1;
            String[] traceColumn = traced ? (trace[i] = new String[depth]) : null;
            int support = 0, solidY = floor;
            if (ground != null) ground[i] = floor - depth;
            boolean caveAirAbove = false, canCoverWater = ground != null, underRiverIce = false;
            for (int offset = 0; offset < depth; offset++) {
                var below = block.apply(pos.set(wx, floor - offset, wz));
                boolean air = open(below);
                underRiverIce = underRiverIce && hasWater(below);
                int value = offset == 0 ? waterSurface ? surfaceWater : color : 0;
                if (underRiverIce) {
                    value = ReliefTile.COVERED_WATER;
                    if (coveredWaterOffsets == null) coveredWaterOffsets = new int[256];
                    if (coveredWaterOffsets[i] == 0) coveredWaterOffsets[i] = offset;
                } else if (offset != 0 && !air) {
                    value = water(below) ? ReliefTile.WATER_FACE : reliefColor(below, level, pos, palette);
                    if (ground != null && caveAirAbove && (value & ReliefTile.FACE_TYPE) == ReliefTile.FACE_TYPE
                            && value != BlockMapColors.LAVA)
                        value = ReliefTile.CAVE_FLOOR | (value & 0xFFFFFF);
                }
                if (offset < ReliefTile.DEPTH) {
                    voxels[first + offset] = value;
                    if (sampledAir != null && (air || ReliefTile.shaped(value & ReliefTile.FACE_TYPE)))
                        sampledAir[i] |= 1L << offset;
                    if (blockIds != null) blockIds[first + offset] = debugBlockId(below);
                } else {
                    deeper.add(offset - ReliefTile.DEPTH, air ? ReliefTile.SAMPLED_AIR : value,
                            blockIds == null ? 0 : debugBlockId(below));
                }
                caveAirAbove = ground != null && (below.is(Blocks.CAVE_AIR)
                        || caveAirAbove && ReliefTile.shaped(value & ReliefTile.FACE_TYPE));
                if (traced) ReliefDiagnostics.sample(traceColumn, offset, wx, floor - offset, wz, below, sampleReason(below, value));
                if (canCoverWater && (below.is(Blocks.ICE) || below.is(Blocks.FROSTED_ICE)))
                    underRiverIce = river.test(pos);
                int material = value & ReliefTile.FACE_TYPE;
                if (material != ReliefTile.FACE_TYPE) solidY = floor - offset - 1;
                canCoverWater = canCoverWater && (material == 0 || ReliefTile.plant(material) || below.is(Blocks.SNOW));
                if (ground == null || support == 3) continue;
                support = naturalGround(below) ? support + 1 : 0;
                if (support == 3) ground[i] = floor - offset + 2;
            }
            if (deeper != null) {
                deeper.endColumn(i, false);
                solidUpTo[i] = solidY;
            }
        }
        return new ReliefTile(x, z, colors, heights, voxels, fog, ground, 0, trace, blockIds,
                deeper == null ? null : deeper.build(), sampledAir, solidUpTo, coveredWaterOffsets);
    }

    private static ReliefTile endTile(int x, int z, int bottom, BlockGetter level,
                                      Function<BlockPos, BlockState> block, IntBinaryOperator top, int fog) {
        int[] colors = new int[256], heights = new int[256];
        boolean debug = com.wywf.core.WyWFDebug.ENABLED;
        var columns = new ReliefColumns.Builder(debug);
        var trace = ReliefDiagnostics.traceTile(x, z);
        var palette = new java.util.IdentityHashMap<BlockState, Integer>();
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int column = dz * 16 + dx, wx = x + dx, wz = z + dz;
            int y = top.applyAsInt(wx, wz);
            int rgb = 0;
            // vanilla spikes place fire inside the crystal, one block above the bedrock
            boolean crystal = y - 2 >= bottom && block.apply(pos.set(wx, y, wz)).is(Blocks.FIRE)
                    && block.apply(pos.set(wx, y - 1, wz)).is(Blocks.BEDROCK)
                    && block.apply(pos.set(wx, y - 2, wz)).is(Blocks.OBSIDIAN);
            while (y >= bottom) {
                var state = block.apply(pos.set(wx, y, wz));
                rgb = crystal ? ReliefTile.END_CRYSTAL : reliefColor(state, level, pos, palette);
                if (rgb != 0) break;
                y--;
            }
            heights[column] = y;
            colors[column] = rgb;
            boolean traced = trace != null && ReliefDiagnostics.traces(wx, wz);
            String[] traceColumn = traced ? (trace[column] = new String[Math.max(0, y - bottom + 1)]) : null;
            for (int by = y; by >= bottom; by--) {
                var state = block.apply(pos.set(wx, by, wz));
                int offset = y - by;
                int value = offset == 0 ? rgb : reliefColor(state, level, pos, palette);
                columns.add(offset, value, debug ? debugBlockId(state) : 0);
                if (traced) ReliefDiagnostics.sample(traceColumn, offset, wx, by, wz, state, sampleReason(state, value));
            }
            columns.endColumn(column, true);
        }
        return new ReliefTile(x, z, colors, heights, null, fog, null, 0, trace, null, columns.build(), null);
    }

    public static int surfaceColor(BlockState state, BlockGetter level, BlockPos pos, int bottom,
                                   Function<BlockPos, BlockState> block) {
        if (!water(state)) {
            int tint = DiyBlockColors.sample(state, level, pos);
            return tint == DiyBlockColors.NO_TINT ? 0xFF000000 | state.getMapColor(level, pos).col
                    : BlockMapColors.surfaceTint(tint);
        }
        return waterColor(pos.getX(), pos.getY(), pos.getZ(), bottom, level, block,
                new java.util.IdentityHashMap<>(), new BlockPos.MutableBlockPos());
    }

    private static int waterColor(int x, int surface, int z, int bottom, BlockGetter level,
                                  Function<BlockPos, BlockState> block, java.util.Map<BlockState, Integer> palette,
                                  BlockPos.MutableBlockPos pos) {
        int bed = 0xFF193439;
        int depth = Math.min(ReliefTile.DEPTH, surface - bottom);
        boolean foundBed = false;
        for (int d = 1; d <= depth; d++) {
            var state = block.apply(pos.set(x, surface - d, z));
            if (water(state) || aquaticDecoration(state) || open(state)) continue;
            int material = reliefColor(state, level, pos, palette);
            if (material == 0) continue;
            bed = ReliefShapes.rgb(material);
            depth = d;
            foundBed = true;
            break;
        }

        float transparency = foundBed ? (float)Math.exp(-.22 * depth) : 0;
        float ripple = (float)(Math.sin(x * .23 + z * .16 + Math.sin(z * .12) * 1.8)
                * Math.sin(z * .31 - x * .09)) * 3;
        int r = Math.clamp(Math.round(22 + ((bed >>> 16 & 255) * .58f - 22) * transparency + ripple), 0, 255);
        int g = Math.clamp(Math.round(51 + ((bed >>> 8 & 255) * .66f + 38 - 51) * transparency + ripple), 0, 255);
        int b = Math.clamp(Math.round(60 + ((bed & 255) * .60f + 44 - 60) * transparency + ripple * 1.4f), 0, 255);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static boolean water(BlockState state) {
        return (state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN) || aquaticDecoration(state) || hiddenDecoration(state))
                && hasWater(state);
    }

    private static boolean hasWater(BlockState state) {
        var fluid = state.getFluidState();
        return Fluids.WATER.isSame(fluid.getType()) || fluid.is(FluidTags.WATER);
    }

    private static ReliefTile caveTile(int x, int z, int cut, int bottom, int ceiling, BlockGetter level,
                                       Function<BlockPos, BlockState> block, IntBinaryOperator surface, int radius, int fog) {
        int top = Math.min(ceiling, cut + radius);
        int depth = Math.max(0, top - Math.max(bottom, cut - radius) + 1);
        int stride = Math.max(ReliefTile.DEPTH, depth);
        int[] colors = new int[256], heights = new int[256], voxels = new int[256 * stride];
        var palette = new java.util.IdentityHashMap<BlockState, Integer>();
        var trace = ReliefDiagnostics.traceTile(x, z);
        char[] blockIds = com.wywf.core.WyWFDebug.ENABLED ? new char[voxels.length] : null;
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int column = dz * 16 + dx;
            heights[column] = top;
            int wx = x + dx, wz = z + dz;
            boolean traced = trace != null && ReliefDiagnostics.traces(wx, wz);
            String[] traceColumn = traced ? (trace[column] = new String[stride]) : null;
            int roof = surface == null ? top : surface.applyAsInt(wx, wz);
            if (surface != null) {
                while (roof >= bottom && !terrainRock(block.apply(pos.set(wx, roof, wz)))) roof--;
                roof -= 4;
            }
            int first = Math.max(0, top - roof);
            if (blockIds != null) for (int offset = 0; offset < Math.min(first, depth); offset++) {
                var state = block.apply(pos.set(wx, top - offset, wz));
                blockIds[column * stride + offset] = debugBlockId(state);
                if (traced) ReliefDiagnostics.sample(traceColumn, offset, wx, top - offset, wz, state, "above-cave-roof-limit");
            }
            for (int offset = first; offset < depth; offset++) {
                var state = block.apply(pos.set(wx, top - offset, wz));
                if (blockIds != null) blockIds[column * stride + offset] = debugBlockId(state);
                if (open(state)) continue;
                int rgb = reliefColor(state, level, pos, palette);
                if (traced) ReliefDiagnostics.sample(traceColumn, offset, wx, top - offset, wz, state, sampleReason(state, rgb));
                if (rgb == 0) continue;
                colors[column] = rgb;
                voxels[column * stride + offset] = offset == first
                        && !ReliefTile.shaped(rgb & ReliefTile.FACE_TYPE)
                        ? ReliefTile.CLIPPED_FACE | (rgb & 0xFFFFFF) : rgb;
            }
        }
        return new ReliefTile(x, z, colors, heights, voxels, fog, null, depth, trace, blockIds);
    }

    private static char debugBlockId(BlockState state) {
        if (state.isAir()) return 0;
        int id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.getBlock());
        return id >= 0 && id < Character.MAX_VALUE ? (char)(id + 1) : 0;
    }

    private static String sampleReason(BlockState state, int color) {
        if (aquaticDecoration(state) && (color == 0 || color == ReliefTile.WATER_FACE)) return "small-aquatic-decoration";
        if (state.is(Blocks.WATER) && color == 0) return "water-interior-surface-only";
        if (color == 0) return "no-map-color-or-collision";
        return switch (color & ReliefTile.FACE_TYPE) {
            case ReliefTile.WATER_FACE -> "water-overlay";
            case ReliefTile.COVERED_WATER -> "under-river-ice";
            case ReliefTile.END_CRYSTAL -> "end-crystal";
            case ReliefTile.PLANT_FACE, ReliefTile.BERRY_FACE -> "passable-plant-20-percent-transparent";
            default -> null;
        };
    }

    private static boolean hiddenDecoration(BlockState state) {
        return state.getBlock() instanceof BaseTorchBlock || state.getBlock() instanceof ButtonBlock
                || state.is(Blocks.GLOW_LICHEN);
    }

    private static boolean aquaticDecoration(BlockState state) {
        return state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
                || state.getBlock() instanceof BaseCoralPlantTypeBlock;
    }

    private static int reliefColor(BlockState state, BlockGetter level, BlockPos pos,
                                   java.util.Map<BlockState, Integer> palette) {
        Integer cached = palette.get(state);
        if (cached != null) return cached;
        if (open(state) || aquaticDecoration(state) || hiddenDecoration(state)) return 0;
        var fluid = state.getFluidState();
        var mapColor = state.getMapColor(level, pos);
        int fallback;
        if (Fluids.LAVA.isSame(fluid.getType()) || fluid.is(FluidTags.LAVA)) fallback = BlockMapColors.LAVA;
        else if (mapColor != MapColor.NONE) fallback = 0xFF000000 | mapColor.col;
        else fallback = state.getCollisionShape(level, pos).isEmpty() ? 0 : 0xFF929CA3;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var sources = DiyBlockColors.sources(state);
        int tint = DiyBlockColors.sample(sources, state, level, pos);
        int color = tint != DiyBlockColors.NO_TINT ? tint
                : OreMapColors.choose(id, BlockMapColors.material(id, fallback));
        color = decorate(state, level, pos, color, tint != DiyBlockColors.NO_TINT);
        if (sources.isEmpty() && mapColor != MapColor.NONE) palette.put(state, color);
        return color;
    }

    private static int decorate(BlockState state, BlockGetter level, BlockPos pos, int color, boolean tinted) {
        var block = state.getBlock();
        if (block instanceof LeafLitterBlock) return ReliefShapes.pack(Blocks.MOSS_CARPET.defaultBlockState(), color);
        if (state.is(Blocks.VINE)) return ReliefShapes.pack(state, color);
        if (color == BlockMapColors.LAVA || block instanceof LeavesBlock || state.is(BlockTags.LEAVES)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.PALE_MOSS_BLOCK)) return color;
        boolean plant = block instanceof VegetationBlock || block instanceof GrowingPlantBlock || block instanceof HangingMossBlock
                || state.is(Blocks.CACTUS) || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.CHORUS_PLANT) || state.is(Blocks.CHORUS_FLOWER);
        if (plant && state.getCollisionShape(level, pos).isEmpty()) {
            if (color == 0) return 0;
            int material = state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(BlockStateProperties.AGE_3) >= 2
                    ? ReliefTile.BERRY_FACE : ReliefTile.PLANT_FACE;
            if (!tinted && (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))) color = 0xFF668A38;
            return material | (color & 0xFFFFFF);
        }
        return ReliefShapes.pack(state, color);
    }

    private static boolean naturalGround(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.STONE)
                || state.is(Blocks.SAND) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.BEDROCK);
    }

    private static boolean terrainRock(BlockState state) {
        return state.isSolidRender() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES);
    }

    private static boolean open(BlockState state) {
        return state.isAir() && state.getFluidState().isEmpty();
    }
}
