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

    private static void bind(Map<String, String> m, String biome, String structure, String variant) {
        m.put(biome + "\u0000" + structure, variant);
    }

    public ParsedQuery parse(String input) {
        if (input != null && input.strip().matches("[+-]?[0-9]+"))
            return new ParsedQuery(input, List.of());
        if(input!=null&&input.stripLeading().matches("(?is)^(ftp|findthisplace)\\b.*")) {
            var m=java.util.regex.Pattern.compile("(?is)^\\s*(?:ftp|findthisplace)\\s+(-?\\d+)\\s+around\\s+(-?\\d+)\\s+(-?\\d+)\\s+within\\s+(\\d+|world)\\s+step\\s+(\\d+)\\s*:\\s*(.+)$").matcher(input);
            if(!m.matches())return new ParsedQuery(input,List.of(),List.of(),null,"FTP seed around X Z within radius step spacing: conditions");
            try {
                long seed = Long.parseLong(m.group(1));
                int x = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3)), step = Integer.parseInt(m.group(5));
                var place = m.group(4).equalsIgnoreCase("world") ? PlaceSearch.wholeSeed(seed, x, z, step)
                        : new PlaceSearch(seed, x, z, Integer.parseInt(m.group(4)), step);
                var q=QueryCompiler.compile(m.group(6), this::parseTerms);
                if(q.terms().stream().anyMatch(t->t.category==KeywordDictionary.Category.SPAWN))
                    return new ParsedQuery(input,q.terms(),q.ignoredWords(),q.logic(),"FTP searches regions; spawn conditions require a world search");
                return new ParsedQuery(input,q.terms(),q.ignoredWords(),q.logic(),q.error()).withPlace(place);
            }catch(IllegalArgumentException error){return new ParsedQuery(input,List.of(),List.of(),null,error.getMessage());}
        }
        return QueryCompiler.compile(input, this::parseTerms);
    }

    private ParsedQuery parseTerms(String input) {
        return parseTerms(input, true);
    }

    private ParsedQuery parseTerms(String input, boolean allowLoot) {
        if (input == null || input.isBlank()) {
            return new ParsedQuery("", List.of());
        }

        StructureTags tags=StructureTags.NONE;
        String original=input;
        var tagMatch=java.util.regex.Pattern.compile("(?i)\\s+tags\\s+([a-z0-9_,]+)(?=\\s+with\\b|$)").matcher(input.strip());
        if(tagMatch.find()){
            try{tags=StructureTags.parse(tagMatch.group(1));}
            catch(IllegalArgumentException error){return new ParsedQuery(original,List.of(),List.of(),null,error.getMessage());}
            input=input.strip().substring(0,tagMatch.start())+input.strip().substring(tagMatch.end());
        }else if(input.matches("(?is).*\\btags\\b.*"))return new ParsedQuery(original,List.of(),List.of(),null,"Invalid structure tags");
        String prefix = LootConstraint.targetPrefix(input);
        ParsedQuery prefixQuery = !prefix.equals(input) && allowLoot ? parseTerms(prefix, false) : null;
        LootConstraint.Split loot;
        try { loot = prefixQuery != null && !prefixQuery.isEmpty() ? LootConstraint.split(input) : new LootConstraint.Split(input, null); }
        catch (IllegalArgumentException error) {
            return new ParsedQuery(input, prefixQuery == null ? List.of() : prefixQuery.terms(), List.of(), null, error.getMessage());
        }
        boolean pinned=loot.target().stripLeading().matches("(?is)^keep\\b.*");
        String text = loot.target().replaceFirst("(?is)^\\s*keep\\s+", "").toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':') {
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

            if (word.equals("within")) {
                int start = skipSpaces(normalized, wordEnd);
                int end = numberEnd(normalized, start);
                if (end > start) {
                    try {
                        pendingBetweenMax = distanceNumber(normalized, start, end);
                        if (pendingBetweenMax == 0) throw new IllegalArgumentException("Radius must be at least 1 block");
                    } catch (IllegalArgumentException error) {
                        return new ParsedQuery(input, terms, ignored, null, error.getMessage());
                    }
                    pendingBetweenMin = 0;
                    if (!pending.counted()) pending = Modifier.BETWEEN;
                    i = end;
                    continue;
                }
            }
            String modName = dict.getModifier(word);
            if (modName == null) {
                try { modName = Modifier.valueOf(word.toUpperCase(Locale.ROOT)).name(); }
                catch (IllegalArgumentException ignoredModifier) { }
            }
            if (modName != null) {
                pending = Modifier.valueOf(modName);
                pendingSomeCount = 2;
                pendingBetweenMin = 0;
                pendingBetweenMax = 0;

                if (pending == Modifier.SOME || pending.counted()) {
                    int numEnd = wordEnd;
                    while (numEnd < len && normalized.charAt(numEnd) == ' ') numEnd++;
                    int numStart = numEnd;
                    while (numEnd < len && Character.isDigit(normalized.charAt(numEnd))) numEnd++;
                    if (numEnd > numStart) {
                        String rawNum = normalized.substring(numStart, numEnd);
                        try {
                            pendingSomeCount = Integer.parseInt(rawNum);
                        } catch (NumberFormatException ex) {
                            if (pending.counted()) return new ParsedQuery(input, terms, ignored, null, "Invalid count");
                            ignored.add(rawNum + "(too large)");
                        }
                        if (pending.counted() && (pendingSomeCount < 0 || pendingSomeCount > 64))
                            return new ParsedQuery(input, terms, ignored, null, "Count must be 0..64");
                        wordEnd = numEnd;
                    } else if (pending.counted()) return new ParsedQuery(input, terms, ignored, null, "Missing count");
                }

                if (pending == Modifier.BETWEEN) {
                    try {
                        var range = distanceRange(normalized, skipSpaces(normalized, wordEnd), true);
                        pendingBetweenMin = range.min();
                        pendingBetweenMax = range.max();
                        wordEnd = range.end();
                    } catch (IllegalArgumentException error) {
                        return new ParsedQuery(input, terms, ignored, null, error.getMessage());
                    }
                }

                i = wordEnd;
                continue;
            }

            if (dict.isSpawnTrigger(word) || word.equals("spawn")) {
                pendingSpawn = true;
                i = wordEnd;
                continue;
            }

            if (Character.isDigit(c) || c == '-') {
                try {
                    var range = distanceRange(normalized, i, false);
                    if (range != null) {
                        pending = Modifier.BETWEEN;
                        pendingBetweenMin = range.min();
                        pendingBetweenMax = range.max();
                        pendingSomeCount = 2;
                        i = range.end();
                        continue;
                    }
                } catch (IllegalArgumentException error) {
                    return new ParsedQuery(input, terms, ignored, null, error.getMessage());
                }
            }

            if (allDigits(word)) {
                try { pendingSomeCount = Integer.parseInt(word); }
                catch (NumberFormatException error) { return new ParsedQuery(input, terms, ignored, null, "Invalid count"); }
                if (pendingSomeCount > 64) return new ParsedQuery(input, terms, ignored, null, "Count must be 0..64");
                pending = Modifier.EXACTLY; i = wordEnd; continue;
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
                                if (pending == Modifier.SOME || pending.counted()) {
                                    terms.add(new ParsedQuery.Term(canonical, e.category, pending, pendingSomeCount, pendingBetweenMin, pendingBetweenMax));
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
                            if (pending == Modifier.SOME || pending.counted()) {
                                terms.add(new ParsedQuery.Term(canonical, e.category, pending,
                                        pendingSomeCount, pendingBetweenMin, pendingBetweenMax));
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
                i = wordEnd;
            }
        }

        // A trailing modifier has no target
        if (pending != Modifier.DEFAULT && !terms.isEmpty()) {
            ignored.add(pending.name().toLowerCase(Locale.ROOT));
        }

        List<ParsedQuery.Term> bound = bindCompound(terms).stream().map(t -> t.canonical.equals("minecraft:smithy")
                ? new ParsedQuery.Term(t.canonical, t.category, t.modifier, t.someCount, t.betweenMin, t.betweenMax,
                new LootConstraint(List.of(), "weaponsmith")) : t).toList();
        for (var term : bound) {
            if (!term.modifier.counted() || term.category == KeywordDictionary.Category.STRUCTURE) continue;
            if (term.category != KeywordDictionary.Category.BIOME || term.modifier != Modifier.AT_LEAST
                    || term.someCount < 1 || term.betweenMax < 1 || term.betweenMax > 4096) {
                return new ParsedQuery(input, bound, ignored, null, "Biome counts require at_least and a radius of 1..4096 blocks");
            }
        }
        if (loot.loot() != null) {
            var targets = bound.stream().filter(t -> t.category == KeywordDictionary.Category.STRUCTURE).toList();
            if (targets.isEmpty()) targets = bound.stream().filter(t -> t.category == KeywordDictionary.Category.BIOME).toList();
            if (targets.size() != 1 || targets.getFirst().modifier.counted()
                    || Set.of(Modifier.NEVER, Modifier.ONLY, Modifier.SOME, Modifier.UNDER).contains(targets.getFirst().modifier))
                return new ParsedQuery(input, bound, ignored, null, "Attach loot to one biome or structure with a distance or presence condition");
            var target = targets.getFirst();
            if (target.category == KeywordDictionary.Category.BIOME && loot.loot().items().isEmpty())
                return new ParsedQuery(input, bound, ignored, null, "Choose at least one item to find in the biome");
            if (target.category == KeywordDictionary.Category.STRUCTURE && !LootContainers.belongs(target.canonical, loot.loot().container()))
                return new ParsedQuery(input, bound, ignored, null, "This container does not belong to the selected structure");
            bound = bound.stream().map(t -> t == target ? t.withLoot(loot.loot()) : t).toList();
        }
        if(!tags.empty()){
            var targets=bound.stream().filter(t->t.category==KeywordDictionary.Category.STRUCTURE).toList();
            if(targets.size()!=1||!tags.supports(targets.getFirst().canonical))
                return new ParsedQuery(original,bound,ignored,null,"Attach supported tags to one structure");
            var target=targets.getFirst();var selected=tags;
            bound=bound.stream().map(t->t==target?t.withTags(selected):t).toList();
        }
        return new ParsedQuery(input, pinned ? bound.stream().map(t -> t.withPinned(true)).toList() : bound, ignored);
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
                if (variant != null && !(a.modifier != Modifier.DEFAULT && b.modifier != Modifier.DEFAULT)) {
                    KeywordDictionary.Entry e = dict.get(variant);
                    Modifier useMod = (mod == null || mod == Modifier.DEFAULT) ? a.modifier : mod;
                    ParsedQuery.Term bounds = b.modifier != Modifier.DEFAULT ? b : a;
                    if (e != null) {
                        out.add(new ParsedQuery.Term(variant, KeywordDictionary.Category.STRUCTURE, useMod,
                                bounds.someCount, bounds.betweenMin, bounds.betweenMax));
                        if (biomeTerm != null && !useMod.counted()) out.add(biomeTerm);
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

    private static String buildDedupeKey(String canonical, KeywordDictionary.Category category,
                                     Modifier mod, int someCount, int betweenMin, int betweenMax) {
        String base = canonical + "#" + category.name() + "#" + mod.name();
        if (mod == Modifier.SOME || mod.counted()) return base + "#" + someCount + "#" + betweenMax;
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

    private record DistanceRange(int min, int max, int end) {}

    private static DistanceRange distanceRange(String text, int start, boolean explicit) {
        int firstEnd = numberEnd(text, start);
        if (firstEnd == start) {
            if (explicit) throw new IllegalArgumentException("Enter a distance after BETWEEN");
            return null;
        }

        int separatorStart = skipSpaces(text, firstEnd);
        int separatorSize = separatorLength(text, separatorStart);
        if (separatorSize == 0 && !explicit) return null;

        int secondStart = skipSpaces(text, separatorStart + separatorSize);
        int secondEnd = numberEnd(text, secondStart);
        if (secondEnd == secondStart && separatorSize != 0)
            throw new IllegalArgumentException("Enter the end of the distance range");

        int min = distanceNumber(text, start, firstEnd);
        int max = secondEnd == secondStart ? min : distanceNumber(text, secondStart, secondEnd);
        TargetDistance.checkRange(min, max);
        return new DistanceRange(min, max, secondEnd == secondStart ? firstEnd : secondEnd);
    }

    private static int distanceNumber(String text, int start, int end) {
        final int distance;
        try {
            distance = Integer.parseInt(text, start, end, 10);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Distance must be between 0 and " + TargetDistance.MAX_RADIUS_BLOCKS + " blocks");
        }
        TargetDistance.checkRange(distance, distance);
        return distance;
    }

    private static int skipSpaces(String text, int start) {
        int end = start;
        while (end < text.length() && text.charAt(end) == ' ') end++;
        return end;
    }

    private static int numberEnd(String text, int start) {
        int digits = start;
        if (digits < text.length() && text.charAt(digits) == '-') digits++;
        int end = digits;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        return end == digits ? start : end;
    }

    private static int separatorLength(String text, int pos) {
        if (pos >= text.length()) return 0;
        if (text.charAt(pos) == '.') return text.startsWith("..", pos) ? 2 : 1;
        if (text.startsWith("до", pos) || text.startsWith("do", pos) || text.startsWith("to", pos)) {
            int next = pos + 2;
            if (next == text.length() || !Character.isLetter(text.charAt(next))) return 2;
        }
        return 0;
    }
}
