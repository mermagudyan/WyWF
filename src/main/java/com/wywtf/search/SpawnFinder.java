package com.wywtf.search;

import com.wywtf.core.ParsedQuery;
import com.wywtf.core.SearchResult;

/**
 * Выбирает финальные координаты спавна для найденного сида.
 *
 * Логика:
 *   - Если есть структура — спавн в её центре (±8 блоков, чтобы не влитнуть в сундук).
 *   - Иначе если есть биом — спавн в центре найденного биома.
 *   - Иначе — (0, 0).
 *
 * В будущем можно добавить:
 *   - проверку высоты (чтобы спавн не под землёй/в воздухе)
 *   - проверку безопасности (не в лаве)
 *   - смещение к ближайшему берегу для океан-запросов
 */
public final class SpawnFinder {

    public SearchResult compute(long seed, ParsedQuery query, StructureChecker.Result structureResult) {
        int x, z;
        String description;

        if (structureResult.found) {
            // Спавн максимально близко к структуре
            x = structureResult.structureX;
            z = structureResult.structureZ;
            description = structureResult.structureId;
        } else if (!query.biomes().isEmpty()) {
            // Биом-запрос без структуры — берём (0,0), мир сам найдёт нужный биом
            // в радиусе (за это отвечает BiomeChecker)
            x = 0;
            z = 0;
            description = query.biomes().get(0);
        } else {
            x = 0;
            z = 0;
            description = "origin";
        }

        return new SearchResult(seed, x, z, description);
    }
}
