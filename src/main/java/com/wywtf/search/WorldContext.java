package com.wywtf.search;

/**
 * Контекст мира для конкретного сида.
 *
 * Содержит всё необходимое для оффлайн-проверки биомов и структур
 * без полной генерации чанков. Создаётся один раз на поток-рабочий
 * и пересоздаётся для каждого нового сида (или переиспользуется через reset()).
 *
 * Поля должны быть иммутабельны или эффективно заменяться через {@link #forSeed(long)}.
 *
 * Реализация зависит от версии Minecraft. В 26.x с mojmap ожидается:
 *   - BiomeSource (создаётся из MultiNoiseBiomeSource / FixedBiomeSource для overworld)
 *   - ChunkGenerator (для запроса структур через StructurePlacement)
 *   - RegistryAccess.Frozen (registries)
 *
 * Чтобы не привязываться к конкретным классам Minecraft в этом файле —
 * реальная реализация лежит в {@code com.wywtf.search.MinecraftWorldContextFactory}.
 */
public final class WorldContext {

    public final long seed;
    public final Object biomeSource;        // net.minecraft.world.level.biome.BiomeSource
    public final Object chunkGenerator;     // net.minecraft.world.level.chunk.ChunkGenerator
    public final Object registryAccess;     // net.minecraft.core.RegistryAccess.Frozen

    public WorldContext(long seed, Object biomeSource, Object chunkGenerator, Object registryAccess) {
        this.seed = seed;
        this.biomeSource = biomeSource;
        this.chunkGenerator = chunkGenerator;
        this.registryAccess = registryAccess;
    }
}
