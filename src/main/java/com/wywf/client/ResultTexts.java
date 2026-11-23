package com.wywf.client;

import com.wywf.core.*;
import net.minecraft.network.chat.Component;
import java.util.*;

final class ResultTexts {
    static String text(String key, Object... values) {
        String template = ConstructorTexts.tr("wywf.results." + key);
        return values.length == 0 ? template : String.format(Locale.ROOT, template, values);
    }

    static Component label(String key, Object... values) { return Component.literal(text(key, values)); }

    static String name(String canonical) {
        if(canonical.startsWith("wywf:"))return MapTexts.text("feature_"+canonical.substring(5));
        KeywordDictionary.Lang language = ConstructorTexts.displayLanguage(KeywordDictionary.Lang.AUTO);
        for (SearchDraft.Target target : SearchDraft.Target.values()) {
            if (target.canonical().equals(canonical)) return target.phrase(language);
        }
        var id = net.minecraft.resources.Identifier.tryParse(canonical);
        if (id != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id))
            return Component.translatable(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(id).getDescriptionId()).getString();
        return canonical.substring(canonical.indexOf(':') + 1).replace('_', ' ');
    }

    private static boolean russian() { return ConstructorTexts.displayLanguage(KeywordDictionary.Lang.AUTO) == KeywordDictionary.Lang.RU; }
    static String normalized(ParsedQuery query) { return QueryDescription.describe(query, russian(), ResultTexts::name); }
    private static String condition(ParsedQuery.Term term) { return QueryDescription.condition(term, russian(), ResultTexts::name); }
    static String names(List<String> ids, ParsedQuery query) {
        if (ids.isEmpty()) return text("none");
        Set<String> labels = new LinkedHashSet<>();
        for (String id : ids) {
            List<ParsedQuery.Term> terms = query.terms().stream().filter(t -> t.canonical.equals(id)).toList();
            if (terms.isEmpty()) labels.add(name(id));
            else terms.forEach(t -> labels.add(condition(t)));
        }
        return String.join(", ", labels);
    }

    static String centerSummary(SearchResult result) {
        double distance = Double.POSITIVE_INFINITY;
        for (var entry : result.structurePositions.entrySet()) {
            int[] position=entry.getValue();
            var dimension=DimensionSpace.of(entry.getKey().split("#",2)[0]);
            if (position.length >= 2) distance = Math.min(distance,
                    Math.hypot((double) position[0] - dimension.coordinate(result.centerX), (double) position[1] - dimension.coordinate(result.centerZ)));
        }
        if (!Double.isFinite(distance)) {
            for (int value : result.biomeDistances.values()) if (value >= 0) distance = Math.min(distance, value);
        }
        return text("center", result.centerX, result.centerZ)
                + (Double.isFinite(distance) ? " · " + text("distance_short", Math.round(distance)) : "");
    }

    static String centerSummary(SearchResult result,ParsedQuery query,SearchConfig config) {
        var saved=com.wywf.verify.PreviewWorld.find(result,query,config);
        if(saved==null||saved.anchors().isEmpty())return centerSummary(result);
        double distance=Double.POSITIVE_INFINITY;
        for(var a:saved.anchors()){
            var dim=DimensionSpace.of(a.id());
            distance=Math.min(distance,TargetDistance.blocks(query,a.id(),dim.coordinate(result.centerX),dim.coordinate(result.centerZ),a.x(),a.z(),a.minX(),a.minZ(),a.maxX(),a.maxZ()));
        }
        return text("center",result.centerX,result.centerZ)+" · "+text("distance_short",Math.round(distance));
    }

    static List<String> details(SearchResult result,ParsedQuery query){return details(result,query,List.of());}
    static List<String> details(SearchResult result,ParsedQuery query,List<com.wywf.verify.VerifiedAnchor> anchors) {
        List<String> lines = new ArrayList<>();
        lines.add(MapTexts.text("candidate_ready"));
        lines.add(text("query", normalized(query)));
        if (query.hasLogic()) lines.add(MapTexts.text("understood", normalized(query)));
        for (ParsedQuery.Term term : query.terms()) lines.add(text("condition", condition(term)));
        if (!query.ignoredWords().isEmpty()) lines.add(text("ignored", String.join(", ", query.ignoredWords())));
        lines.add(text("center", result.centerX, result.centerZ));
        lines.add(text("structures", names(result.matchedStructures, query)));
        for(var a:anchors){
            var dimension=DimensionSpace.of(a.id());
            long distance=Math.round(TargetDistance.footprint(dimension.coordinate(result.centerX),dimension.coordinate(result.centerZ),a.minX(),a.minZ(),a.maxX(),a.maxZ()));
            String title=MapTexts.text("dimension_"+dimension.name().toLowerCase(Locale.ROOT))+" · "+name(a.id())+" · "+MapTexts.text("distance_footprint");
            lines.add(text("position",title,a.x(),a.z(),distance));
        }
        if(anchors.isEmpty())result.structurePositions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            int[] pos = entry.getValue();
            if (pos.length < 2) return;
            String[] parts = entry.getKey().split("#", 2);
            String title = name(parts[0]) + (parts.length > 1 ? " #" + parts[1] : "");
            DimensionSpace dimension = DimensionSpace.of(parts[0]);
            long distance = Math.round(Math.hypot((double) pos[0] - dimension.coordinate(result.centerX),
                    (double) pos[1] - dimension.coordinate(result.centerZ)));
            title = MapTexts.text("dimension_"+dimension.name().toLowerCase(Locale.ROOT)) + " · " + title + " · " + MapTexts.text("distance_start");
            lines.add(text("position", title, pos[0], pos[1], distance));
        });
        if(anchors.isEmpty())for (String id : new LinkedHashSet<>(result.matchedStructures)) {
            if (!result.structurePositions.containsKey(id)) lines.add(text("position_unknown", name(id)));
        }
        lines.add(text("biomes", names(result.matchedBiomes, query)));
        for (String id : new LinkedHashSet<>(result.matchedBiomes)) {
            Integer distance = result.biomeDistances.get(id);
            lines.add(distance != null && distance >= 0 ? text("biome_distance", name(id), distance)
                    : text("position_unknown", name(id)));
        }
        lines.add(text("distance_help"));
        return List.copyOf(lines);
    }

    static List<String> lootLines(SearchResult result, ParsedQuery query, SearchConfig config) {
        var saved = com.wywf.verify.PreviewWorld.find(result, query, config);
        if (saved == null) return List.of();
        return lootLines(saved.loot);
    }
    static List<String> lootLines(List<com.wywf.verify.LootWitness> witnesses) {
        return witnesses.stream().map(w -> MapTexts.text("dimension_" + DimensionSpace.of(w.structure()).name().toLowerCase(Locale.ROOT)) + " · " + text("loot", w.items().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(e -> e.getValue() + " " + name(e.getKey()))
                .collect(java.util.stream.Collectors.joining(", ")), w.x(), w.y(), w.z())).toList();
    }
    private ResultTexts() {}
}
