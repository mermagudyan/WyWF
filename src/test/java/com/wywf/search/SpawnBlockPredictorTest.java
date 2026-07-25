package com.wywf.search;

// This is a test file. These files have no effect on the main gameplay.

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnBlockPredictorTest {

    private static MinecraftWorldContextFactory factory;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        factory = new MinecraftWorldContextFactory();
    }

    @Test
    void predictsANonNullSurfaceBlock() {
        WorldContext ctx = factory.create(0L, false);
        String block = ctx.spawnPredictor.predict(ctx);
        assertNotNull(block, "spawn block should be determined");
        assertTrue(block.equals("any_solid") || block.startsWith("minecraft:"),
                "unexpected block id: " + block);
    }

    @Test
    void anySolidIsAlwaysPossible() {
        WorldContext ctx = factory.create(1L, false);
        assertTrue(ctx.spawnPredictor.isPossibleSurfaceBlock("any_solid"));
        assertTrue(ctx.spawnPredictor.isPossibleSurfaceBlock("minecraft:grass_block"));
        assertFalse(ctx.spawnPredictor.isPossibleSurfaceBlock("minecraft:bedrock"));
    }
}
