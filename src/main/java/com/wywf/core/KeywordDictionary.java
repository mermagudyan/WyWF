package com.wywf.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KeywordDictionary {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-dict");

    public enum Category {
        BIOME,
        STRUCTURE,
        OBJECT,
        SPAWN,
        SPAWN_TRIGGER,
        MODIFIER
    }

    /** Which synonym file(s) to load. AUTO merges EN + RU with a collision check. */
    public enum Lang {
        EN,
        RU,
        AUTO
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

    private final Map<String, String> modifierCanonical = new HashMap<>();

    private final Set<String> spawnTriggers = new HashSet<>();

    private final Map<String, List<String>> variants = new HashMap<>();

    private final Map<String, String> variantToBase = new HashMap<>();

    private volatile List<String> sortedKeys = List.of();

    private volatile List<String> blockSortedKeys = List.of();

    private static final Gson GSON = new Gson();

    public KeywordDictionary() {
        this(Lang.AUTO);
    }

    public KeywordDictionary(Lang lang) {
        load(lang);
        rebuildIndex();
    }

    /** Loads the synonym file(s) for the given language. EN is always the base /
     *  fallback: if the chosen language's file is missing or fails to parse, EN is
     *  used instead (never an error to the player). AUTO merges EN + the chosen
     *  secondary language, throwing on a duplicate synonym across files. */
    private void load(Lang lang) {
        boolean loadedAny = false;

        if (lang == Lang.EN) {
            loadedAny |= loadFile("assets/wywf/data/en_us.json");
        } else if (lang == Lang.RU) {
            loadedAny = loadFile("assets/wywf/data/ru_ru.json");
            if (!loadedAny) loadedAny = loadFile("assets/wywf/data/en_us.json");
        } else { // AUTO
            loadedAny |= loadFile("assets/wywf/data/en_us.json");
            loadedAny |= loadFile("assets/wywf/data/ru_ru.json");
        }

        if (!loadedAny) {
            // Last-resort: hardcoded defaults so the mod still parses something.
            registerDefaults();
        }
    }

    /** @return true if the file was found and parsed. */
    private boolean loadFile(String resource) {
        InputStream in = KeywordDictionary.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) return false;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return false;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String key = e.getKey();
                if (key.startsWith("wywf.variant.")) {
                    String generic = key.substring("wywf.variant.".length());
                    if (!e.getValue().isJsonArray()) continue;
                    List<String> list = new ArrayList<>();
                    for (JsonElement v : e.getValue().getAsJsonArray()) {
                        if (v.isJsonPrimitive()) list.add(v.getAsString());
                    }
                    variants.put(generic, List.copyOf(list));
                    for (String v : list) {
                        variantToBase.putIfAbsent(v, generic);
                    }
                    continue;
                }
                if (!key.startsWith("wywf.synonym.")) continue;
                String rest = key.substring("wywf.synonym.".length());
                if (rest.equals("spawn_trigger")) {
                    if (!e.getValue().isJsonArray()) continue;
                    for (JsonElement syn : e.getValue().getAsJsonArray()) {
                        if (syn.isJsonPrimitive()) spawnTriggers.add(syn.getAsString().toLowerCase(Locale.ROOT).trim());
                    }
                    continue;
                }
                List<String> parts = splitKey(key);
                if (parts == null) continue;
                String cat = parts.get(0);
                String canonical = parts.get(1);
                if (cat.equals("modifier")) canonical = canonical.toUpperCase(Locale.ROOT);
                Category category = categoryOf(cat);
                if (category == null) continue;
                if (!e.getValue().isJsonArray()) continue;
                for (JsonElement syn : e.getValue().getAsJsonArray()) {
                    if (!syn.isJsonPrimitive()) continue;
                    registerSynonym(syn.getAsString(), canonical, category);
                }
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.info("Failed to load keyword file '{}': {}", resource, ex.getMessage());
            return false;
        }
    }

    /** Splits "wywf.synonym.<cat>.<canonical>" into [cat, canonical]. */
    private static List<String> splitKey(String key) {
        // key = wywf.synonym.modifier.near  -> [modifier, near]
        // key = wywf.synonym.biome.minecraft:plains -> [biome, minecraft:plains]
        String prefix = "wywf.synonym.";
        if (!key.startsWith(prefix)) return null;
        String rest = key.substring(prefix.length());
        int dot = rest.indexOf('.');
        if (dot < 0) return null;
        return List.of(rest.substring(0, dot), rest.substring(dot + 1));
    }

    private static Category categoryOf(String cat) {
        return switch (cat) {
            case "modifier" -> Category.MODIFIER;
            case "spawn_trigger" -> Category.SPAWN_TRIGGER;
            case "biome" -> Category.BIOME;
            case "structure" -> Category.STRUCTURE;
            case "object" -> Category.OBJECT;
            case "block" -> Category.SPAWN;
            default -> null;
        };
    }

    public void register(Entry entry, String... synonyms) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.canonical, entry);

        synonymToCanonical.put(entry.canonical.toLowerCase(Locale.ROOT), entry.canonical);
        if (entry.category == Category.MODIFIER) {
            modifierCanonical.put(entry.canonical.toLowerCase(Locale.ROOT), entry.canonical);
        } else if (entry.category == Category.SPAWN_TRIGGER) {
            spawnTriggers.add(entry.canonical.toLowerCase(Locale.ROOT));
        }

        if (entry.displayName != null && !entry.displayName.isBlank()) {
            synonymToCanonical.put(entry.displayName.toLowerCase(Locale.ROOT).trim(), entry.canonical);
        }
        for (String s : synonyms) {
            if (s != null && !s.isBlank()) {
                String key = s.toLowerCase(Locale.ROOT).trim();
                synonymToCanonical.put(key, entry.canonical);
                if (entry.category == Category.MODIFIER) {
                    modifierCanonical.put(key, entry.canonical);
                } else if (entry.category == Category.SPAWN_TRIGGER) {
                    spawnTriggers.add(key);
                }
            }
        }
    }

    public void registerSynonym(String synonym, String canonical, Category category) {
        if (synonym == null || synonym.isBlank()) return;
        String key = synonym.toLowerCase(Locale.ROOT).trim();
        String existing = synonymToCanonical.get(key);
        if (existing != null) {
            // First definition wins. A later synonym mapping to a different canonical
            // (e.g. a cross-language or intra-file clash) is skipped and logged.
            if (!existing.equals(canonical)) {
                LOGGER.debug("Keyword '{}' already maps to '{}', ignoring duplicate '{}'",
                        synonym, existing, canonical);
            }
            return;
        }
        synonymToCanonical.put(key, canonical);
        entries.computeIfAbsent(canonical, c -> new Entry(c, category, null));
        if (category == Category.MODIFIER) {
            modifierCanonical.put(key, canonical);
        } else if (category == Category.SPAWN_TRIGGER) {
            spawnTriggers.add(key);
        }
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
                    if (Character.isLetter(c) || c == '-' || c == '_' || c == '.') continue;
                }
                if (isSpawnKey(key)) continue;
                if (isModifierKey(key)) continue;
                if (isSpawnTriggerKey(key)) continue;
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
                    if (Character.isLetter(c) || c == '-' || c == '_' || c == '.') continue;
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

    private boolean isModifierKey(String key) {
        return modifierCanonical.containsKey(key);
    }

    private boolean isSpawnTriggerKey(String key) {
        return spawnTriggers.contains(key);
    }

    public String getModifier(String word) {
        if (word == null) return null;
        return modifierCanonical.get(word.toLowerCase(Locale.ROOT).trim());
    }

    public boolean isSpawnTrigger(String word) {
        if (word == null) return false;
        return spawnTriggers.contains(word.toLowerCase(Locale.ROOT).trim());
    }

    public List<String> getVariants(String canonical) {
        List<String> v = variants.get(canonical);
        if (v != null) return v;
        String base = variantToBase.get(canonical);
        if (base != null) return List.of(base);
        return List.of(canonical);
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

    /**
     * Returns the best fuzzy suggestion for a misspelled word, or null if none
     * is close enough. Uses consonant-skeleton matching: consonants from the input
     * must be a subsequence of the target's consonants, and the input length must
     * be within 2 of the target length.
     *
     * <p>Examples that match: "vllg"→"village", "dsrt"→"desert", "vilge"→"village".
     * Examples that don't: "drts"→"desert" (consonants out of order),
     * "dskdfoskdrt"→any (gibberish), "villllage"→"village" (too many l's).
     */
    public String findSuggestion(String word) {
        if (word == null || word.isBlank()) return null;
        String lower = word.toLowerCase(Locale.ROOT).trim();
        if (synonymToCanonical.containsKey(lower)) return null;

        String inputConsonants = extractConsonants(lower);
        if (inputConsonants.length() < 2) return null;

        String bestMatch = null;
        int bestLen = Integer.MAX_VALUE;

        for (String key : sortedKeys) {
            if (key.length() < 3) continue;
            if (lower.length() < key.length() / 2) continue;

            String targetConsonants = extractConsonants(key);
            if (!isSubsequence(inputConsonants, targetConsonants)) continue;

            if (key.length() < bestLen) {
                bestLen = key.length();
                bestMatch = key;
            }
        }
        return bestMatch;
    }

    private static final java.util.Set<Character> CONSONANTS = Set.of(
            'b','c','d','f','g','h','j','k','l','m','n','p','q','r','s','t','v','w','x','y','z');

    private static String extractConsonants(String word) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (CONSONANTS.contains(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** Returns true if {@code input} is a subsequence of {@code target}. */
    private static boolean isSubsequence(String input, String target) {
        int i = 0, j = 0;
        while (i < input.length() && j < target.length()) {
            if (input.charAt(i) == target.charAt(j)) i++;
            j++;
        }
        return i == input.length();
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            int[] curr = new int[n + 1];
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(
                        curr[j - 1] + 1,
                        prev[j] + 1),
                        prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[n];
    }

    /** Hardcoded fallback used only if no lang file could be loaded. */
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
        registerBiome("minecraft:dark_forest", "тёмный лес", "тёмный лес",
                "dark forest", "dark woods", "roofed forest");
        registerBiome("minecraft:ocean", "океан", "ocean", "sea", "море", "моря", "океана", "океане");
        registerBiome("minecraft:warm_ocean", "тёплый океан", "тёплый океан",
                "warm ocean", "warm sea", "tropical ocean", "tropical sea",
                "тёплого океана", "тёплого океана", "тёплом океане", "тёплом океане");
        registerBiome("minecraft:cold_ocean", "холодный океан",
                "cold ocean", "cold sea", "холодного океана", "холодном океане");
        registerBiome("minecraft:deep_ocean", "глубокий океан",
                "deep ocean", "deep sea", "глубокого океана", "глубоком океане");
        registerBiome("minecraft:river", "река", "river", "stream", "creek", "реки", "реке");
        registerBiome("minecraft:badlands", "пустошь", "бэдлендс", "меса", "badlands", "mesa", "badland");
        registerBiome("minecraft:wooded_badlands", "лесистая пустошь",
                "wooded badlands", "wooded mesa", "forested badlands");
        registerBiome("minecraft:birch_forest", "берёзовый лес", "берёзовый лес",
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
        registerBiome("minecraft:frozen_ocean", "замёрзший океан", "замерзший океан",
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

        // ---- modifiers ----
        registerModifier("NEAR", "near", "nearby", "close", "beside", "next",
                "рядом", "около", "вблизи", "недалеко", "возле", "близко");
        registerModifier("IN", "in", "inside", "within", "в", "во", "внутри");
        registerModifier("SOME", "some", "several", "many", "multiple", "cluster",
                "несколько", "много", "куча", "группа", "скопление");
        registerModifier("FAR", "far", "distant", "away", "remote",
                "далеко", "вдали", "далеки", "поодаль");
        registerModifier("UNDER", "under", "beneath", "below", "underneath", "под", "снизу");
        registerModifier("NEVER", "never", "no", "not", "without", "нет", "не", "без", "никакого", "никаких");
        registerModifier("ONLY", "only", "just", "single", "only one",
                "только", "лишь", "одна", "один", "единственная", "единственная");
        registerModifier("BETWEEN", "between", "mid", "middle", "in range", "from",
                "между", "середина", "диапазон", "промежуток", "от");

        // ---- spawn triggers ----
        for (String w : new String[]{"spawn", "on", "на", "блок", "block", "onto", "встань", "стоять"}) {
            spawnTriggers.add(w.toLowerCase(Locale.ROOT).trim());
        }
    }

    private void registerModifier(String canonical, String... synonyms) {
        register(new Entry(canonical, Category.MODIFIER, null), synonyms);
    }

    private void registerSpawnTrigger(String... synonyms) {
        for (String s : synonyms) {
            spawnTriggers.add(s.toLowerCase(Locale.ROOT).trim());
        }
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
