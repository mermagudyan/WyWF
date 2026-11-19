package com.wywf.core;

import java.util.*;
import java.util.regex.*;

public record LootConstraint(List<Requirement> items, String container) {
    public record Stack(String item, int count, Map<String, Integer> enchantments, int durability) {
        public Stack { enchantments = Map.copyOf(enchantments); }
    }
    public record Requirement(String item, int count, String enchantment, int level, int durability) {
        public Requirement(String item, int count) { this(item, count, "", 0, 0); }
        public Requirement {
            if (!enchantment.isEmpty() && (!enchantment.matches("minecraft:[a-z0-9_]+") || level < 1))
                throw new IllegalArgumentException("An enchantment needs a vanilla id and a positive level");
            if (!item.matches("minecraft:[a-z0-9_]+") || count < 1 || count > 4096 || level < 0 || level > 255 || durability < 0 || durability > 100)
                throw new IllegalArgumentException("Loot needs a vanilla item and a quantity from 1 to 4096");
        }
    }
    private static final Pattern WITH = Pattern.compile("(?iu)\\s+(?:with|с)\\s+");
    private static final Map<String, String> ITEMS = Map.ofEntries(
            Map.entry("diamond", "diamond"), Map.entry("diamonds", "diamond"), Map.entry("алмаз", "diamond"),
            Map.entry("алмазы", "diamond"), Map.entry("алмазами", "diamond"),
            Map.entry("obsidian", "obsidian"), Map.entry("обсидиан", "obsidian"), Map.entry("обсидианом", "obsidian"),
            Map.entry("iron", "iron_ingot"), Map.entry("gold", "gold_ingot"), Map.entry("bread", "bread"),
            Map.entry("apple", "apple"), Map.entry("saddle", "saddle"), Map.entry("emerald", "emerald"),
            Map.entry("potato", "potato"), Map.entry("potatoes", "potato"), Map.entry("картофель", "potato"),
            Map.entry("картофелем", "potato"),
            Map.entry("emeralds", "emerald"), Map.entry("изумруды", "emerald"));
    public LootConstraint {
        items = List.copyOf(items);
        container = LootContainers.normalize(container);
        if (items.isEmpty() && container.equals("any") || items.size() > 16) throw new IllegalArgumentException("Loot needs 1 to 16 item requirements");
        if (!LootContainers.valid(container))
            throw new IllegalArgumentException("Unknown chest type");
    }
    public static String protectConnectors(String text) {
        if (text == null) return null;
        return text.replaceAll("(?iu)(diamonds?|obsidian|minecraft:[a-z0-9_]+|алмазы|алмазами|обсидиан|обсидианом)\\s+(?:and|и)\\s+(?=\\d*\\s*(?:diamonds?|obsidian|minecraft:|алмаз|обсидиан))", "$1, ");
    }
    public record Split(String target, LootConstraint loot) {}
    public static String targetPrefix(String text) {
        var matcher = WITH.matcher(text);
        return matcher.find() ? text.substring(0, matcher.start()) : text;
    }
    public static Split split(String text) {
        Matcher with = WITH.matcher(text);
        if (!with.find()) return new Split(text, null);
        String target = text.substring(0, with.start()), tail = text.substring(with.end()).trim().toLowerCase(Locale.ROOT);
        String container = "any";
        if (Set.of("smithy", "weaponsmith", "кузница", "кузницей").contains(tail))
            return new Split(target, new LootConstraint(List.of(), "weaponsmith"));
        if (LootContainers.valid(LootContainers.normalize(tail)) && !tail.equals("any"))
            return new Split(target, new LootConstraint(List.of(), tail));
        Matcher chest = Pattern.compile("(?iu)\\s+(?:in|в)\\s+(?:a\\s+)?([a-z0-9_:/]+|кузнице|оружейника|инструментальщика|бронника|кожевника)$").matcher(tail);
        if (chest.find()) {
            container = switch (chest.group(1)) {
                case "smithy", "кузнице", "оружейника" -> "weaponsmith";
                case "инструментальщика" -> "toolsmith";
                case "бронника" -> "armorer";
                default -> chest.group(1);
            };
            tail = tail.substring(0, chest.start());
        }
        List<Requirement> items = new ArrayList<>();
        for (String part : tail.split("\\s*[,;]\\s*")) {
            Matcher item = Pattern.compile("(?iu)^(?:(\\d+)\\s+)?([\\p{L}0-9_:]+)(?:\\s+enchanted\\s+([a-z0-9_:]+)\\s+(\\d+))?(?:\\s+durability\\s+(\\d+))?$").matcher(part.trim());
            if (!item.matches()) throw new IllegalArgumentException("Use: village with 2 diamonds, 4 obsidian in smithy; optional: enchanted sharpness 3 durability 80");
            String word = item.group(2), id = word.startsWith("minecraft:") ? word : "minecraft:" + ITEMS.getOrDefault(word, word);
            String enchantment = item.group(3) == null ? "" : item.group(3).contains(":") ? item.group(3) : "minecraft:" + item.group(3);
            items.add(new Requirement(id, item.group(1) == null ? 1 : Integer.parseInt(item.group(1)), enchantment,
                    item.group(4) == null ? 0 : Integer.parseInt(item.group(4)), item.group(5) == null ? 0 : Integer.parseInt(item.group(5))));
        }
        Map<String, Requirement> totals = new LinkedHashMap<>();
        for (var item : items) {
            var old = totals.get(item.item);
            if (old != null && (!old.enchantment.equals(item.enchantment) || old.level != item.level || old.durability != item.durability))
                throw new IllegalArgumentException("Use one property rule per item");
            totals.put(item.item, old == null ? item : new Requirement(item.item, Math.addExact(old.count, item.count),
                    item.enchantment, item.level, item.durability));
        }
        return new Split(target, new LootConstraint(List.copyOf(totals.values()), container));
    }
    public boolean matches(Map<String, Integer> contents) {
        return items.stream().allMatch(item -> item.enchantment.isEmpty() && item.durability == 0 && contents.getOrDefault(item.item, 0) >= item.count);
    }
    public boolean matches(List<Stack> stacks) {
        return items.stream().allMatch(item -> stacks.stream().filter(s -> s.item.equals(item.item)
                && (item.enchantment.isEmpty() || s.enchantments.getOrDefault(item.enchantment, 0) >= item.level)
                && (item.durability == 0 || s.durability >= item.durability)).mapToInt(Stack::count).sum() >= item.count);
    }
    public boolean acceptsTable(String table) {
        return LootContainers.matches(container, table);
    }
    public String suffix() {
        if (items.isEmpty()) return " with " + container;
        return " with " + items.stream().map(i -> i.count + " " + i.item + (i.enchantment.isEmpty() ? "" : " enchanted " + i.enchantment + " " + i.level)
                + (i.durability == 0 ? "" : " durability " + i.durability))
                .collect(java.util.stream.Collectors.joining(", "))
                + (container.equals("any") ? "" : " in " + container);
    }
}