package com.wywf.verify;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wywf.core.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.level.storage.loot.LootTable;
import java.util.*;

public final class LootCatalog {
    public record Issue(ParsedQuery.Term term, LootConstraint.Requirement item, int chestMax, int rollMax, int slotMax, boolean absent) {}
    public record ItemLimit(String item, int chestMax, int rollMax, int slotMax) {}
    public record Choices(List<ItemLimit> items, boolean complete) {
        public Choices { items = List.copyOf(items); }
    }
    public static List<Issue> inspect(CreateWorldScreen creator, ParsedQuery query) {
        if (query.hasAlternatives()) return List.of();
        List<Issue> issues = new ArrayList<>();
        for (var term : query.terms()) {
            if (term.loot == null) continue;
            var choices = term.category == KeywordDictionary.Category.BIOME
                    ? biomeChoices(creator, term.canonical, term.loot.container())
                    : choices(creator, term.canonical, term.loot.container());
            if (!choices.complete()) continue;
            for (var item : term.loot.items()) {
                var limit = choices.items().stream().filter(i -> i.item().equals(item.item())).findFirst()
                        .orElse(new ItemLimit(item.item(), 0, 0, 0));
                if (item.count() > limit.chestMax()) issues.add(new Issue(term, item, limit.chestMax(), limit.rollMax(), limit.slotMax(), limit.chestMax() == 0));
            }
        }
        return List.copyOf(issues);
    }
    public static List<String> containers(CreateWorldScreen creator, String canonical) {
        if(creator==null)return List.of("any");
        List<String> values=new ArrayList<>();values.add("any");
        creator.getUiState().getSettings().dataPackResources().fullRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE)
                .listElementIds().map(k->k.identifier().toString()).filter(id->LootContainers.accepts(canonical,id))
                .sorted().map(LootContainers::normalize).forEach(values::add);
        return List.copyOf(values);
    }
    public static Choices choices(CreateWorldScreen creator, String canonical, String container) {
        var paths = tablePaths(canonical);
        if (paths == null) return new Choices(List.of(), false);
        return choices(creator, container, id -> LootContainers.accepts(canonical, id), !paths.isEmpty());
    }

    public static Choices biomeChoices(CreateWorldScreen creator, String biome, String container) {
        var structureIds = creator.getUiState().getSettings().worldgenLoadContext().lookupOrThrow(Registries.STRUCTURE)
                .listElementIds().map(k -> k.identifier().toString())
                .filter(id -> DimensionSpace.of(id) == DimensionSpace.of(biome)).filter(LootCatalog::hasContainer).toList();
        return choices(creator, container, table -> structureIds.stream().anyMatch(id -> acceptsTable(id, table)), true);
    }

    static boolean hasContainer(String structure) {
        var paths = tablePaths(structure);
        return paths != null && !paths.isEmpty();
    }

    private static Choices choices(CreateWorldScreen creator, String container,
                                   java.util.function.Predicate<String> availableTable, boolean expectTables) {
        var context = creator.getUiState().getSettings();
        var lookup = context.dataPackResources().fullRegistries().lookup();
        var registry = lookup.lookupOrThrow(Registries.LOOT_TABLE);
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        Map<String, JsonElement> tables = new HashMap<>();
        java.util.function.Function<String, JsonElement> resolve = id -> tables.computeIfAbsent(id, key -> registry.get(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(key)))
                .flatMap(h -> LootTable.DIRECT_CODEC.encodeStart(ops, h.value()).result()).orElse(null));
        List<String> selected = registry.listElementIds().map(k -> k.identifier().toString())
                .filter(availableTable)
                .filter(id -> LootContainers.matches(container, id)).toList();
        if (expectTables && selected.isEmpty()) return new Choices(List.of(), false);
        List<LootBounds.Summary> summaries = selected.stream().map(id -> LootBounds.analyze(resolve.apply(id), resolve)).toList();
        boolean complete = summaries.stream().allMatch(LootBounds.Summary::complete)
                && context.dataConfiguration().dataPacks().getEnabled().stream().noneMatch(id -> id.startsWith("file/"));
        Map<String, LootBounds.Limit> combined = new TreeMap<>();
        for (var summary : summaries) summary.items().forEach((id, limit) -> combined.merge(id, limit,
                (a, b) -> new LootBounds.Limit(Math.max(a.chest(), b.chest()), Math.max(a.roll(), b.roll()))));
        List<ItemLimit> items = new ArrayList<>();
        for (var entry : combined.entrySet()) {
            var id = Identifier.tryParse(entry.getKey());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) { complete = false; continue; }
            var item = BuiltInRegistries.ITEM.getValue(id);
            if (!item.builtInRegistryHolder().areComponentsBound()) { complete = false; continue; }
            int slot = item.getDefaultInstance().getMaxStackSize();
            items.add(new ItemLimit(entry.getKey(), Math.min(entry.getValue().chest(), slot * 27), entry.getValue().roll(), slot));
        }
        return new Choices(items, complete);
    }    static boolean supported(String canonical) { return tablePaths(canonical) != null; }
    static boolean acceptsTable(String canonical, String table) {
        return LootContainers.accepts(canonical, table);
    }
    private static List<String> tablePaths(String canonical) { return LootContainers.paths(canonical); }
    private LootCatalog() {}
}
