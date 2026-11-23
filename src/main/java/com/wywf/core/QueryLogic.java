package com.wywf.core;

import java.util.*;
import java.util.function.Function;

public final class QueryLogic {
    public enum Op { AND, OR, XOR }
    public enum Truth { YES, NO, UNKNOWN;
        public static Truth of(boolean value) { return value ? YES : NO; }
        public Truth not() { return this == UNKNOWN ? UNKNOWN : this == YES ? NO : YES; }
    }
    public sealed interface Node permits Leaf, Binary, Not {}
    public record Leaf(ParsedQuery.Term term) implements Node {}
    public record Binary(Op op, Node left, Node right) implements Node {}
    public record Not(Node child) implements Node {}

    public static Truth evaluate(Node node, Function<ParsedQuery.Term, Truth> test) {
        if (node instanceof Leaf leaf) return test.apply(leaf.term);
        if (node instanceof Not not) return evaluate(not.child, test).not();
        var binary = (Binary) node;
        Truth a = evaluate(binary.left, test);
        if (binary.op == Op.AND && a == Truth.NO || binary.op == Op.OR && a == Truth.YES) return a;
        Truth b = evaluate(binary.right, test);
        return switch (binary.op) {
            case AND -> a == Truth.NO || b == Truth.NO ? Truth.NO : a == Truth.UNKNOWN || b == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.YES;
            case OR -> a == Truth.YES || b == Truth.YES ? Truth.YES : a == Truth.UNKNOWN || b == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.NO;
            case XOR -> a == Truth.UNKNOWN || b == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.of(a != b);
        };
    }
    public static boolean conjunction(Node node) {
        return node instanceof Leaf || node instanceof Binary b && b.op == Op.AND && conjunction(b.left) && conjunction(b.right);
    }
    public static String render(Node node) {
        return render(node, term -> (term.category == KeywordDictionary.Category.SPAWN ? "spawn " : "") + term);
    }
    public static String render(Node node, Function<ParsedQuery.Term, String> label) {
        if (node instanceof Leaf leaf) return label.apply(leaf.term);
        if (node instanceof Not not) return "NOT (" + render(not.child, label) + ")";
        var b = (Binary) node;
        return "(" + render(b.left, label) + " " + b.op + " " + render(b.right, label) + ")";
    }
    public static SearchResult merge(ParsedQuery query, long seed, int x, int z, List<SearchResult> witnesses) {
        var structures = new LinkedHashSet<String>();
        var biomes = new LinkedHashSet<String>();
        Map<String, int[]> positions = new HashMap<>();
        Map<String, Integer> distances = new HashMap<>();
        for (var r : witnesses) {
            structures.addAll(r.matchedStructures);
            biomes.addAll(r.matchedBiomes);
            positions.putAll(r.structurePositions);
            distances.putAll(r.biomeDistances);
        }
        var first = witnesses.isEmpty() ? null : witnesses.getFirst();
        return new SearchResult(seed, x, z, first == null ? x : first.structureX, first == null ? z : first.structureZ,
                query.primaryTarget().orElse("query"), List.copyOf(structures), List.copyOf(biomes), "", positions, distances);
    }
    private QueryLogic() {}
}
