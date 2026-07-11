package com.wywtf.search;

import com.wywtf.core.ParsedQuery;

/**
 * Стратегия проверки биомов для одного сида.
 *
 * Реализация должна быть потокобезопасной (без мутабельного общего состояния)
 * и максимально быстрой — десятки миллионов вызовов за запуск.
 *
 * В будущих версиях сюда можно подключать модовые биомы.
 */
public interface BiomeChecker {

    /**
     * Проверяет, что в радиусе {@code radiusChunks} вокруг точки {@code centerX, centerZ}
     * присутствуют все биомы из {@code query.biomes()}.
     *
     * @param ctx           контекст мира (BiomeSource и т.д.)
     * @param centerX       центр поиска, блоки
     * @param centerZ       центр поиска, блоки
     * @param radiusChunks  радиус в чанках
     * @param query         распарсенный запрос
     * @return true, если все требуемые биомы найдены
     */
    boolean check(WorldContext ctx, int centerX, int centerZ, int radiusChunks, ParsedQuery query);
}
