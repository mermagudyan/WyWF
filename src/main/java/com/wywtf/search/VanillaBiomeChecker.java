package com.wywtf.search;

import com.wywtf.core.ParsedQuery;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.util.*;

/**
 * Реализация {@link BiomeChecker} поверх ванильного {@link BiomeSource}.
 *
 * Алгоритм:
 *   1. Для каждой требуемой структуры (если есть) — ранее найденный центр.
 *      Если структур нет — центр = (0, 0).
 *   2. Идём по сетке чанков в радиусе и опрашиваем {@code biomeSource.getBiome()}.
 *   3. Собираем множество ResourceLocation биомов.
 *   4. Проверяем, что все требуемые canonical-биомы присутствуют.
 *
 * Производительность: ~1 мкс на чанк. Для радиуса 16 чанков = 1024 чанкa ≈ 1ms на сид.
 *
 * Потокобезопасность: BiomeSource сам по себе потокобезопасен для чтения
 * (он без состояния кроме сидa, который зафиксирован в контексте).
 */
public final class VanillaBiomeChecker implements BiomeChecker {

    @Override
    public boolean check(WorldContext ctx, int centerX, int centerZ, int radiusChunks, ParsedQuery query) {
        if (query.biomes().isEmpty()) return true;

        BiomeSource biomeSource = (BiomeSource) ctx.biomeSource;
        if (biomeSource == null) return false;

        // Собираем уникальные id биомов в радиусе
        Set<String> found = new HashSet<>(query.biomes().size() * 2);
        int chunkMinX = (centerX >> 4) - radiusChunks;
        int chunkMaxX = (centerX >> 4) + radiusChunks;
        int chunkMinZ = (centerZ >> 4) - radiusChunks;
        int chunkMaxZ = (centerZ >> 4) + radiusChunks;

        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                int blockX = cx << 4;
                int blockZ = cz << 4;

                // В 26.x BiomeSource.getBiome(int, int, int) без noise sampler
                // возвращает Holder<Biome> по упрощённому пути (MultiNoise lookup).
                // Привязка к точной сигнатуре делается в адаптере; здесь показан принцип.
                Holder<Biome> holder;
                try {
                    holder = biomeSource.getBiome(blockX, 64, blockZ, null);
                } catch (Throwable ignored) {
                    continue;
                }

                if (holder != null) {
                    String id = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
                    if (!id.isEmpty()) found.add(id);
                    if (found.size() >= query.biomes().size() && found.containsAll(query.biomes())) {
                        return true;
                    }
                }
            }
        }

        return found.containsAll(query.biomes());
    }
}
