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
        public final LootConstraint loot;
        public final boolean pinned;
        public final StructureTags tags;

        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier) {
            this(canonical, category, modifier, 2, 0, 0);
        }

        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier,
                    int someCount, int betweenMin, int betweenMax) {
            this(canonical, category, modifier, someCount, betweenMin, betweenMax, null);
        }

        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier,
                    int someCount, int betweenMin, int betweenMax, LootConstraint loot) {
            this(canonical,category,modifier,someCount,betweenMin,betweenMax,loot,false);
        }
        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier,
                    int someCount, int betweenMin, int betweenMax, LootConstraint loot, boolean pinned) {
            this(canonical,category,modifier,someCount,betweenMin,betweenMax,loot,pinned,StructureTags.NONE);
        }
        public Term(String canonical, KeywordDictionary.Category category, Modifier modifier, int someCount, int betweenMin,
                    int betweenMax, LootConstraint loot, boolean pinned, StructureTags tags) {
            this.tags=Objects.requireNonNull(tags);
            if(!tags.supports(canonical))throw new IllegalArgumentException("Tags are not supported by this target");
            this.pinned=pinned;
            this.loot = loot;
            this.canonical = canonical;
            this.category = category;
            this.modifier = modifier == null ? Modifier.DEFAULT : modifier;
            this.someCount = this.modifier.counted() ? Math.max(0, someCount) : Math.max(2, someCount);
            if (this.modifier == Modifier.BETWEEN || this.modifier.counted())
                TargetDistance.checkRange(betweenMin, betweenMax);
            this.betweenMin = Math.max(0, betweenMin);
            this.betweenMax = Math.max(betweenMin, betweenMax);
        }

        public boolean biomeRegionCount() {
            return category == KeywordDictionary.Category.BIOME && modifier == Modifier.AT_LEAST;
        }

        public boolean biomeLoot() {
            return category == KeywordDictionary.Category.BIOME && loot != null;
        }

        public Term withLoot(LootConstraint constraint) {
            return new Term(canonical, category, modifier, someCount, betweenMin, betweenMax, constraint,pinned,tags);
        }

        public Term withPinned(boolean value){return new Term(canonical,category,modifier,someCount,betweenMin,betweenMax,loot,value,tags);}
        public Term withTags(StructureTags value){return new Term(canonical,category,modifier,someCount,betweenMin,betweenMax,loot,pinned,value);}
        @Override public String toString() { return (pinned ? "keep " : "")+baseString() + tags.suffix() + (loot == null ? "" : loot.suffix()); }
        private String baseString() {
            String m = modifier == Modifier.DEFAULT ? "" : modifier.name().toLowerCase(Locale.ROOT) + " ";
            String extra = "";
            if (modifier.counted()) return m + someCount + " within " + (betweenMax > 0 ? betweenMax : 500) + " " + canonical;
            if (modifier == Modifier.SOME) {
                extra = someCount + " ";
            } else if (modifier == Modifier.BETWEEN) {
                extra = betweenMin + ".." + betweenMax + " ";
            }
            return m + extra + canonical;
        }
    }

    private boolean estimated;
    public boolean estimated(){return estimated;}
    public ParsedQuery estimate(){var q=new ParsedQuery(raw,terms,ignoredWords,logic,error).withPlace(place);q.estimated=true;return q;}
    private PlaceSearch place;
    public PlaceSearch place(){return place;}
    public ParsedQuery withPlace(PlaceSearch value){place=value;return this;}
    public String expression(){return normalizedExpression();}
    private final String raw;
    private final QueryLogic.Node logic;
    private final String error;
    public QueryLogic.Node logic() { return logic; }
    public boolean hasLogic() { return logic != null; }
    public boolean hasAlternatives() { return logic != null && !QueryLogic.conjunction(logic); }
    public String error() { return error; }
    public String normalized(){return (place==null ? "" : place.prefix())+normalizedExpression();}
    private String normalizedExpression() {
        if (hasLogic()) return QueryLogic.render(logic);
        if (!terms.isEmpty() && terms.stream().anyMatch(t -> t.loot != null || !t.tags.empty())) {
            QueryLogic.Node node = new QueryLogic.Leaf(terms.getFirst());
            for (int i = 1; i < terms.size(); i++) node = new QueryLogic.Binary(QueryLogic.Op.AND, node, new QueryLogic.Leaf(terms.get(i)));
            return QueryLogic.render(node);
        }
        return QueryAdvisor.serialize(terms);
    }
    private final List<Term> terms;
    private final List<String> structures;
    private final List<String> biomes;
    private final List<String> objects;
    private final List<String> spawns;
    private final List<String> ignoredWords;

    public ParsedQuery(String raw, List<Term> terms) {
        this(raw, terms, List.of());
    }

    public ParsedQuery(String raw, List<Term> terms, List<String> ignoredWords) { this(raw, terms, ignoredWords, null, null); }

    public ParsedQuery(String raw, List<Term> terms, List<String> ignoredWords, QueryLogic.Node logic, String error) {
        this.logic = logic; this.error = error;
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
