package com.wywf.search;

// This is a test file. These files have no effect on the main gameplay.

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructureParityTest {

    private static MinecraftWorldContextFactory factory;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        factory = new MinecraftWorldContextFactory();
    }

    @Test
    void findsMineshaftAndNetherAndEnd() {
        VanillaStructureChecker checker = new VanillaStructureChecker();
        int radius = 64;
        int found = 0;
        for (long seed = 1; seed <= 2000 && found < 3; seed++) {
            WorldContext ctx = factory.create(seed, false);
            if (checker.hasAnyPlacementWithin(ctx, 0, 0, radius, "minecraft:mineshaft")) found++;
        }
        assertTrue(found > 0, "mineshaft should be found in first 2000 seeds");

        int netherFound = 0;
        for (long seed = 1; seed <= 2000 && netherFound < 3; seed++) {
            WorldContext ctx = factory.create(seed, false);
            if (checker.hasAnyPlacementWithin(ctx, 0, 0, radius, "minecraft:fortress")) netherFound++;
        }
        assertTrue(netherFound > 0, "nether fortress should be found in first 2000 seeds");

        int endFound = 0;
        for (long seed = 1; seed <= 2000 && endFound < 3; seed++) {
            WorldContext ctx = factory.create(seed, false);
            if (checker.hasAnyPlacementWithin(ctx, 0, 0, radius, "minecraft:end_city")) endFound++;
        }
        assertTrue(endFound > 0, "end city should be found in first 2000 seeds");
    }
}
