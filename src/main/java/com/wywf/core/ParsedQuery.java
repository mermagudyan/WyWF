package com.wywf.core;

import java.util.*;

public final class ParsedQuery {

    public static final class Term {
        public final String canonical;
        public final KeywordDictionary.Category category;
        public final Modifier modifier;

        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier) {
            this.canonical = canonical;
            this.category = category;
            this.modifier = modifier == null ? Modifier.DEFAULT : modifier;
        }

        @Override public String toString() {
            String m = modifier == Modifier.DEFAULT ? "" : modifier.name().toLowerCase(Locale.ROOT) + " ";
            return m + canonical;
        }
    }

    private final String raw;
    private final List<Term> terms;
    private final List<String> structures;
    private final List<String> biomes;
    private final List<String> objects;

    public ParsedQuery(String raw, List<Term> terms) {
        this.raw = raw;
        this.terms = List.copyOf(terms);

        List<String> s = new ArrayList<>();
        List<String> b = new ArrayList<>();
        List<String> o = new ArrayList<>();
        for (Term t : this.terms) {
            switch (t.category) {
                case STRUCTURE -> s.add(t.canonical);
                case BIOME     -> b.add(t.canonical);
                case OBJECT    -> o.add(t.canonical);
            }
        }
        this.structures = List.copyOf(s);
        this.biomes     = List.copyOf(b);
        this.objects    = List.copyOf(o);
    }

    public String raw()              { return raw; }
    public List<Term> terms()        { return terms; }
    public List<String> structures() { return structures; }
    public List<String> biomes()     { return biomes; }
    public List<String> objects()    { return objects; }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    public Optional<String> primaryTarget() {
        if (!structures.isEmpty()) return Optional.of(structures.get(0));
        if (!biomes.isEmpty())     return Optional.of(biomes.get(0));
        if (!objects.isEmpty())    return Optional.of(objects.get(0));
        return Optional.empty();
    }

    @Override public String toString() {
        return "ParsedQuery{terms=" + terms + '}';
    }
}
