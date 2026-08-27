package com.wywf.core;

import java.util.*;

public final class QueryParser {

    private final KeywordDictionary dict;

    public QueryParser(KeywordDictionary dict) {
        this.dict = dict;
    }

    private static final Map<String, String> COMPOUND = buildCompound();

    private static Map<String, String> buildCompound() {
        Map<String, String> m = new HashMap<>();
        bind(m, "minecraft:plains",        "minecraft:village",  "minecraft:village_plains");
        bind(m, "minecraft:desert",        "minecraft:village",  "minecraft:village_desert");
        bind(m, "minecraft:savanna",       "minecraft:village",  "minecraft:village_savanna");
        bind(m, "minecraft:snowy_plains",   "minecraft:village",  "minecraft:village_snowy");
        bind(m, "minecraft:taiga",         "minecraft:village",  "minecraft:village_taiga");
        bind(m, "minecraft:desert",        "minecraft:desert_pyramid", "minecraft:desert_pyramid");
        bind(m, "minecraft:jungle",        "minecraft:jungle_temple",  "minecraft:jungle_temple");
        bind(m, "minecraft:bamboo_jungle",  "minecraft:jungle_temple",  "minecraft:jungle_temple");
        bind(m, "minecraft:swamp",         "minecraft:swamp_hut", "minecraft:swamp_hut");
        bind(m, "minecraft:dark_forest",    "minecraft:mansion",       "minecraft:mansion");
        bind(m, "minecraft:snowy_plains",  "minecraft:igloo", "minecraft:igloo");
        bind(m, "minecraft:snowy_taiga",   "minecraft:igloo", "minecraft:igloo");
        bind(m, "minecraft:snowy_slopes",  "minecraft:igloo", "minecraft:igloo");
        bind(m, "minecraft:ocean",         "minecraft:ocean_monument", "minecraft:ocean_monument");
        bind(m, "minecraft:ocean",         "minecraft:ocean_ruins", "minecraft:ocean_ruins");
        return Map.copyOf(m);
    }

    private static final Map<String, String> STRUCTURE_NATURAL_BIOME = buildNaturalBiomes();

    private static Map<String, String> buildNaturalBiomes() {
        Map<String, String> m = new HashMap<>();
        m.put("minecraft:mansion",        "minecraft:dark_forest");
        m.put("minecraft:desert_pyramid", "minecraft:desert");
        m.put("minecraft:jungle_temple",  "minecraft:jungle");
        m.put("minecraft:swamp_hut",      "minecraft:swamp");
        m.put("minecraft:ocean_monument", "minecraft:ocean");
        m.put("minecraft:ocean_ruins",    "minecraft:ocean");
        m.put("minecraft:fortress",       "minecraft:nether_wastes");
        m.put("minecraft:bastion",        "minecraft:nether_wastes");
        m.put("minecraft:end_city",       "minecraft:end");
        m.put("minecraft:village_desert", "minecraft:desert");
        m.put("minecraft:village_plains", "minecraft:plains");
        m.put("minecraft:village_taiga",  "minecraft:taiga");
        m.put("minecraft:village_savanna","minecraft:savanna");
        m.put("minecraft:village_snowy",  "minecraft:snowy_plains");
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
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
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
        int pendingSomeCount = 2;
        int pendingBetweenMin = 0;
        int pendingBetweenMax = 0;
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
                pendingSomeCount = 2;
                pendingBetweenMin = 0;
                pendingBetweenMax = 0;

                if (pending == Modifier.SOME) {
                    int numEnd = wordEnd;
                    while (numEnd < len && normalized.charAt(numEnd) == ' ') numEnd++;
                    int numStart = numEnd;
                    while (numEnd < len && Character.isDigit(normalized.charAt(numEnd))) numEnd++;
                    if (numEnd > numStart) {
                        String rawNum = normalized.substring(numStart, numEnd);
                        try {
                            pendingSomeCount = Integer.parseInt(rawNum);
                        } catch (NumberFormatException ex) {
                            ignored.add(rawNum + "(too large)");
                        }
                        wordEnd = numEnd;
                    }
                }

                if (pending == Modifier.BETWEEN) {
                    int p = wordEnd;
                    while (p < len && normalized.charAt(p) == ' ') p++;
                    int n1Start = p;
                    while (p < len && Character.isDigit(normalized.charAt(p))) p++;
                    if (p > n1Start) {
                        String rawMin = normalized.substring(n1Start, p);
                        try {
                            pendingBetweenMin = Integer.parseInt(rawMin);
                        } catch (NumberFormatException ex) {
                            pendingBetweenMin = 0;
                            ignored.add(rawMin + "(too large)");
                        }
                        while (p < len && normalized.charAt(p) == ' ') p++;
                        if (p < len && normalized.charAt(p) == '.') {
                            p++;
                            if (p < len && normalized.charAt(p) == '.') p++;
                            while (p < len && normalized.charAt(p) == ' ') p++;
                        } else if (p + 1 < len
                                && ((normalized.charAt(p) == 'd' && normalized.charAt(p + 1) == 'o')
                                 || (normalized.charAt(p) == 't' && normalized.charAt(p + 1) == 'o')
                                 || (normalized.charAt(p) == 'д' && normalized.charAt(p + 1) == 'о'))) {
                            p += 2;
                            while (p < len && normalized.charAt(p) == ' ') p++;
                        }
                        int n2Start = p;
                        while (p < len && Character.isDigit(normalized.charAt(p))) p++;
                        if (p > n2Start) {
                            String rawMax = normalized.substring(n2Start, p);
                            try {
                                pendingBetweenMax = Integer.parseInt(rawMax);
                            } catch (NumberFormatException ex) {
                                pendingBetweenMax = pendingBetweenMin;
                                ignored.add(rawMax + "(too large)");
                            }
                        } else {
                            pendingBetweenMax = pendingBetweenMin;
                        }
                        wordEnd = p;
                    }
                }

                i = wordEnd;
                continue;
            }

            if (dict.isSpawnTrigger(word)) {
                pendingSpawn = true;
                i = wordEnd;
                continue;
            }

            if (allDigits(word)) {
                int p = wordEnd;
                while (p < len && normalized.charAt(p) == ' ') p++;
                if (p < len && isSeparatorAt(normalized, p)) {
                    int sepLen = separatorLength(normalized, p);
                    p += sepLen;
                    while (p < len && normalized.charAt(p) == ' ') p++;
                    int n2Start = p;
                    while (p < len && Character.isDigit(normalized.charAt(p))) p++;
                    if (p > n2Start) {
                        String rawMax = normalized.substring(n2Start, p);
                        try {
                            pending = Modifier.BETWEEN;
                            pendingBetweenMin = Integer.parseInt(word);
                            pendingBetweenMax = Integer.parseInt(rawMax);
                            pendingSomeCount = 2;
                            i = p;
                            continue;
                        } catch (NumberFormatException ex) {
                            ignored.add(word + ".." + rawMax + "(too large)");
                        }
                    }
                }
            }

            int[] betweenSplit = splitBetweenWord(word);
            if (betweenSplit != null) {
                pending = Modifier.BETWEEN;
                pendingBetweenMin = betweenSplit[0];
                pendingBetweenMax = betweenSplit[1];
                pendingSomeCount = 2;
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
                            String dedupeKey = buildDedupeKey(canonical, KeywordDictionary.Category.SPAWN, pending, pendingSomeCount, pendingBetweenMin, pendingBetweenMax);
                            if (seen.add(dedupeKey)) {
                                if (pending == Modifier.SOME) {
                                    terms.add(new ParsedQuery.Term(canonical, e.category, pending, pendingSomeCount, 0, 0));
                                } else if (pending == Modifier.BETWEEN) {
                                    terms.add(new ParsedQuery.Term(canonical, e.category, pending, 2, pendingBetweenMin, pendingBetweenMax));
                                } else {
                                    terms.add(new ParsedQuery.Term(canonical, e.category, pending));
                                }
                            }
                        }
                    }
                    pendingSpawn = false;
                    pending = Modifier.DEFAULT;
                    pendingSomeCount = 2;
                    pendingBetweenMin = 0;
                    pendingBetweenMax = 0;
                    i += matched;
                    continue;
                } else {
                    // No block matched after spawn trigger — keep pendingSpawn for next word if still plausible,
                    // but if current word is clearly not a block prefix, drop the trigger to avoid stretching it
                    // over multiple unrelated words. We keep it for now and let the non-block path below handle it.
                }
            }

            int matched = dict.matchAt(normalized, i, out);
            if (matched > 0) {
                String canonical = out[0];
                if (canonical != null) {
                    KeywordDictionary.Entry e = dict.get(canonical);
                    if (e != null) {
                        String dedupeKey = buildDedupeKey(canonical, e.category, pending, pendingSomeCount, pendingBetweenMin, pendingBetweenMax);
                        if (seen.add(dedupeKey)) {
                            if (pending == Modifier.SOME) {
                                terms.add(new ParsedQuery.Term(canonical, e.category, pending,
                                        pendingSomeCount, 0, 0));
                            } else if (pending == Modifier.BETWEEN) {
                                terms.add(new ParsedQuery.Term(canonical, e.category, pending,
                                        2, pendingBetweenMin, pendingBetweenMax));
                            } else {
                                terms.add(new ParsedQuery.Term(canonical, e.category, pending));
                            }
                        }
                    }
                }
                pending = Modifier.DEFAULT;
                pendingSomeCount = 2;
                pendingBetweenMin = 0;
                pendingBetweenMax = 0;
                pendingSpawn = false;
                i += matched;
            } else {
                ignored.add(word);
                pending = Modifier.DEFAULT;
                pendingSomeCount = 2;
                pendingBetweenMin = 0;
                pendingBetweenMax = 0;
                pendingSpawn = false;
                i = wordEnd;
            }
        }

        // Trailing modifier without a following term: treat as stray modifier
        // instead of silently rewriting history (which bypasses dedupe and
        // violates user intent, e.g. "village near" -> "near village").
        if (pending != Modifier.DEFAULT && !terms.isEmpty()) {
            ignored.add(pending.name().toLowerCase(Locale.ROOT));
        }

        List<ParsedQuery.Term> bound = bindCompound(terms);
        List<ParsedQuery.Term> enriched = addImplicitBiomes(bound);
        return new ParsedQuery(input, enriched, ignored);
    }

    private List<ParsedQuery.Term> bindCompound(List<ParsedQuery.Term> terms) {
        List<ParsedQuery.Term> out = new ArrayList<>(terms.size());
        int i = 0;
        while (i < terms.size()) {
            ParsedQuery.Term a = terms.get(i);
            ParsedQuery.Term b = (i + 1 < terms.size()) ? terms.get(i + 1) : null;
            if (b != null) {
                String variant = null;
                Modifier mod = null;
                ParsedQuery.Term biomeTerm = null;
                if (a.category == KeywordDictionary.Category.BIOME
                        && b.category == KeywordDictionary.Category.STRUCTURE) {
                    variant = COMPOUND.get(a.canonical + "\u0000" + b.canonical);
                    mod = b.modifier;
                    biomeTerm = a;
                } else if (b.category == KeywordDictionary.Category.BIOME
                        && a.category == KeywordDictionary.Category.STRUCTURE) {
                    variant = COMPOUND.get(b.canonical + "\u0000" + a.canonical);
                    mod = a.modifier;
                    biomeTerm = b;
                }
                if (variant != null) {
                    KeywordDictionary.Entry e = dict.get(variant);
                    Modifier useMod = (mod == null || mod == Modifier.DEFAULT) ? a.modifier : mod;
                    if (e != null) {
                        out.add(new ParsedQuery.Term(variant, KeywordDictionary.Category.STRUCTURE, useMod));
                        if (biomeTerm != null) out.add(biomeTerm);
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

    private List<ParsedQuery.Term> addImplicitBiomes(List<ParsedQuery.Term> terms) {
        Set<String> biomesInQuery = new HashSet<>();
        for (ParsedQuery.Term t : terms) {
            if (t.category == KeywordDictionary.Category.BIOME) biomesInQuery.add(t.canonical);
        }
        if (!biomesInQuery.isEmpty()) return terms;

        List<ParsedQuery.Term> out = new ArrayList<>(terms);
        for (ParsedQuery.Term t : terms) {
            if (t.category == KeywordDictionary.Category.STRUCTURE
                    && t.modifier != Modifier.NEVER && t.modifier != Modifier.ONLY) {
                String biome = STRUCTURE_NATURAL_BIOME.get(t.canonical);
                if (biome != null && !biomesInQuery.contains(biome)) {
                    out.add(new ParsedQuery.Term(biome, KeywordDictionary.Category.BIOME, Modifier.DEFAULT));
                    biomesInQuery.add(biome);
                }
            }
        }
        return out;
    }

    private static String buildDedupeKey(String canonical, KeywordDictionary.Category category,
                                     Modifier mod, int someCount, int betweenMin, int betweenMax) {
        String base = canonical + "#" + category.name() + "#" + mod.name();
        if (mod == Modifier.SOME) return base + "#" + someCount;
        if (mod == Modifier.BETWEEN) return base + "#" + betweenMin + ".." + betweenMax;
        return base;
    }

    public boolean isQuery(String input) {
        return !parse(input).isEmpty();
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isSeparatorAt(String text, int pos) {
        if (pos >= text.length()) return false;
        char c = text.charAt(pos);
        if (c == '.') return true;
        if (pos + 1 < text.length()) {
            if (c == 'д' && text.charAt(pos + 1) == 'о') return true;
            if (c == 'd' && text.charAt(pos + 1) == 'o') return true;
            if (c == 't' && text.charAt(pos + 1) == 'o') return true;
        }
        return false;
    }

    private static int separatorLength(String text, int pos) {
        if (pos >= text.length()) return 0;
        char c = text.charAt(pos);
        if (c == '.') {
            if (pos + 1 < text.length() && text.charAt(pos + 1) == '.') return 2;
            return 1;
        }
        if (pos + 1 < text.length()) {
            if (c == 'д' && text.charAt(pos + 1) == 'о') return 2;
            if (c == 'd' && text.charAt(pos + 1) == 'o') return 2;
            if (c == 't' && text.charAt(pos + 1) == 'o') return 2;
        }
        return 0;
    }

    private static int[] splitBetweenWord(String word) {
        String[] seps = {"..", "до", "do", "to"};
        for (String sep : seps) {
            int idx = word.indexOf(sep);
            if (idx > 0 && idx + sep.length() < word.length()) {
                String left = word.substring(0, idx);
                String right = word.substring(idx + sep.length());
                if (allDigits(left) && allDigits(right)) {
                    try {
                        return new int[]{Integer.parseInt(left), Integer.parseInt(right)};
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
