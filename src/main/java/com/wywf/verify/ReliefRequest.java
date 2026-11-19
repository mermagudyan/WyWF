package com.wywf.verify;

import com.wywf.core.DimensionSpace;
import net.minecraft.core.BlockPos;

public record ReliefRequest(DimensionSpace dimension, int x, int z, Integer cut, BlockPos focus) {
    public boolean containsFocus(int chunkX, int chunkZ) {
        return (focus.getX() >> 4) == (chunkX >> 4) && (focus.getZ() >> 4) == (chunkZ >> 4);
    }
}
