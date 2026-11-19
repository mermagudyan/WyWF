package com.wywf.verify;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;

final class ServerClimate {
    static Climate.Sampler sampler(ServerLevel level) { return level.getChunkSource().randomState().sampler(); }
}
