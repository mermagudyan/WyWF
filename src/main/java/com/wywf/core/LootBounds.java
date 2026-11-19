package com.wywf.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

public final class LootBounds {
    public static final int UNKNOWN = 1_000_000_000;
    public record Limit(int chest, int roll) {}
    public record Summary(Map<String, Limit> items, boolean complete) {
        public Summary { items = Map.copyOf(items); }
    }
    public static Summary analyze(JsonElement table, Function<String, JsonElement> resolve) {
        return table(table, resolve, new HashSet<>());
    }
    private static Summary table(JsonElement value, Function<String, JsonElement> resolve, Set<String> visiting) {
        if (value == null || !value.isJsonObject()) return unknown();
        var table = value.getAsJsonObject();
        Map<String, Limit> totals = new HashMap<>(); boolean complete = harmless(table.get("modifier"));
        if (table.has("functions")) complete &= harmless(table.get("functions"));
        if (!table.has("pools")) return new Summary(totals, complete);
        for (var element : table.getAsJsonArray("pools")) {
            var pool = element.getAsJsonObject(); Map<String, Limit> perRoll = new HashMap<>();
            complete &= harmless(pool.get("modifier")) && harmless(pool.get("functions"));
            int rolls = maximum(pool.get("rolls"));
            for (var entry : pool.getAsJsonArray("entries")) {
                var sub = entry(entry, resolve, visiting); complete &= sub.complete;
                sub.items.forEach((id, limit) -> perRoll.merge(id, limit, (a,b) -> new Limit(Math.max(a.chest,b.chest),Math.max(a.roll,b.roll))));
            }
            if (rolls == UNKNOWN) complete = false;
            for (var e : perRoll.entrySet()) {
                var n = new Limit(multiply(e.getValue().chest, rolls), e.getValue().roll);
                totals.merge(e.getKey(), n, (a,b) -> new Limit(add(a.chest,b.chest),Math.max(a.roll,b.roll)));
            }
        }
        return new Summary(totals, complete);
    }
    private static Summary entry(JsonElement value, Function<String, JsonElement> resolve, Set<String> visiting) {
        if (!value.isJsonObject()) return unknown();
        var e = value.getAsJsonObject(); String type = text(e,"type");
        if (e.has("weight") && e.get("weight").isJsonPrimitive() && e.get("weight").getAsInt() <= 0) return new Summary(Map.of(),true);
        if (type.equals("minecraft:empty")) return new Summary(Map.of(),true);
        if (e.has("children")) {
            Map<String, Limit> items = new HashMap<>(); boolean complete = Set.of("minecraft:group", "minecraft:sequence", "minecraft:alternatives").contains(type), sum = !type.equals("minecraft:alternatives");
            for (var child : e.getAsJsonArray("children")) {
                var sub = entry(child,resolve,visiting); complete &= sub.complete;
                sub.items.forEach((id,n) -> items.merge(id,n,(a,b) -> new Limit(sum ? add(a.chest,b.chest) : Math.max(a.chest,b.chest),sum ? add(a.roll,b.roll) : Math.max(a.roll,b.roll))));
            }
            return new Summary(items,complete && harmless(e.get("modifier")) && harmless(e.get("functions")));
        }
        if (type.equals("minecraft:loot_table")) {
            var ref = e.has("value") ? e.get("value") : e.get("name");
            if (ref == null) return unknown();
            Summary sub;
            if (ref.isJsonObject()) sub = table(ref, resolve, visiting);
            else {
                String id = ref.getAsString(); if (!visiting.add(id)) return unknown();
                sub = table(resolve.apply(id), resolve, visiting); visiting.remove(id);
            }
            Map<String, Limit> items = new HashMap<>();
            sub.items.forEach((id, limit) -> items.put(id, new Limit(limit.chest, limit.chest)));
            return new Summary(items, sub.complete && harmless(e.get("modifier")) && harmless(e.get("functions")));
        }
        if (!type.equals("minecraft:item") || !e.has("name")) return unknown();
        String id = e.get("name").getAsString(); int count = 1; boolean complete = true, conditionalBook = false;
        for (JsonElement modifier : modifiers(e)) {
            if (!modifier.isJsonObject()) { complete = false; count = UNKNOWN; continue; }
            var f = modifier.getAsJsonObject(); String kind = f.has("function") ? text(f,"function") : text(f,"type");
            if (kind.equals("minecraft:set_count")) {
                int n = maximum(f.get("count"));
                count = f.has("add") && f.get("add").getAsBoolean() ? add(count,n) : Math.max(count,n);
            } else if ((kind.equals("minecraft:enchant_randomly") || kind.equals("minecraft:enchant_with_levels")) && id.equals("minecraft:book")) {
                conditionalBook |= f.has("conditions");
                id = "minecraft:enchanted_book";
            } else if (!harmless(modifier)) { complete = false; count = UNKNOWN; }
        }
        var limit = new Limit(count, count);
        return new Summary(conditionalBook ? Map.of(id, limit, "minecraft:book", limit) : Map.of(id, limit), complete);
    }
    private static List<JsonElement> modifiers(JsonObject o) {
        List<JsonElement> result = new ArrayList<>();
        if (o.has("modifier")) flatten(o.get("modifier"),result);
        if (o.has("functions")) flatten(o.get("functions"),result);
        return result;
    }
    private static void flatten(JsonElement value,List<JsonElement> out) {
        if (value.isJsonArray()) { for(var v:value.getAsJsonArray()) flatten(v,out); return; }
        if (value.isJsonObject() && text(value.getAsJsonObject(),"type").equals("minecraft:sequence") && value.getAsJsonObject().has("functions"))
            flatten(value.getAsJsonObject().get("functions"),out);
        else out.add(value);
    }
    private static boolean harmless(JsonElement value) {
        if (value == null || value.isJsonNull()) return true;
        List<JsonElement> parts = new ArrayList<>(); flatten(value,parts);
        for (var part:parts) {
            if (!part.isJsonObject()) return false;
            var o=part.getAsJsonObject(); String k=o.has("function")?text(o,"function"):text(o,"type");
            if (!Set.of("minecraft:set_damage","minecraft:set_name","minecraft:set_lore","minecraft:set_enchantments",
                    "minecraft:set_potion",
                    "minecraft:explosion_decay","minecraft:set_instrument").contains(k)) return false;
        }
        return true;
    }
    private static int maximum(JsonElement v) {
        if (v == null) return UNKNOWN;
        try {
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return Math.min(UNKNOWN,Math.max(0,(int)Math.ceil(v.getAsDouble())));
            if (!v.isJsonObject()) return UNKNOWN;
            var o=v.getAsJsonObject(); String t=text(o,"type");
            if (t.equals("minecraft:uniform")) return maximum(o.get("max"));
            if (t.equals("minecraft:constant")) return maximum(o.get("value"));
            return UNKNOWN;
        } catch(RuntimeException error) { return UNKNOWN; }
    }
    private static String text(JsonObject o,String key) { return o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString():""; }
    private static int multiply(int a,int b) { return (int)Math.min(UNKNOWN,(long)a*b); }
    private static int add(int a,int b) { return (int)Math.min(UNKNOWN,(long)a+b); }
    private static Summary unknown() { return new Summary(Map.of(),false); }
    private LootBounds() {}
}