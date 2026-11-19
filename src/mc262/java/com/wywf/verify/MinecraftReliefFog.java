package com.wywf.verify;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import static net.minecraft.world.attribute.EnvironmentAttributes.FOG_COLOR;

public final class MinecraftReliefFog {
    public static int at(Level level, BlockPos pos) {
        return 0xFF000000 | level.environmentAttributes().getValue(FOG_COLOR, pos);
    }

    public static int biome(Biome biome) {
        return 0xFF000000 | biome.getAttributes().applyModifier(FOG_COLOR, FOG_COLOR.defaultValue());
    }

    private MinecraftReliefFog() {}
}
