package com.wywf.core;

import java.util.*;

public final class ParsedQuery {

    public static final class Term {
        public final String canonical;
        public final KeywordDictionary.Category category;
        public final Modifier modifier;
        public final int someCount;
        public final int betweenMin;
        public final int betweenMax;

        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier) {
            this(canonical, category, modifier, 2, 0, 0);
        }

        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier,
                    int someCount, int betweenMin, int betweenMax) {
            this.canonical = canonical;
            this.category = category;
            this.modifier = modifier == null ? Modifier.DEFAULT : modifier;
            this.someCount = someCount < 2 ? 2 : someCount;
            this.betweenMin = Math.max(0, betweenMin);
            this.betweenMax = Math.max(betweenMin, betweenMax);
        }

        @Override public String toString() {
            String m = modifier == Modifier.DEFAULT ? "" : modifier.name().toLowerCase(Locale.ROOT) + " ";
            String extra = "";
            if (modifier == Modifier.SOME) {
                extra = someCount + " ";
            } else if (modifier == Modifier.BETWEEN) {
                extra = betweenMin + ".." + betweenMax + " ";
            }
            return m + extra + canonical;
        }
    }

    private final String raw;
    private final List<Term> terms;
    private final List<String> structures;
    private final List<String> biomes;
    private final List<String> objects;
    private final List<String> spawns;
    private final List<String> ignoredWords;

    public ParsedQuery(String raw, List<Term> terms) {
        this(raw, terms, List.of());
    }

    public ParsedQuery(String raw, List<Term> terms, List<String> ignoredWords) {
        this.raw = raw;
        this.terms = List.copyOf(terms);
        this.ignoredWords = List.copyOf(ignoredWords);

        List<String> s = new ArrayList<>();
        List<String> b = new ArrayList<>();
        List<String> o = new ArrayList<>();
        List<String> p = new ArrayList<>();
        for (Term t : this.terms) {
            switch (t.category) {
                case STRUCTURE -> s.add(t.canonical);
                case BIOME     -> b.add(t.canonical);
                case OBJECT    -> o.add(t.canonical);
                case SPAWN     -> p.add(t.canonical);
            }
        }
        this.structures = List.copyOf(s);
        this.biomes     = List.copyOf(b);
        this.objects    = List.copyOf(o);
        this.spawns     = List.copyOf(p);
    }

    public String raw()              { return raw; }
    public List<Term> terms()        { return terms; }
    public List<String> structures() { return structures; }
    public List<String> biomes()     { return biomes; }
    public List<String> objects()    { return objects; }
    public List<String> spawns()     { return spawns; }
    public List<String> ignoredWords() { return ignoredWords; }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    public Optional<String> primaryTarget() {
        if (!structures.isEmpty()) return Optional.of(structures.get(0));
        if (!biomes.isEmpty())     return Optional.of(biomes.get(0));
        if (!spawns.isEmpty())     return Optional.of(spawns.get(0));
        if (!objects.isEmpty())    return Optional.of(objects.get(0));
        return Optional.empty();
    }

    @Override public String toString() {
        return "ParsedQuery{terms=" + terms + '}';
    }
}
