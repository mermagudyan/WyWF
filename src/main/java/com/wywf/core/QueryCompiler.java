package com.wywf.core;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class QueryCompiler {
    private static final Pattern TOKENS = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_:])(?:and|or|xor|not|и|или|либо|не)(?![\\p{L}\\p{N}_])|[()]");
    private static final Pattern EMPTY_WORDS = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_:])(?:please|find|хочу|найди|найти|пожалуйста)(?![\\p{L}\\p{N}_])");
    private final Function<String, ParsedQuery> leaves;
    private final List<String> tokens = new ArrayList<>();
    private final List<ParsedQuery.Term> terms = new ArrayList<>();
    private final List<String> ignored = new ArrayList<>();
    private int cursor;

    private QueryCompiler(Function<String, ParsedQuery> leaves) { this.leaves = leaves; }
    public static ParsedQuery compile(String input, Function<String, ParsedQuery> leaves) { return compile(input, leaves, leaves); }
    public static ParsedQuery compile(String input, Function<String, ParsedQuery> plain, Function<String, ParsedQuery> leaves) {
        if (input == null) return plain.apply(null);
        String text = EMPTY_WORDS.matcher(LootConstraint.protectConnectors(input)).replaceAll(" ").trim()
                .replaceAll("(?iu)\\bat\\s+least\\b|(?<![\\p{L}])не\\s+менее(?![\\p{L}])", "at_least")
                .replaceAll("(?iu)\\bat\\s+most\\b|(?<![\\p{L}])не\\s+более(?![\\p{L}])", "at_most")
                .replaceAll("(?iu)\\bnext\\s+to\\b", "at")
                .replaceAll("(?iu)в\\s+радиусе", "within");
        if (!TOKENS.matcher(text).find()) {
            var parsed = plain.apply(text);
            return new ParsedQuery(input, parsed.terms(), parsed.ignoredWords(), parsed.logic(), parsed.error());
        }
        var compiler = new QueryCompiler(leaves);
        var matcher = TOKENS.matcher(text); int end = 0;
        while (matcher.find()) {
            compiler.add(text.substring(end, matcher.start()));
            compiler.add(matcher.group().toUpperCase(Locale.ROOT)); end = matcher.end();
        }
        compiler.add(text.substring(end));
        if (compiler.tokens.size() > 256) return new ParsedQuery(input, List.of(), List.of(), null, "Query is too long");
        try {
            QueryLogic.Node node = compiler.parseOr();
            if (compiler.cursor != compiler.tokens.size()) throw new IllegalArgumentException("Unexpected token");
            return new ParsedQuery(input, compiler.terms, compiler.ignored, node, null);
        } catch (IllegalArgumentException error) {
            return new ParsedQuery(input, compiler.terms, compiler.ignored, null, error.getMessage());
        }
    }
    public static String closingSuggestion(String input) {
        int balance = 0;
        for (char c : input.toCharArray()) { if (c == '(') balance++; if (c == ')') balance--; if (balance < 0) return null; }
        return balance > 0 && balance <= 8 ? input + ")".repeat(balance) : null;
    }
    private void add(String token) { if (!token.isBlank()) tokens.add(token.trim()); }
    private boolean take(String... values) {
        if (cursor == tokens.size()) return false;
        for (String value : values) if (tokens.get(cursor).equals(value)) { cursor++; return true; }
        return false;
    }
    private QueryLogic.Node parseOr() {
        var node = parseXor();
        while (take("OR", "ИЛИ", "ЛИБО")) node = new QueryLogic.Binary(QueryLogic.Op.OR, node, parseXor());
        return node;
    }
    private QueryLogic.Node parseXor() {
        var node = parseAnd();
        while (take("XOR")) node = new QueryLogic.Binary(QueryLogic.Op.XOR, node, parseAnd());
        return node;
    }
    private QueryLogic.Node parseAnd() {
        var node = unary();
        while (cursor < tokens.size()) {
            String next = tokens.get(cursor);
            if (take("AND", "И")) node = new QueryLogic.Binary(QueryLogic.Op.AND, node, unary());
            else if (Set.of(")", "OR", "ИЛИ", "ЛИБО", "XOR").contains(next)) break;
            else node = new QueryLogic.Binary(QueryLogic.Op.AND, node, unary());
        }
        return node;
    }
    private QueryLogic.Node unary() {
        if (take("NOT", "НЕ")) return new QueryLogic.Not(unary());
        if (take("(")) {
            var node = parseOr();
            if (!take(")")) throw new IllegalArgumentException("Missing closing bracket");
            return node;
        }
        if (cursor == tokens.size() || Set.of(")", "AND", "OR", "XOR", "И", "ИЛИ", "ЛИБО").contains(tokens.get(cursor)))
            throw new IllegalArgumentException("Missing condition");
        var leaf = leaves.apply(tokens.get(cursor++));
        ignored.addAll(leaf.ignoredWords()); terms.addAll(leaf.terms());
        if (leaf.error() != null) throw new IllegalArgumentException(leaf.error());
        if (leaf.isEmpty()) throw new IllegalArgumentException("Unrecognized condition");
        QueryLogic.Node node = new QueryLogic.Leaf(leaf.terms().getFirst());
        for (int i = 1; i < leaf.terms().size(); i++) node = new QueryLogic.Binary(QueryLogic.Op.AND, node, new QueryLogic.Leaf(leaf.terms().get(i)));
        return node;
    }
}
