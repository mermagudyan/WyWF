package com.wywtf.search;

/**
 * Фабрика {@link WorldContext} для конкретного сида.
 *
 * Реализация скрыта за интерфейсом, чтобы:
 *   - можно было тестировать парсер/чекеры на моках;
 *   - можно было подменять стратегию создания контекста (например, для Nether-биомов).
 *
 * В 26.x реализация использует {@code net.minecraft.world.level.biome.BiomeSource}
 * и {@code net.minecraft.world.level.chunk.ChunkGenerator} из Registries.
 */
public interface WorldContextFactory {

    WorldContext create(long seed);
}
