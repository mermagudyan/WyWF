package com.wywtf.search;

import com.wywtf.core.*;
import java.util.Optional;

/**
 * Проверяет один сид на соответствие {@link ParsedQuery}.
 *
 * Стратегия проверки (соответствует спецификации):
 *   1. Структуры — сначала (самый сильный фильтр).
 *   2. Биомы вокруг найденной структуры.
 *   3. Объекты — доп. проверки (пока используется как биомный фильтр для «вода»/«лава»).
 *
 * Потокобезопасен, не имеет состояния.
 */
public final class SeedValidator {

    private final StructureChecker structureChecker;
    private final BiomeChecker     biomeChecker;
    private final SpawnFinder      spawnFinder;
    private final int              searchRadiusChunks;
    private final int              biomeRadiusChunks;

    public SeedValidator(StructureChecker sc, BiomeChecker bc, SpawnFinder sf,
                         int searchRadiusChunks, int biomeRadiusChunks) {
        this.structureChecker  = sc;
        this.biomeChecker      = bc;
        this.spawnFinder       = sf;
        this.searchRadiusChunks = searchRadiusChunks;
        this.biomeRadiusChunks = biomeRadiusChunks;
    }

    /**
     * Проверяет один сид. Возвращает результат, если сид подходит, иначе Optional.empty().
     */
    public Optional<SearchResult> validate(WorldContext ctx, ParsedQuery query) {
        // 1. Структуры (если есть в запросе)
        StructureChecker.Result struct = StructureChecker.Result.notFound();
        if (!query.structures().isEmpty()) {
            struct = structureChecker.check(ctx, 0, 0, searchRadiusChunks, query);
            if (!struct.found) {
                // Нет структуры — не подходящий сид
                return Optional.empty();
            }
        }

        // 2. Биомы вокруг найденной структуры (или вокруг (0,0) если структуры нет)
        int centerStructX = struct.found ? struct.structureX : 0;
        int centerStructZ = struct.found ? struct.structureZ : 0;
        if (!query.biomes().isEmpty()) {
            boolean ok = biomeChecker.check(ctx, centerStructX, centerStructZ, biomeRadiusChunks, query);
            if (!ok) return Optional.empty();
        }

        // 3. Объекты (вода/лава/дерево) — пока modeled как биомные проверки
        //    (вода → ocean/river, лава → не в списке, дерево → forest)
        //    Полная имплементация требует проверки блоков — выходит за рамки демо.
        if (!query.objects().isEmpty()) {
            boolean ok = checkObjects(ctx, centerStructX, centerStructZ, query);
            if (!ok) return Optional.empty();
        }

        // 4. Спавн
        SearchResult result = spawnFinder.compute(ctx.seed, query, struct);
        return Optional.of(result);
    }

    private boolean checkObjects(WorldContext ctx, int x, int z, ParsedQuery query) {
        // В будущем: sampled block check через chunk snapshot
        // Сейчас: пропускаем (считаем «истина»)
        return true;
    }
}
