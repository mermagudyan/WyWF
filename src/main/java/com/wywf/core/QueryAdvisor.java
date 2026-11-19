package com.wywf.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class QueryAdvisor {
    public enum Kind { WIDEN, REDUCE_COUNT, DROP, CONFLICT, CAVE, STRONGHOLD }
    public record Advice(Kind kind, String target, String before, String after, String query) {}
    public record Report(List<Advice> advice, boolean reviewBeforeSearch, boolean conflict) {}
    private static final Set<String> CAVES = Set.of("minecraft:deep_dark", "minecraft:lush_caves",
            "minecraft:dripstone_caves", "minecraft:sulfur_caves");

    public static Report inspect(ParsedQuery query, SearchConfig config) {
        if (query.hasAlternatives() || query.error() != null) return new Report(List.of(), false, false);
        List<Advice> suggestions = new ArrayList<>();
        var proof=QueryConflicts.find(query,config);
        boolean conflict=proof.isPresent();
        if (!query.ignoredWords().isEmpty()) return new Report(List.of(), false, false);
        List<ParsedQuery.Term> terms = query.terms();
        if(query.hasLogic()&&!QueryLogic.conjunction(query.logic()))return new Report(List.of(),true,false);
        for (int i = 0; i < terms.size(); i++) {
            var term = terms.get(i);
            if(term.pinned)continue;
            if(proof.isPresent()&&proof.get().conditions().contains(term))add(suggestions,terms,i,null,Kind.CONFLICT);
            if (term.modifier == Modifier.UNDER && CAVES.contains(term.canonical)) {
                add(suggestions, terms, i, range(term, 0, 200), Kind.CAVE);
            }
            if (term.canonical.equals("minecraft:stronghold") && config.searchCenter() == SearchConfig.SearchCenter.ORIGIN
                    && max(term, config) > 0 && max(term, config) < 1024) {
                add(suggestions, terms, i, range(term, 0, TargetDistance.MAX_RADIUS_BLOCKS), Kind.STRONGHOLD);
            }
        }
        for (int i = 0; i < terms.size() && suggestions.size() < 8; i++) {
            var term = terms.get(i);
            if(term.pinned)continue;
            if (term.modifier == Modifier.NEVER || term.modifier.counted() || term.category == KeywordDictionary.Category.SPAWN) continue;
            int radius = max(term, config);
            if (radius > 0 && radius < TargetDistance.MAX_RADIUS_BLOCKS
                    && term.modifier != Modifier.SOME && term.modifier != Modifier.ONLY) {
                int minimum = term.modifier == Modifier.BETWEEN ? term.betweenMin : term.modifier == Modifier.FAR ? 500 : 0;
                int maximum = Math.min(TargetDistance.MAX_RADIUS_BLOCKS, Math.max(128, radius * 2));
                add(suggestions, terms, i, range(term, minimum, maximum), Kind.WIDEN);
            }
            if (term.modifier == Modifier.SOME) {
                var reduced = term.someCount > 2 && term.someCount != 5 ? new ParsedQuery.Term(term.canonical, term.category,
                        Modifier.SOME, term.someCount > 5 ? Math.max(5, term.someCount / 2) : 2, 0, 0,term.loot,term.pinned,term.tags) : range(term, 0, max(term, config));
                add(suggestions, terms, i, reduced, Kind.REDUCE_COUNT);
            }
        }
        if (terms.size() > 1) {
            for (int i = terms.size() - 1; i >= 0 && suggestions.size() < 10; i--)
                if (!terms.get(i).pinned && terms.get(i).modifier != Modifier.NEVER) add(suggestions, terms, i, null, Kind.DROP);
        }
        if(query.place()!=null) suggestions.replaceAll(a->new Advice(a.kind(),a.target(),a.before(),a.after(),query.place().prefix()+a.query()));
        return new Report(List.copyOf(suggestions), conflict || terms.size() >= 5 || suggestions.stream().anyMatch(a -> a.kind() == Kind.STRONGHOLD), conflict);
    }

    private static int max(ParsedQuery.Term term, SearchConfig config) {
        return switch (term.modifier) {
            case BETWEEN -> term.betweenMax;
            case AT -> 128;
            case NEAR -> 200;
            case IN -> 64;
            case UNDER -> 0;
            case SOME -> term.someCount >= 5 ? 640 : 320;
            case FAR -> 1000;
            case ONLY -> 500;
            default -> (term.category == KeywordDictionary.Category.BIOME ? config.biomeCheckRadiusChunks() : config.searchRadiusChunks()) * 16;
        };
    }

    private static ParsedQuery.Term range(ParsedQuery.Term term, int minimum, int maximum) {
        return new ParsedQuery.Term(term.canonical, term.category, Modifier.BETWEEN, 2, minimum, maximum,term.loot,term.pinned,term.tags);
    }

    private static void add(List<Advice> out, List<ParsedQuery.Term> terms, int index, ParsedQuery.Term replacement, Kind kind) {
        List<ParsedQuery.Term> edited = new ArrayList<>(terms);
        var original = edited.get(index);
        if (replacement == null) edited.remove(index); else edited.set(index, replacement);
        if (edited.isEmpty()) return;
        String proposed = serialize(edited);
        if (out.stream().noneMatch(s -> s.query.equals(proposed)))
            out.add(new Advice(kind, original.canonical, original.toString(),
                    replacement == null ? "" : replacement.toString(), proposed));
    }

    public static String serialize(List<ParsedQuery.Term> terms) {
        return terms.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(terms.stream().anyMatch(t -> t.loot != null || !t.tags.empty()) ? " AND " : " "));
    }

    private QueryAdvisor() {}
}
