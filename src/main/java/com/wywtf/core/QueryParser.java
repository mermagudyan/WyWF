package com.wywtf.core;

import java.util.*;

/**
 * Парсер естественного языка → {@link ParsedQuery}.
 *
 * Алгоритм:
 *   1. Нормализуем строку (lowercase, замена разделителей).
 *   2. Идём по строке и жадно матчим ключи словаря по убыванию длины.
 *   3. Извлекаем только известные ключевые слова, весь остальной «мусор» игнорируется.
 *   4. Распределяем найденные сущности по категориям (structures / biomes / objects).
 *
 * Примеры, которые должны давать одинаковый результат:
 *   "деревня"
 *   "хочу деревню"
 *   "заспавни возле деревни"
 *   "деревня рядом"
 *   "около деревни"
 *   "алала заспавни меня рядом с кузницей возле океана лалала"
 */
public final class QueryParser {

    private final KeywordDictionary dict;

    public QueryParser(KeywordDictionary dict) {
        this.dict = dict;
    }

    public ParsedQuery parse(String input) {
        if (input == null || input.isBlank()) {
            return new ParsedQuery("", List.of(), List.of(), List.of());
        }

        // Нормализация
        String text = input.toLowerCase(Locale.ROOT);
        // Не трогаем буквы, только схлопываем лишние пробелы
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
                lastSpace = false;
            } else {
                if (!lastSpace) { sb.append(' '); lastSpace = true; }
            }
        }
        String normalized = sb.toString().trim();

        List<String> structures = new ArrayList<>();
        List<String> biomes     = new ArrayList<>();
        List<String> objects    = new ArrayList<>();
        Set<String> seen        = new HashSet<>();  // защита от дублей

        int i = 0;
        int len = normalized.length();
        String[] out = new String[1];
        while (i < len) {
            char c = normalized.charAt(i);
            if (c == ' ') { i++; continue; }

            int matched = dict.matchAt(normalized, i, out);
            if (matched > 0) {
                String canonical = out[0];
                if (canonical != null && seen.add(canonical)) {
                    KeywordDictionary.Entry e = dict.get(canonical);
                    if (e != null) {
                        switch (e.category) {
                            case STRUCTURE -> structures.add(canonical);
                            case BIOME     -> biomes.add(canonical);
                            case OBJECT    -> objects.add(canonical);
                        }
                    }
                }
                i += matched;
            } else {
                // неизвестное слово — пропускаем до следующего пробела
                while (i < len && normalized.charAt(i) != ' ') i++;
            }
        }

        return new ParsedQuery(input, structures, biomes, objects);
    }

    /**
     * Эвристика: является ли строка запросом (а не обычным сидом).
     * Запрос — если парсер смог извлечь хотя бы одну известную сущность.
     */
    public boolean isQuery(String input) {
        return !parse(input).isEmpty();
    }
}
