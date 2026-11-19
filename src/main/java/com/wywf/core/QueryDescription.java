package com.wywf.core;

import java.util.function.Function;
import java.util.stream.Collectors;

public final class QueryDescription {
    public static String describe(ParsedQuery query, boolean ru, Function<String, String> name) {
        String conditions = query.hasLogic() ? node(query.logic(), ru, name)
                : query.terms().stream().map(t -> condition(t, ru, name)).collect(Collectors.joining(", "));
        return query.place()==null ? (ru ? "Найти мир: " : "Find a world with ")+conditions
                : "FindThisPlace — "+query.place().seed()+": "+conditions;
    }
    private static String node(QueryLogic.Node node, boolean ru, Function<String, String> name) {
        if (node instanceof QueryLogic.Leaf leaf) return condition(leaf.term(), ru, name);
        if (node instanceof QueryLogic.Not not) return (ru ? "не выполняется условие «" : "the following is false: “") + node(not.child(), ru, name) + (ru ? "»" : "”");
        var b = (QueryLogic.Binary) node;
        String a = node(b.left(), ru, name), c = node(b.right(), ru, name);
        if (b.op() == QueryLogic.Op.AND && QueryLogic.conjunction(b)) return a + ", " + c;
        return switch (b.op()) {
            case AND -> (ru ? "оба условия: «" : "both conditions: “") + a + (ru ? "» и «" : "” and “") + c + (ru ? "»" : "”");
            case OR -> (ru ? "хотя бы одно: «" : "at least one of: “") + a + (ru ? "» или «" : "” or “") + c + (ru ? "»" : "”");
            case XOR -> (ru ? "ровно одно: «" : "exactly one of: “") + a + (ru ? "» или «" : "” or “") + c + (ru ? "»" : "”");
        };
    }
    public static String condition(ParsedQuery.Term t, boolean ru, Function<String, String> name) {
        String n = name.apply(t.canonical), blocks = ru ? " блоков" : " blocks";
        String s = switch (t.modifier) {
            case DEFAULT -> n;
            case BETWEEN -> t.betweenMin == 0 ? n + (ru ? " в пределах " : " within ") + t.betweenMax + blocks
                    : n + (ru ? " на расстоянии от " : " between ") + t.betweenMin + (ru ? " до " : " and ") + t.betweenMax + blocks;
            case AT -> n + (ru ? " у начальной точки" : " beside the starting point");
            case NEAR -> n + (ru ? " в пределах 200 блоков" : " within 200 blocks");
            case IN -> (ru ? "внутри " : "inside ") + n;
            case NEVER -> (ru ? "без " : "no ") + n;
            case FAR -> n + (ru ? " на расстоянии от 500 до 1000 блоков" : " between 500 and 1000 blocks");
            case UNDER -> n + (ru ? " под начальной точкой" : " below the starting point");
            case ONLY -> (ru ? "только одна " : "only one ") + n;
            case SOME -> (ru ? "не менее " : "at least ") + t.someCount + " " + n;
            case EXACTLY, AT_LEAST, AT_MOST -> (switch (t.modifier) {
                case EXACTLY -> ru ? "ровно " : "exactly ";
                case AT_LEAST -> ru ? "не менее " : "at least ";
                default -> ru ? "не более " : "at most ";
            }) + t.someCount + " " + n + (ru ? " в пределах " : " within ") + (t.betweenMax > 0 ? t.betweenMax : 500) + blocks;
        };
        if(!t.tags.empty())s+=", "+t.tags.description(ru);
        if (t.category == KeywordDictionary.Category.SPAWN) s = (ru ? "появление на " : "spawn on ") + s;
        if (t.loot != null && t.loot.items().isEmpty()) s += ", " + building(t.loot.container(), ru);
        if (t.loot != null && !t.loot.items().isEmpty()) s += (", " + chest(t.loot.container(), ru) + (ru ? " с " : " containing "))
                + t.loot.items().stream().map(i -> i.count() + " " + name.apply(i.item())
                        + (i.enchantment().isEmpty() ? "" : (ru ? " с зачарованием " : " enchanted with ") + name.apply(i.enchantment()) + " " + i.level())
                        + (i.durability() == 0 ? "" : (ru ? " с прочностью не менее " : " with at least ") + i.durability() + (ru ? " процентов" : " percent durability"))).collect(Collectors.joining(", "));
        if (DimensionSpace.of(t.canonical) == DimensionSpace.NETHER)
            s += ru ? ", в Незере от центра поиска с масштабом один к восьми" : ", in the Nether around the search center scaled eight to one";
        if (DimensionSpace.of(t.canonical) == DimensionSpace.END)
            s += ru ? ", в Крае от тех же координат центра" : ", in the End around the same center coordinates";
        return (t.pinned ? (ru ? "обязательно: " : "required: ") : "")+s;
    }
    private static String building(String container, boolean ru) {
        return switch (container) {
            case "weaponsmith" -> ru ? "есть кузница оружейника" : "a weaponsmith building";
            case "toolsmith" -> ru ? "есть кузница инструментальщика" : "a toolsmith building";
            case "armorer" -> ru ? "есть кузница бронника" : "an armorer building";
            default -> (ru ? "контейнер: " : "container: ") + LootContainers.name(container, ru);
        };
    }
    private static String chest(String container, boolean ru) {
        return switch (container) {
            case "weaponsmith" -> ru ? "сундук оружейника" : "a weaponsmith’s chest";
            case "toolsmith" -> ru ? "сундук инструментальщика" : "a toolsmith’s chest";
            case "armorer" -> ru ? "сундук бронника" : "an armorer’s chest";
            default -> container.equals("any") ? ru ? "один сундук" : "one chest" : LootContainers.name(container, ru);
        };
    }
    private QueryDescription() {}
}