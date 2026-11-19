package com.wywf.verify;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

final class ServerClimate {
    static Climate.Sampler sampler(ServerLevel level) {
        return level.getChunkSource().randomState().createClimateSampler(SamplerContext.builder().build());
    }
}
