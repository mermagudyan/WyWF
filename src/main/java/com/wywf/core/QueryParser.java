package com.wywf.core;

import java.util.*;

public final class QueryParser {

    private final KeywordDictionary dict;

    private static final Map<String, Modifier> MODIFIERS = buildModifiers();

    public QueryParser(KeywordDictionary dict) {
        this.dict = dict;
    }

    private static Map<String, Modifier> buildModifiers() {
        Map<String, Modifier> m = new HashMap<>();

        for (String w : new String[]{"near", "nearby", "close", "beside", "next",
                "рядом", "около", "вблизи", "недалеко", "возле", "близко"}) {
            m.put(w, Modifier.NEAR);
        }
        for (String w : new String[]{"in", "inside", "within",
                "в", "во", "внутри"}) {
            m.put(w, Modifier.IN);
        }
        for (String w : new String[]{"on", "atop", "upon",
                "на", "поверх"}) {
            m.put(w, Modifier.ON);
        }
        for (String w : new String[]{"some", "several", "many", "multiple", "cluster",
                "несколько", "много", "куча", "группа", "скопление"}) {
            m.put(w, Modifier.SOME);
        }
        for (String w : new String[]{"far", "distant", "away", "remote",
                "далеко", "вдали", "далеки", "поодаль"}) {
            m.put(w, Modifier.FAR);
        }
        for (String w : new String[]{"under", "beneath", "below", "underneath",
                "под", "снизу"}) {
            m.put(w, Modifier.UNDER);
        }
        for (String w : new String[]{"never", "no", "not", "without",
                "нет", "не", "без", "никакого", "никаких"}) {
            m.put(w, Modifier.NEVER);
        }
        return m;
    }

    public ParsedQuery parse(String input) {
        if (input == null || input.isBlank()) {
            return new ParsedQuery("", List.of());
        }

        String text = input.toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
                lastSpace = false;
            } else {
                if (!lastSpace) { sb.append(' '); lastSpace = true; }
            }
        }
        String normalized = sb.toString().trim();

        List<ParsedQuery.Term> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int i = 0;
        int len = normalized.length();
        String[] out = new String[1];
        Modifier pending = Modifier.DEFAULT;

        while (i < len) {
            char c = normalized.charAt(i);
            if (c == ' ') { i++; continue; }

            int wordEnd = i;
            while (wordEnd < len && normalized.charAt(wordEnd) != ' ') wordEnd++;
            String word = normalized.substring(i, wordEnd);

            Modifier mod = MODIFIERS.get(word);
            if (mod != null) {
                pending = mod;
                i = wordEnd;
                continue;
            }

            int matched = dict.matchAt(normalized, i, out);
            if (matched > 0) {
                String canonical = out[0];
                if (canonical != null) {
                    KeywordDictionary.Entry e = dict.get(canonical);
                    if (e != null) {
                        String dedupeKey = canonical + "#" + pending.name();
                        if (seen.add(dedupeKey)) {
                            terms.add(new ParsedQuery.Term(canonical, e.category, pending));
                        }
                    }
                }
                pending = Modifier.DEFAULT;
                i += matched;
            } else {
                i = wordEnd;
            }
        }

        return new ParsedQuery(input, terms);
    }

    public boolean isQuery(String input) {
        return !parse(input).isEmpty();
    }
}
