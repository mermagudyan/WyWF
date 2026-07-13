package com.wywf.core;

import java.util.*;

public final class KeywordDictionary {

    public enum Category {
        BIOME,
        STRUCTURE,
        OBJECT,
        SPAWN
    }

    public static final class Entry {
        public final String canonical;
        public final Category category;
        public final String displayName;

        public Entry(String canonical, Category category, String displayName) {
            this.canonical = canonical;
            this.category = category;
            this.displayName = displayName;
        }

        @Override public String toString() { return canonical; }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    private final Map<String, String> synonymToCanonical = new HashMap<>();

    private volatile List<String> sortedKeys = List.of();

    private volatile List<String> blockSortedKeys = List.of();

    public KeywordDictionary() {
        registerDefaults();
        rebuildIndex();
    }

    public void register(Entry entry, String... synonyms) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.canonical, entry);

        synonymToCanonical.put(entry.canonical.toLowerCase(Locale.ROOT), entry.canonical);

        if (entry.displayName != null && !entry.displayName.isBlank()) {
            synonymToCanonical.put(entry.displayName.toLowerCase(Locale.ROOT).trim(), entry.canonical);
        }
        for (String s : synonyms) {
            if (s != null && !s.isBlank()) {
                synonymToCanonical.put(s.toLowerCase(Locale.ROOT).trim(), entry.canonical);
            }
        }
    }

    public void registerSynonym(String synonym, String canonical) {
        synonymToCanonical.put(synonym.toLowerCase(Locale.ROOT).trim(), canonical);
    }

    public Entry get(String canonical) {
        return entries.get(canonical);
    }

    public int matchAt(String text, int start, String[] outCanonical) {

        List<String> keys = sortedKeys;
        for (String key : keys) {
            int len = key.length();
            if (start + len > text.length()) continue;
            boolean ok = true;
            for (int i = 0; i < len; i++) {
                if (text.charAt(start + i) != key.charAt(i)) { ok = false; break; }
            }
            if (ok) {

                int next = start + len;
                if (next < text.length()) {
                    char c = text.charAt(next);
                    if (Character.isLetter(c) || c == '-' || c == '_') continue;
                }
                if (isSpawnKey(key)) continue;
                outCanonical[0] = synonymToCanonical.get(key);
                return len;
            }
        }
        return 0;
    }

    /** Matches only {@link Category#SPAWN} (block) keywords. Used when a spawn trigger precedes the word. */
    public int matchBlockAt(String text, int start, String[] outCanonical) {
        List<String> keys = blockSortedKeys;
        for (String key : keys) {
            int len = key.length();
            if (start + len > text.length()) continue;
            boolean ok = true;
            for (int i = 0; i < len; i++) {
                if (text.charAt(start + i) != key.charAt(i)) { ok = false; break; }
            }
            if (ok) {
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

    private boolean isSpawnKey(String key) {
        String canonical = synonymToCanonical.get(key);
        Entry e = canonical == null ? null : entries.get(canonical);
        return e != null && e.category == Category.SPAWN;
    }

    public Collection<Entry> all() { return entries.values(); }

    public void rebuildIndex() {
        List<String> keys = new ArrayList<>(synonymToCanonical.keySet());

        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        sortedKeys = List.copyOf(keys);

        List<String> blocks = new ArrayList<>();
        for (String key : keys) {
            if (isSpawnKey(key)) blocks.add(key);
        }
        blockSortedKeys = List.copyOf(blocks);
    }

    private void registerDefaults() {
        // ---- biomes (surface) ----
        registerBiome("minecraft:forest", "лес", "forest", "woods", "woodland", "oak forest");
        registerBiome("minecraft:taiga", "тайга", "taiga", "spruce forest", "pine forest", "boreal forest");
        registerBiome("minecraft:swamp", "болото", "swamp", "marsh", "wetland", "bog", "swampland");
        registerBiome("minecraft:jungle", "джунгли", "jungle", "rainforest", "tropical forest");
        registerBiome("minecraft:desert", "пустыня", "desert", "sandy", "dunes", "sand desert");
        registerBiome("minecraft:savanna", "саванна", "savanna", "savannah", "dry grassland");
        registerBiome("minecraft:plains", "равнины", "plains", "plain", "field", "grassland", "grassy field");
        registerBiome("minecraft:cherry_grove", "вишневый лес", "вишнёвый лес", "вишня",
                "cherry grove", "cherry forest", "cherry", "cherry blossom", "sakura", "sakura grove");
        registerBiome("minecraft:dark_forest", "темный лес", "тёмный лес",
                "dark forest", "dark woods", "roofed forest");
        registerBiome("minecraft:ocean", "океан", "ocean", "sea", "море", "моря", "океана", "океане");
        registerBiome("minecraft:warm_ocean", "теплый океан", "тёплый океан",
                "warm ocean", "warm sea", "tropical ocean", "tropical sea",
                "теплого океана", "тёплого океана", "теплом океане", "тёплом океане");
        registerBiome("minecraft:cold_ocean", "холодный океан",
                "cold ocean", "cold sea", "холодного океана", "холодном океане");
        registerBiome("minecraft:deep_ocean", "глубокий океан",
                "deep ocean", "deep sea", "глубокого океана", "глубоком океане");
        registerBiome("minecraft:river", "река", "river", "stream", "creek", "реки", "реке");
        registerBiome("minecraft:badlands", "пустошь", "бэдлендс", "меса", "badlands", "mesa", "badland");
        registerBiome("minecraft:wooded_badlands", "лесистая пустошь",
                "wooded badlands", "wooded mesa", "forested badlands");
        registerBiome("minecraft:birch_forest", "березовый лес", "берёзовый лес",
                "birch forest", "birch woods");
        registerBiome("minecraft:flower_forest", "цветочный лес",
                "flower forest", "flowery forest", "flowers forest");
        registerBiome("minecraft:mushroom_fields", "грибные поля", "грибной остров",
                "mushroom fields", "mushroom island", "mushroom", "mooshroom island", "mushrom", "mushroom biome");
        registerBiome("minecraft:mangrove_swamp", "мангровое болото", "мангры",
                "mangrove swamp", "mangrove", "mangroves");
        registerBiome("minecraft:bamboo_jungle", "бамбуковые джунгли", "bamboo jungle", "bamboo forest");
        registerBiome("minecraft:sparse_jungle", "редкие джунгли", "sparse jungle", "jungle edge");
        registerBiome("minecraft:snowy_plains", "снежная равнина", "тундра",
                "snowy plains", "snowy tundra", "tundra", "ice plains", "snow plains");
        registerBiome("minecraft:snowy_taiga", "снежная тайга", "snowy taiga", "cold taiga");
        registerBiome("minecraft:ice_spikes", "ледяные шипы", "ice spikes", "ice spike");
        registerBiome("minecraft:meadow", "луг", "meadow", "alpine meadow");
        registerBiome("minecraft:grove", "роща", "grove", "snowy grove");
        registerBiome("minecraft:windswept_hills", "холмы", "горы",
                "windswept hills", "mountains", "extreme hills", "hills", "mountain");
        registerBiome("minecraft:jagged_peaks", "горные пики", "пики",
                "jagged peaks", "mountain peaks", "peaks", "snowy peaks");
        registerBiome("minecraft:frozen_ocean", "замерзший океан", "замёрзший океан",
                "frozen ocean", "frozen sea", "icy ocean");
        registerBiome("minecraft:lukewarm_ocean", "тепловатый океан", "lukewarm ocean", "lukewarm sea");
        registerBiome("minecraft:beach", "пляж", "берег", "beach", "shore", "coast", "seaside");
        registerBiome("minecraft:sunflower_plains", "подсолнуховые равнины", "подсолнухи",
                "sunflower plains", "sunflowers", "sunflower field");
        registerBiome("minecraft:old_growth_pine_taiga", "гигантская тайга", "мегатайга",
                "old growth pine taiga", "mega taiga", "giant taiga", "old growth taiga", "pine taiga");
        registerBiome("minecraft:pale_garden", "бледный сад", "pale garden", "pale forest");

        registerBiome("minecraft:savanna_plateau", "плато саванны", "плато", "savanna plateau", "plateau");
        registerBiome("minecraft:windswept_savanna", "ветреная саванна", "windswept savanna", "savanna hills");
        registerBiome("minecraft:snowy_beach", "снежный пляж", "snowy beach", "frozen beach");
        registerBiome("minecraft:stony_shore", "каменистый берег", "stony shore", "stone shore", "rocky shore");
        registerBiome("minecraft:frozen_river", "замерзшая река", "frozen river", "icy river");
        registerBiome("minecraft:windswept_forest", "ветреный лес", "windswept forest", "windswept woods");
        registerBiome("minecraft:frozen_peaks", "замерзшие пики", "frozen peaks", "ice peaks");
        registerBiome("minecraft:stony_peaks", "каменистые пики", "stony peaks", "stone peaks", "rocky peaks");
        registerBiome("minecraft:eroded_badlands", "эродированная пустошь", "эродированные бэдлендс",
                "eroded badlands", "eroded mesa", "weathered badlands");

        // ---- biomes (underground / cave) ----
        registerBiome("minecraft:deep_dark", "глубокая тьма", "тьма", "дип дарк",
                "deep dark", "deep_dark", "darkness");
        registerBiome("minecraft:lush_caves", "пышные пещеры", "пышная пещера", "цветущие пещеры",
                "lush caves", "lush cave", "lush", "lush cavern");
        registerBiome("minecraft:dripstone_caves", "капельные пещеры", "сталактитовые пещеры", "капельная пещера",
                "dripstone caves", "dripstone cave", "dripstone", "stalactite caves");
        registerBiome("minecraft:sulfur_caves", "серные пещеры", "серная пещера",
                "sulfur caves", "sulfur cave", "sulfur", "sulphur caves");

        // ---- structures ----
        registerStructure("minecraft:village", "деревня", "деревушка", "деревни", "деревню", "деревней",
                "village", "settlement", "town", "hamlet", "villagers");
        registerStructure("minecraft:smithy", "кузница", "кузнец",
                "smithy", "blacksmith", "forge", "smithy house", "smith");
        registerStructure("minecraft:ruined_portal", "портал", "разрушенный портал",
                "ruined portal", "portal", "broken portal", "nether portal");
        registerStructure("minecraft:pillager_outpost", "аванпост", "форпост",
                "pillager outpost", "outpost", "pillager tower", "raid outpost", "pillager camp");
        registerStructure("minecraft:mansion", "особняк", "особняка", "особняком",
                "mansion", "woodland mansion", "woodland", "illager mansion");
        registerStructure("minecraft:desert_pyramid", "храм", "пирамида",
                "desert pyramid", "desert temple", "pyramid", "temple", "sand temple");
        registerStructure("minecraft:jungle_temple", "храм джунглей",
                "jungle temple", "jungle pyramid");
        registerStructure("minecraft:shipwreck", "корабль", "кораблекрушение",
                "shipwreck", "ship", "wreck", "sunken ship", "wrecked ship");
        registerStructure("minecraft:igloo", "иглу", "igloo", "ice house", "snow house");
        registerStructure("minecraft:stronghold", "крепость",
                "stronghold", "fortress", "end portal", "stone stronghold");
        registerStructure("minecraft:trial_chambers", "trial chambers", "триал чамберс",
                "trial chamber", "copper chambers");
        registerStructure("minecraft:ancient_city", "древний город",
                "ancient city", "ancient", "lost city", "deep dark city", "warden city");
        registerStructure("minecraft:mineshaft", "шахта", "рудник",
                "mineshaft", "mine", "abandoned mineshaft", "mine shaft");
        registerStructure("minecraft:swamp_hut", "хижина ведьмы", "болотная хижина",
                "witch hut", "swamp hut", "witch house", "witch cottage");
        registerStructure("minecraft:ocean_monument", "монумент", "подводный храм",
                "ocean monument", "monument", "guardian temple", "water temple", "sea monument");
        registerStructure("minecraft:ocean_ruins", "руины океана", "подводные руины",
                "ocean ruins", "underwater ruins", "ocean ruin", "sunken ruins");
        registerStructure("minecraft:buried_treasure", "закопанное сокровище", "клад", "сокровище",
                "buried treasure", "treasure", "treasure chest");
        registerStructure("minecraft:trail_ruins", "тропные руины",
                "trail ruins", "trail ruin");

        // ---- objects ----
        registerObject("minecraft:tree", "дерево", "tree", "trees", "oak", "birch", "spruce");
        registerObject("minecraft:water", "вода", "water", "lake", "pond");
        registerObject("minecraft:lava", "лава", "lava", "magma", "lava pool", "lava lake");

        // ---- spawn surface blocks ----
        registerBlock("minecraft:grass_block", "трава", "grass", "grass block", "травяной блок");
        registerBlock("minecraft:dirt", "земля", "dirt", "soil", "soil block");
        registerBlock("minecraft:coarse_dirt", "крупная земля", "coarse dirt", "coarse soil");
        registerBlock("minecraft:sand", "песок", "sand", "sand block", "песчаный блок");
        registerBlock("minecraft:stone", "камень", "stone", "rock", "stone block");
        registerBlock("minecraft:snow_block", "снег", "snow", "snow block", "снежный блок");
        registerBlock("minecraft:podzol", "подзол", "podzol");
        registerBlock("minecraft:mycelium", "мицелий", "mycelium");
        registerBlock("minecraft:gravel", "гравий", "gravel");
        registerBlock("any_solid", "любой твёрдый блок", "любой твёрдый", "любые твёрдые", "любых твёрдых",
                "any solid", "any solid block", "solid block", "твёрдый блок", "твердый блок",
                "твёрдых", "твердых", "твёрдое", "твердое");
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

    private void registerBlock(String canonical, String displayName, String... synonyms) {
        register(new Entry(canonical, Category.SPAWN, displayName), synonyms);
    }
}
