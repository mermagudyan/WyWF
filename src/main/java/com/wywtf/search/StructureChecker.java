package com.wywtf.search;

import com.wywtf.core.ParsedQuery;

/**
 * Стратегия проверки структур для одного сида.
 *
 * Структуры проверяются в первую очередь — это самый сильный фильтр.
 * Алгоритм проверки — реализация ниже использует structure placement
 * из ванильного worldgen без полной генерации чанков.
 */
public interface StructureChecker {

    /**
     * Ищет хотя бы одну из требуемых структур (или все, если указано) в радиусе {@code radiusChunks}.
     *
     * @param ctx           контекст мира
     * @param centerX       центр поиска, блоки
     * @param centerZ       центр поиска, блоки
     * @param radiusChunks  радиус в чанках
     * @param query         распарсенный запрос
     * @return результат проверки (найдено + координаты первой структуры)
     */
    Result check(WorldContext ctx, int centerX, int centerZ, int radiusChunks, ParsedQuery query);

    /** Результат поиска структур. */
    final class Result {
        public final boolean found;
        public final int     structureX;   // блоки, центр найденной структуры
        public final int     structureZ;
        public final String  structureId;  // canonical, напр. "minecraft:village"

        public Result(boolean found, int x, int z, String id) {
            this.found = found;
            this.structureX = x;
            this.structureZ = z;
            this.structureId = id;
        }

        public static Result notFound() { return new Result(false, 0, 0, null); }

        public static Result found(int x, int z, String id) { return new Result(true, x, z, id); }
    }
}
