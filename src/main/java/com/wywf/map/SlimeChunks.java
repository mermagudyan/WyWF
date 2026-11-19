package com.wywf.map;

public final class SlimeChunks {
    public static boolean contains(long seed,int chunkX,int chunkZ){return net.minecraft.world.level.levelgen.WorldgenRandom.seedSlimeChunk(chunkX,chunkZ,seed,987234911L).nextInt(10)==0;}
    private SlimeChunks(){}
}
