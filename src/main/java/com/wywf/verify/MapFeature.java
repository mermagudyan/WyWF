package com.wywf.verify;

import java.util.Set;

public record MapFeature(String id,int x,int y,int z) {
    public static final Set<String> GENERATED=Set.of("wywf:lava_pool","wywf:amethyst_geode","wywf:iron_vein","wywf:copper_vein","wywf:spawner",EndReturnGateways.ID);
}
