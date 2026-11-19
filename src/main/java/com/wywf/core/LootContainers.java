package com.wywf.core;

import java.util.*;

public final class LootContainers {
    private static final Set<String> VILLAGE = Set.of("weaponsmith", "toolsmith", "armorer", "butcher", "cartographer",
            "fletcher", "fisher", "mason", "shepherd", "tannery", "temple", "plains_house", "desert_house", "savanna_house", "snowy_house", "taiga_house");
    public static String normalize(String value) {
        return switch(value) {
            case "smithy", "кузница", "кузнице", "оружейника" -> "weaponsmith";
            case "leatherworker", "кожевник", "кожевника" -> "tannery";
            case "инструментальщика" -> "toolsmith";
            case "бронника" -> "armorer";
            default -> value.startsWith("minecraft:chests/village/village_") && VILLAGE.contains(value.substring(33)) ? value.substring(33) : value;
        };
    }
    public static boolean village(String value) { return VILLAGE.contains(value); }
    public static boolean buildingOnly(String value) { return Set.of("weaponsmith", "toolsmith", "armorer").contains(value); }
    public static boolean valid(String value) { return value.equals("any") || village(value) || value.matches("minecraft:chests/[a-z0-9_/]+"); }
    public static String table(String value) { return village(value) ? "minecraft:chests/village/village_" + value : value; }
    public static boolean matches(String container, String table) { return container.equals("any") || table(container).equals(table); }
    public static boolean accepts(String canonical, String table) {
        var paths=paths(canonical);
        if(paths==null || paths.stream().noneMatch(p->table.equals("minecraft:chests/"+p)||p.endsWith("/")&&table.startsWith("minecraft:chests/"+p)))return false;
        String id=canonical.substring(canonical.indexOf(':')+1);
        if(id.equals("smithy"))return table.equals(table("weaponsmith"));
        if(id.startsWith("village_")&&table.endsWith("_house"))return table.equals(table(id.substring(8)+"_house"));
        return true;
    }
    public static boolean belongs(String canonical,String container) {
        return container.equals("any") || accepts(canonical,table(container));
    }
    public static List<String> paths(String canonical) {
        String id=canonical.substring(canonical.indexOf(':')+1);
        if(id.startsWith("village")||id.equals("smithy"))return List.of("village/");
        if(id.startsWith("ruined_portal"))return List.of("ruined_portal");
        if(id.startsWith("abandoned_camp"))return List.of("abandoned_camp_common_chest","abandoned_camp_secret_chest");
        return switch(id) {
            case "stronghold" -> List.of("stronghold_corridor","stronghold_crossing","stronghold_library");
            case "desert_pyramid" -> List.of("desert_pyramid");
            case "jungle_temple", "jungle_pyramid" -> List.of("jungle_temple");
            case "mineshaft", "mineshaft_mesa" -> List.of("abandoned_mineshaft");
            case "shipwreck", "shipwreck_beached" -> List.of("shipwreck_map","shipwreck_supply","shipwreck_treasure");
            case "ocean_ruins", "ocean_ruin_cold", "ocean_ruin_warm" -> List.of("underwater_ruin_big","underwater_ruin_small");
            case "mansion", "woodland_mansion" -> List.of("woodland_mansion");
            case "pillager_outpost" -> List.of("pillager_outpost");
            case "ancient_city" -> List.of("ancient_city","ancient_city_ice_box");
            case "igloo" -> List.of("igloo_chest");
            case "buried_treasure" -> List.of("buried_treasure");
            case "trial_chambers" -> List.of("trial_chambers/corridor","trial_chambers/entrance","trial_chambers/intersection","trial_chambers/intersection_barrel","trial_chambers/supply");
            case "fortress" -> List.of("nether_bridge");
            case "bastion", "bastion_remnant" -> List.of("bastion_bridge","bastion_hoglin_stable","bastion_other","bastion_treasure");
            case "end_city" -> List.of("end_city_treasure");
            case "swamp_hut", "ocean_monument", "monument", "nether_fossil", "trail_ruins" -> List.of();
            default -> null;
        };
    }
    public static String name(String container, boolean ru) {
        String path = container.equals("any") ? container : table(container).substring("minecraft:chests/".length());
        return (ru ? Labels.RUSSIAN : Labels.ENGLISH).getOrDefault(path, path.replace('/', ' ').replace('_', ' '));
    }

    private static final class Labels {
        private static final Map<String, String> ENGLISH = load("en_us");
        private static final Map<String, String> RUSSIAN = load("ru_ru");

        private static Map<String, String> load(String language) {
            try (var stream = LootContainers.class.getResourceAsStream("/assets/wywf/data/loot_" + language + ".json")) {
                if (stream == null) throw new IllegalStateException("Missing loot labels for " + language);
                var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8);
                return Map.copyOf(new com.google.gson.Gson().fromJson(reader,
                        new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType()));
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }
    }

    private LootContainers() {}
}
