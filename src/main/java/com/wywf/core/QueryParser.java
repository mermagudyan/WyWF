package com.wywf.core;

import java.util.*;

public final class QueryParser {

    private final KeywordDictionary dict;

    public QueryParser(KeywordDictionary dict) {
        this.dict = dict;
    }

    /**
     * Binds a preceding biome word to a following structure word so
     * "plains village" -> "minecraft:village_plains" and "desert village" ->
     * "minecraft:village_desert", instead of two independent terms that match ANY
     * village. Key = {@code biomeCanonical + "\u0000" + structureCanonical},
     * value = the specific bound structure variant canonical.
     */
    private static final Map<String, String> COMPOUND = buildCompound();

    private static Map<String, String> buildCompound() {
        Map<String, String> m = new HashMap<>();
        bind(m, "minecraft:plains",        "minecraft:village",  "minecraft:village_plains");
        bind(m, "minecraft:desert",        "minecraft:village",  "minecraft:village_desert");
        bind(m, "minecraft:savanna",       "minecraft:village",  "minecraft:village_savanna");
        bind(m, "minecraft:snowy_plains",   "minecraft:village",  "minecraft:village_snowy");
        bind(m, "minecraft:taiga",         "minecraft:village",  "minecraft:village_taiga");
        bind(m, "minecraft:desert",        "minecraft:desert_pyramid", "minecraft:desert_pyramid");
        bind(m, "minecraft:jungle",        "minecraft:jungle_temple",  "minecraft:jungle_pyramid");
        bind(m, "minecraft:bamboo_jungle",  "minecraft:jungle_temple",  "minecraft:jungle_pyramid");
        bind(m, "minecraft:swamp",         "minecraft:swamp_hut", "minecraft:swamp_hut");
        bind(m, "minecraft:dark_forest",    "minecraft:mansion",       "minecraft:mansion");
        bind(m, "minecraft:snowy_plains",  "minecraft:igloo", "minecraft:igloo");
        bind(m, "minecraft:snowy_taiga",   "minecraft:igloo", "minecraft:igloo");
        bind(m, "minecraft:snowy_slopes",  "minecraft:igloo", "minecraft:igloo");
        bind(m, "minecraft:ocean",         "minecraft:ocean_monument", "minecraft:ocean_monument");
        bind(m, "minecraft:ocean",         "minecraft:ocean_ruins", "minecraft:ocean_ruin_cold");
        bind(m, "minecraft:nether",        "minecraft:fortress", "minecraft:fortress");
        bind(m, "minecraft:nether",        "minecraft:bastion",  "minecraft:bastion");
        bind(m, "minecraft:end",           "minecraft:end_city", "minecraft:end_city");
        return Map.copyOf(m);
    }

    private static void bind(Map<String, String> m, String biome, String structure, String variant) {
        m.put(biome + "\u0000" + structure, variant);
    }

    public ParsedQuery parse(String input) {
        if (input == null || input.isBlank()) {
            return new ParsedQuery("", List.of());
        }

        String text = input.toLowerCase(Locale.ROOT);

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

        List<ParsedQuery.Term> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> ignored = new ArrayList<>();

        int i = 0;
        int len = normalized.length();
        String[] out = new String[1];
        Modifier pending = Modifier.DEFAULT;
        boolean pendingSpawn = false;

        while (i < len) {
            char c = normalized.charAt(i);
            if (c == ' ') { i++; continue; }

            int wordEnd = i;
            while (wordEnd < len && normalized.charAt(wordEnd) != ' ') wordEnd++;
            String word = normalized.substring(i, wordEnd);

            String modName = dict.getModifier(word);
            if (modName != null) {
                pending = Modifier.valueOf(modName);
                i = wordEnd;
                continue;
            }

            if (dict.isSpawnTrigger(word)) {
                pendingSpawn = true;
                i = wordEnd;
                continue;
            }

            if (pendingSpawn) {
                int matched = dict.matchBlockAt(normalized, i, out);
                if (matched > 0) {
                    String canonical = out[0];
                    if (canonical != null) {
                        KeywordDictionary.Entry e = dict.get(canonical);
                        if (e != null && e.category == KeywordDictionary.Category.SPAWN) {
                            String dedupeKey = canonical + "#SPAWN";
                            if (seen.add(dedupeKey)) {
                                terms.add(new ParsedQuery.Term(canonical, e.category, Modifier.DEFAULT));
                            }
                        }
                    }
                    pendingSpawn = false;
                    pending = Modifier.DEFAULT;
                    i += matched;
                    continue;
                }
            }

            int matched = dict.matchAt(normalized, i, out);
            if (matched > 0) {
                String canonical = out[0];
                if (canonical != null) {
                    KeywordDictionary.Entry e = dict.get(canonical);
                    if (e != null) {
                        String dedupeKey = canonical + "#" + pending.name();
                        if (seen.add(dedupeKey)) {
                            terms.add(new ParsedQuery.Term(canonical, e.category, pending));
                        }
                    }
                }
                pending = Modifier.DEFAULT;
                pendingSpawn = false;
                i += matched;
            } else {
                ignored.add(word);
                i = wordEnd;
            }
        }

        List<ParsedQuery.Term> bound = bindCompound(terms);
        return new ParsedQuery(input, bound, ignored);
    }

    /**
     * Merges a biome term immediately followed by a structure term (or vice-versa)
     * into ONE structure term whose canonical is the specific variant bound by
     * {@link #COMPOUND} (e.g. "plains village" -> "minecraft:village_plains").
     * The bound term keeps the structure's modifier (or the biome's if the structure
     * had none). If no binding exists, both terms are kept as-is.
     */
    private List<ParsedQuery.Term> bindCompound(List<ParsedQuery.Term> terms) {
        List<ParsedQuery.Term> out = new ArrayList<>(terms.size());
        int i = 0;
        while (i < terms.size()) {
            ParsedQuery.Term a = terms.get(i);
            ParsedQuery.Term b = (i + 1 < terms.size()) ? terms.get(i + 1) : null;
            if (b != null) {
                String variant = null;
                Modifier mod = null;
                if (a.category == KeywordDictionary.Category.BIOME
                        && b.category == KeywordDictionary.Category.STRUCTURE) {
                    variant = COMPOUND.get(a.canonical + "\u0000" + b.canonical);
                    mod = b.modifier;
                } else if (b.category == KeywordDictionary.Category.BIOME
                        && a.category == KeywordDictionary.Category.STRUCTURE) {
                    variant = COMPOUND.get(b.canonical + "\u0000" + a.canonical);
                    mod = a.modifier;
                }
                if (variant != null) {
                    KeywordDictionary.Entry e = dict.get(variant);
                    Modifier useMod = (mod == null || mod == Modifier.DEFAULT) ? a.modifier : mod;
                    if (e != null) {
                        out.add(new ParsedQuery.Term(variant, KeywordDictionary.Category.STRUCTURE, useMod));
                        i += 2;
                        continue;
                    }
                }
            }
            out.add(a);
            i++;
        }
        return out;
    }

    public boolean isQuery(String input) {
        return !parse(input).isEmpty();
    }
}
