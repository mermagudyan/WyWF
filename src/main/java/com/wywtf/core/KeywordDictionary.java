package com.wywtf.core;

import java.util.*;

/**
 * Словарь ключевых слов.
 *
 * Расширяемый: можно добавлять биомы, структуры, объекты и синонимы без изменения парсера.
 * В будущем словарь может загружаться из JSON (см. #loadFromJson / будущий KeywordDictionaryLoader).
 */
public final class KeywordDictionary {

    public enum Category {
        BIOME,
        STRUCTURE,
        OBJECT
    }

    /** Одна логическая сущность, на которую может ссылаться несколько синонимов. */
    public static final class Entry {
        public final String canonical;     // внутреннее имя, напр. "minecraft:village"
        public final Category category;
        public final String displayName;   // человеко-читаемое имя

        public Entry(String canonical, Category category, String displayName) {
            this.canonical = canonical;
            this.category = category;
            this.displayName = displayName;
        }

        @Override public String toString() { return canonical; }
    }

    // ---- Хранилища ---------------------------------------------------------

    /** canonical -> Entry */
    private final Map<String, Entry> entries = new HashMap<>();

    /** lowercase синоним (с пробелами) -> canonical */
    private final Map<String, String> synonymToCanonical = new HashMap<>();

    /** Сортированные по длине (desc) ключи для жадного матчинга. */
    private volatile List<String> sortedKeys = List.of();

    // ---- Конструктор -------------------------------------------------------

    public KeywordDictionary() {
        registerDefaults();
        rebuildIndex();
    }

    // ---- Публичный API -----------------------------------------------------

    public void register(Entry entry, String... synonyms) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.canonical, entry);
        // canonical тоже работает как синоним
        synonymToCanonical.put(entry.canonical.toLowerCase(Locale.ROOT), entry.canonical);
        for (String s : synonyms) {
            if (s != null && !s.isBlank()) {
                synonymToCanonical.put(s.toLowerCase(Locale.ROOT).trim(), entry.canonical);
            }
        }
    }

    public void registerSynonym(String synonym, String canonical) {
        synonymToCanonical.put(synonym.toLowerCase(Locale.ROOT).trim(), canonical);
    }

    /** Возвращает Entry по каноническому имени или null. */
    public Entry get(String canonical) {
        return entries.get(canonical);
    }

    /**
     * Пытается сматчить максимально длинный ключ из словаря начиная с позиции {@code start}
     * в строке {@code text} (lowercase). Возвращает длину совпавшего ключа или 0.
     * Каноническое имя совпадения кладётся в {@code outCanonical[0]}.
     */
    public int matchAt(String text, int start, String[] outCanonical) {
        // Локальная ссылка на volatile list — безопасно для многопоточного чтения.
        List<String> keys = sortedKeys;
        for (String key : keys) {
            int len = key.length();
            if (start + len > text.length()) continue;
            boolean ok = true;
            for (int i = 0; i < len; i++) {
                if (text.charAt(start + i) != key.charAt(i)) { ok = false; break; }
            }
            if (ok) {
                // Проверяем границу слова — следующий символ должен быть не-буквой
                int next = start + len;
                if (next < text.length()) {
                    char c = text.charAt(next);
                    if (Character.isLetter(c) || c == '-' || c == '_') continue;
                }
                outCanonical[0] = synonymToCanonical.get(key);
                return len;
            }
        }
        return 0;
    }

    public Collection<Entry> all() { return entries.values(); }

    /** Перестроить индекс длинных ключей. Вызывать после массовых изменений словаря. */
    public void rebuildIndex() {
        List<String> keys = new ArrayList<>(synonymToCanonical.keySet());
        // Сортируем по убыванию длины — жадно матчим самые длинные фразы первыми.
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        sortedKeys = List.copyOf(keys);
    }

    // ---- Дефолтный набор ---------------------------------------------------

    private void registerDefaults() {
        // === Биомы ===
        // Каждая запись: (canonicalId, displayNameRu, синонимы...)
        // Синонимы включают английские варианты для локализации запросов.
        registerBiome("minecraft:forest",            "лес",            "forest", "woods", "woodland");
        registerBiome("minecraft:taiga",             "тайга",          "taiga");
        registerBiome("minecraft:swamp",             "болото",         "swamp", "marsh", "wetland");
        registerBiome("minecraft:jungle",            "джунгли",        "jungle");
        registerBiome("minecraft:desert",            "пустыня",        "desert", "sand", "sandy");
        registerBiome("minecraft:savanna",           "саванна",        "savanna", "savannah");
        registerBiome("minecraft:plains",            "равнины",        "plains", "plain", "field", "meadow");
        registerBiome("minecraft:cherry_grove",      "вишневый лес",   "вишнёвый лес", "вишня",
                                                                  "cherry grove", "cherry forest", "cherry", "cherry blossom");
        registerBiome("minecraft:dark_forest",       "темный лес",     "тёмный лес", "dark forest", "dark woods");
        registerBiome("minecraft:ocean",             "океан",          "ocean", "sea");
        registerBiome("minecraft:warm_ocean",        "теплый океан",   "тёплый океан", "warm ocean", "warm sea", "tropical ocean");
        registerBiome("minecraft:cold_ocean",        "холодный океан", "cold ocean", "cold sea");
        registerBiome("minecraft:deep_ocean",        "глубокий океан", "deep ocean", "deep sea");
        registerBiome("minecraft:river",             "река",           "river", "stream");

        // Синонимы биомов
        registerSynonym("море", "minecraft:ocean");

        // === Структуры ===
        registerStructure("minecraft:village",               "деревня",
                                                              "village", "settlement", "town", "hamlet");
        registerStructure("minecraft:smithy",                "кузница", "кузнец",
                                                              "smithy", "blacksmith", "forge", "smithy house");
        // "кузница" в ванилле — это part of village, но для удобства моделируем как структуру-фильтр
        registerStructure("minecraft:ruined_portal",         "портал", "разрушенный портал",
                                                              "ruined portal", "portal", "broken portal", "nether portal");
        registerStructure("minecraft:pillager_outpost",      "аванпост", "форпост",
                                                              "pillager outpost", "outpost", "pillager tower", "raid outpost");
        registerStructure("minecraft:mansion",               "особняк",
                                                              "mansion", "woodland mansion", "woodland");
        registerStructure("minecraft:desert_pyramid",        "храм", "пирамида",
                                                              "desert pyramid", "desert temple", "pyramid", "temple");
        registerStructure("minecraft:jungle_temple",         "храм джунглей",
                                                              "jungle temple", "jungle pyramid");
        registerStructure("minecraft:shipwreck",             "корабль", "кораблекрушение",
                                                              "shipwreck", "ship", "wreck", "sunken ship");
        registerStructure("minecraft:igloo",                 "иглу",
                                                              "igloo", "ice house");
        registerStructure("minecraft:stronghold",            "крепость",
                                                              "stronghold", "fortress", "end portal", "stone stronghold");
        registerStructure("minecraft:trial_chambers",        "trial chambers", "триал чамберс",
                                                              "trial chamber", "trial chambers", "copper chambers");
        registerStructure("minecraft:ancient_city",          "древний город",
                                                              "ancient city", "ancient", "lost city", "deep dark city");

        // Синонимы структур
        registerSynonym("деревушка",  "minecraft:village");
        registerSynonym("корабль",    "minecraft:shipwreck");

        // === Объекты ===
        registerObject("minecraft:tree",  "дерево",  "tree", "trees", "oak", "birch", "spruce");
        registerObject("minecraft:water", "вода",    "water", "lake", "pond");
        registerObject("minecraft:lava",  "лава",    "lava", "magma");
    }

    private void registerBiome(String canonical, String displayName, String... synonyms) {
        register(new Entry(canonical, Category.BIOME, displayName), synonyms);
    }

    private void registerStructure(String canonical, String displayName, String... synonyms) {
        register(new Entry(canonical, Category.STRUCTURE, displayName), synonyms);
    }

    private void registerObject(String canonical, String displayName, String... synonyms) {
        register(new Entry(canonical, Category.OBJECT, displayName), synonyms);
    }
}
