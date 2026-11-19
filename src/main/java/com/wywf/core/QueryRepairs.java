package com.wywf.core;

import java.util.regex.*;

public final class QueryRepairs {
    public static String suggest(ParsedQuery query, QueryParser parser, KeywordDictionary dictionary) {
        String candidate = QueryCompiler.closingSuggestion(query.raw());
        if (candidate == null) candidate = query.raw();
        for (String word : query.ignoredWords()) {
            String replacement = dictionary.findSuggestion(word);
            if (replacement == null) continue;
            candidate = candidate.replaceAll("(?iu)(?<![\\p{L}\\p{N}_:])" + Pattern.quote(word)
                    + "(?![\\p{L}\\p{N}_])", Matcher.quoteReplacement(replacement));
        }
        if (candidate.equals(query.raw())) return null;
        ParsedQuery parsed = parser.parse(candidate);
        if (parsed.error() != null || parsed.isEmpty() || parsed.ignoredWords().size() > query.ignoredWords().size()) return null;
        return candidate;
    }
    private QueryRepairs() {}
}
