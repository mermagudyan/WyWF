package com.wywtf.core;

import java.util.*;

/**
 * Распарсенный запрос пользователя.
 *
 * Immutable. Содержит три группы условий:
 *   - structures (проверяются в первую очередь)
 *   - biomes     (проверяются вокруг найденных структур)
 *   - objects    (дополнительные условия)
 */
public final class ParsedQuery {

    private final String raw;
    private final List<String> structures;  // canonical
    private final List<String> biomes;      // canonical
    private final List<String> objects;     // canonical

    public ParsedQuery(String raw, List<String> structures, List<String> biomes, List<String> objects) {
        this.raw = raw;
        this.structures = List.copyOf(structures);
        this.biomes     = List.copyOf(biomes);
        this.objects    = List.copyOf(objects);
    }

    public String raw()        { return raw; }
    public List<String> structures() { return structures; }
    public List<String> biomes()     { return biomes; }
    public List<String> objects()    { return objects; }

    public boolean isEmpty() {
        return structures.isEmpty() && biomes.isEmpty() && objects.isEmpty();
    }

    /** Главная структура — та, рядом с которой надо заспавнить игрока (первая в списке). */
    public Optional<String> primaryStructure() {
        return structures.isEmpty() ? Optional.empty() : Optional.of(structures.get(0));
    }

    /** Главная точка интереса для спавна: структура, иначе первый биом. */
    public Optional<String> primaryTarget() {
        if (!structures.isEmpty()) return Optional.of(structures.get(0));
        if (!biomes.isEmpty())     return Optional.of(biomes.get(0));
        if (!objects.isEmpty())    return Optional.of(objects.get(0));
        return Optional.empty();
    }

    @Override public String toString() {
        return "ParsedQuery{structures=" + structures +
                ", biomes=" + biomes +
                ", objects=" + objects + '}';
    }
}
